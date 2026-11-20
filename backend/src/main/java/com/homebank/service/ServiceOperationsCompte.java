package com.homebank.service;

import com.homebank.dto.DemandeVirementDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.ModePaiement;
import com.homebank.utils.Montant;
import com.homebank.utils.Periode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Operations propres aux comptes qui creent des transactions : virement entre
 * deux comptes (deux transactions liees) et ajustement du solde calcule sur la
 * valeur reelle d'un compte.
 */
@Service
public class ServiceOperationsCompte {

    private static final Logger log = LoggerFactory.getLogger(ServiceOperationsCompte.class);

    private final DepotTransaction transactionRepository;
    private final ServiceCompte accountService;
    private final MappeurTransaction transactionMapper;

    public ServiceOperationsCompte(DepotTransaction transactionRepository, ServiceCompte accountService,
                                   MappeurTransaction transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.transactionMapper = transactionMapper;
    }

    /**
     * Aligne le solde calcule d'un compte sur sa valeur reelle a une date
     * (ex : placement dont la valeur suit la bourse, livret sans mouvement
     * saisi) : cree une transaction "Ajustement de solde" du montant de
     * l'ecart, validee d'office (la valeur vient de l'utilisateur), et exclue
     * des rapports de depenses/revenus (voir Transaction#balanceAdjustment).
     * Le solde calcule inclut toutes les transactions jusqu'a `date` incluse,
     * meme regle que le rapprochement (voir ServiceRapprochement).
     */
    @Transactional
    public TransactionDTO adjustAccountBalance(Long accountId, LocalDate date, BigDecimal actualBalance) {
        if (date == null) {
            throw new IllegalArgumentException("La date est obligatoire.");
        }
        if (date.isAfter(LocalDate.now(ZoneId.systemDefault()))) {
            throw new IllegalArgumentException("La date ne peut pas être dans le futur.");
        }
        Periode.verifierAnnee(date, "La date");
        BigDecimal actual = Montant.auCentime(actualBalance, "Le solde réel");
        if (actual == null) {
            throw new IllegalArgumentException("Le solde réel est obligatoire.");
        }
        Compte account = accountService.findById(accountId);
        ServiceCompte.ensureNotArchived(account);

        BigDecimal computedBalance = account.getInitialBalance()
                .add(transactionRepository.sumAmountByAccountIdAsOf(accountId, date));
        BigDecimal gap = actual.subtract(computedBalance);
        if (gap.signum() == 0) {
            throw new IllegalArgumentException("Le solde est déjà de " + computedBalance + " à cette date : aucun ajustement nécessaire.");
        }

        Transaction adjustment = new Transaction();
        adjustment.setAccount(account);
        adjustment.setDate(date);
        adjustment.setAmount(gap);
        adjustment.setPaymentMode(ModePaiement.NONE);
        adjustment.setValidated(true);
        adjustment.setBalanceAdjustment(true);
        Transaction saved = transactionRepository.save(adjustment);
        log.info("Ajustement du solde du compte {} au {} : {} (solde réel {})", accountId, date, gap, actual);
        return transactionMapper.toDTO(saved);
    }

    /**
     * Cree un virement entre deux comptes : deux transactions liees
     * (debit sur le compte source, credit sur le compte destination),
     * chacune reliee a l'autre via transferTransaction, et marquees
     * ModePaiement.TRANSFER ; leur liaison les exclut des rapports de depenses/revenus
     * (voir Transaction#isInternalTransfer).
     */
    @Transactional
    public List<TransactionDTO> createTransfer(DemandeVirementDTO request) {
        if (request.getFromAccountId() == null || request.getToAccountId() == null) {
            throw new IllegalArgumentException("Les deux comptes sont obligatoires.");
        }
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new IllegalArgumentException("Le compte source et le compte destination doivent être différents.");
        }
        BigDecimal amount = Montant.auCentime(request.getAmount(), "Le montant du virement");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Le montant du virement doit être positif.");
        }

        Compte from = accountService.findById(request.getFromAccountId());
        Compte to = accountService.findById(request.getToAccountId());
        ServiceCompte.ensureNotArchived(from);
        ServiceCompte.ensureNotArchived(to);
        ensureSameCurrency(from, to);
        LocalDate date = request.getDate() != null ? request.getDate() : LocalDate.now(ZoneId.systemDefault());
        Periode.verifierAnnee(date, "La date du virement");
        boolean validated = !Boolean.FALSE.equals(request.getValidated());

        Transaction debit = new Transaction();
        debit.setAccount(from);
        debit.setDate(date);
        debit.setAmount(amount.negate());
        String memo = ServiceTransaction.sanitizeMemo(request.getMemo());
        debit.setMemo(memo);
        debit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setValidated(validated);

        Transaction credit = new Transaction();
        credit.setAccount(to);
        credit.setDate(date);
        credit.setAmount(amount);
        credit.setMemo(memo);
        credit.setPaymentMode(ModePaiement.TRANSFER);
        credit.setValidated(validated);

        Transaction savedDebit = transactionRepository.save(debit);
        credit.setTransferTransaction(savedDebit);
        Transaction savedCredit = transactionRepository.save(credit);
        savedDebit.setTransferTransaction(savedCredit);
        Transaction finalDebit = transactionRepository.save(savedDebit);
        log.info("Virement cree du compte {} vers le compte {} : transactions {} et {}",
                from.getId(), to.getId(), finalDebit.getId(), savedCredit.getId());

        return List.of(transactionMapper.toDTO(finalDebit), transactionMapper.toDTO(savedCredit));
    }

    /** Pas de taux de change gere : un virement relie toujours deux comptes de meme devise. */
    static void ensureSameCurrency(Compte first, Compte second) {
        String firstCurrency = first.getCurrency();
        String secondCurrency = second.getCurrency();
        if (firstCurrency == null || !firstCurrency.equalsIgnoreCase(secondCurrency)) {
            throw new IllegalArgumentException(
                    "Les virements entre comptes de devises différentes ne sont pas pris en charge"
            );
        }
    }
}

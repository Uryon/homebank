package com.homebank.service;

import com.homebank.dto.RapprochementDTO;
import com.homebank.dto.ValidationRapprochementDTO;
import com.homebank.model.Compte;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Rapprochement bancaire d'un compte : l'utilisateur saisit le solde affiche
 * sur son releve a une date, l'application le compare au solde qu'elle
 * calcule a la meme date (solde initial + toutes les transactions datees
 * jusqu'a ce jour inclus). Si les deux concordent, les operations encore en
 * attente jusqu'a cette date peuvent etre validees d'un coup.
 */
@Service
public class ServiceRapprochement {

    private static final Logger log = LoggerFactory.getLogger(ServiceRapprochement.class);

    private final ServiceCompte accountService;
    private final DepotTransaction transactionRepository;

    public ServiceRapprochement(ServiceCompte accountService, DepotTransaction transactionRepository) {
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
    }

    public RapprochementDTO compare(Long accountId, LocalDate date, BigDecimal statementBalance) {
        validateDate(date);
        if (statementBalance == null) {
            throw new IllegalArgumentException("Le solde du relevé est obligatoire.");
        }
        Compte account = accountService.findById(accountId);

        BigDecimal bookBalance = account.getInitialBalance()
                .add(transactionRepository.sumAmountByAccountIdAsOf(accountId, date));
        List<Transaction> pending = transactionRepository.findPendingByAccountIdUpTo(accountId, date);
        BigDecimal pendingTotal = pending.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new RapprochementDTO(accountId, date, statementBalance, bookBalance,
                statementBalance.subtract(bookBalance), pending.size(), pendingTotal);
    }

    /**
     * Valide toutes les transactions en attente du compte jusqu'a `date`
     * incluse, ainsi que l'autre cote des virements concernes (meme regle que
     * ServiceTransaction#toggleValidated : les deux cotes d'un virement sont
     * toujours rapproches ensemble).
     */
    @Transactional
    public ValidationRapprochementDTO validatePendingUpTo(Long accountId, LocalDate date) {
        validateDate(date);
        accountService.findById(accountId);

        List<Transaction> pending = transactionRepository.findPendingByAccountIdUpTo(accountId, date);
        for (Transaction t : pending) {
            t.setValidated(true);
            if (t.getTransferTransaction() != null) {
                t.getTransferTransaction().setValidated(true);
            }
        }
        transactionRepository.saveAll(pending);
        log.info("Rapprochement du compte {} au {} : {} transaction(s) validée(s)", accountId, date, pending.size());
        return new ValidationRapprochementDTO(pending.size());
    }

    /** Un releve bancaire porte sur des operations deja passees : pas de date future. */
    private static void validateDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("La date du relevé est obligatoire.");
        }
        if (date.isAfter(LocalDate.now(ZoneId.systemDefault()))) {
            throw new IllegalArgumentException("La date du relevé ne peut pas être dans le futur.");
        }
    }
}

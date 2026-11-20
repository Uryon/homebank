package com.homebank.service;

import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import com.homebank.utils.LongueurTexte;
import com.homebank.utils.Montant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Annulation d'une suppression : recree les transactions a l'identique a
 * partir de leur etat d'avant suppression, en une seule operation (tout ou
 * rien). Contrairement a une saisie, une restauration n'est pas une nouvelle
 * arrivee : elle reste permise sur un compte archive, accepte un montant nul
 * et reprend aussi ce que l'utilisateur ne saisit jamais (libelle bancaire,
 * montant d'avant division, marqueurs « pas un doublon »...).
 */
@Service
public class ServiceRestaurationTransaction {

    private static final Logger log = LoggerFactory.getLogger(ServiceRestaurationTransaction.class);

    private final DepotTransaction transactionRepository;
    private final DepotTransactionSupprimee deletedTransactionRepository;
    private final ServiceCompte accountService;
    private final ServiceCategorie categoryService;
    private final ServiceTiers payeeService;
    private final MappeurTransaction transactionMapper;

    public ServiceRestaurationTransaction(
            DepotTransaction transactionRepository,
            DepotTransactionSupprimee deletedTransactionRepository,
            ServiceCompte accountService,
            ServiceCategorie categoryService,
            ServiceTiers payeeService,
            MappeurTransaction transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.deletedTransactionRepository = deletedTransactionRepository;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.payeeService = payeeService;
        this.transactionMapper = transactionMapper;
    }

    /**
     * Les transactions sont recreees dans l'ordre de leurs anciens ids, pour
     * garder leur ordre relatif dans les listes (triees par date puis id).
     */
    @Transactional
    public List<TransactionDTO> restore(List<TransactionDTO> snapshots) {
        Map<Long, TransactionDTO> snapshotsById = indexById(snapshots);

        List<Tiers> knownPayees = new ArrayList<>(payeeService.findAllForMatching());
        Map<Long, Tiers> payeesById = new HashMap<>();
        knownPayees.forEach(payee -> payeesById.put(payee.getId(), payee));

        Map<Long, Transaction> restoredById = new LinkedHashMap<>();
        snapshotsById.forEach((id, snapshot) ->
                restoredById.put(id, transactionRepository.save(fromSnapshot(snapshot, payeesById, knownPayees))));

        linkTransfers(snapshotsById, restoredById);
        forgetDeletions(snapshotsById.values());

        log.info("Restauration de {} transaction(s) supprimee(s)", restoredById.size());
        return restoredById.values().stream().map(transactionMapper::toDTO).toList();
    }

    private static Map<Long, TransactionDTO> indexById(List<TransactionDTO> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction à restaurer.");
        }
        Map<Long, TransactionDTO> byId = new TreeMap<>();
        for (TransactionDTO snapshot : snapshots) {
            if (snapshot == null || snapshot.getId() == null) {
                throw new IllegalArgumentException("Chaque transaction à restaurer doit indiquer son identifiant d'origine.");
            }
            byId.putIfAbsent(snapshot.getId(), snapshot);
        }
        return byId;
    }

    private Transaction fromSnapshot(TransactionDTO snapshot, Map<Long, Tiers> payeesById, List<Tiers> knownPayees) {
        BigDecimal amount = ServiceTransaction.verifierChampsObligatoires(snapshot);

        Transaction transaction = new Transaction();
        transaction.setAccount(accountService.findById(snapshot.getAccountId()));
        transaction.setDate(snapshot.getDate());
        transaction.setAmount(amount);
        transaction.setPayee(restoredPayee(snapshot, amount, payeesById, knownPayees));
        transaction.setMemo(ServiceTransaction.sanitizeMemo(snapshot.getMemo()));
        transaction.setCategory(snapshot.getCategoryId() != null
                ? categoryService.findById(snapshot.getCategoryId())
                : null);
        transaction.setPaymentMode(ServiceTransaction.paymentModeOrNone(snapshot.getPaymentMode()));
        transaction.setSplitFromAmount(Montant.auCentime(snapshot.getSplitFromAmount(), "Le montant d'origine"));
        transaction.setValidated(snapshot.isValidated());
        transaction.setPeriodicExpense(snapshot.isPeriodicExpense());
        transaction.setExceptional(snapshot.isExceptional());
        transaction.setBankLabel(LongueurTexte.libelleBancaire(snapshot.getBankLabel()));
        transaction.setDuplicateIgnored(snapshot.isDuplicateIgnored());
        transaction.setPeriodicSuggestionIgnored(snapshot.isPeriodicSuggestionIgnored());
        transaction.setBalanceAdjustment(snapshot.isBalanceAdjustment());
        return transaction;
    }

    /**
     * Le tiers d'origine s'il existe encore ; sinon (supprime avec sa derniere
     * transaction), retrouve ou recree d'apres son nom. Sans payeeId, le champ
     * payee n'est qu'un libelle calcule (« Virement vers... », « Ajustement de
     * solde ») : le prendre pour un nom creerait un faux tiers.
     */
    private Tiers restoredPayee(TransactionDTO snapshot, BigDecimal amount,
                                Map<Long, Tiers> payeesById, List<Tiers> knownPayees) {
        if (snapshot.getPayeeId() == null) return null;
        return payeesById.computeIfAbsent(snapshot.getPayeeId(),
                id -> payeeService.resolveOrCreate(snapshot.getPayee(), amount, knownPayees));
    }

    /** Relie les deux cotes de chaque virement restaure (voir ServiceTransaction#createTransfer). */
    private static void linkTransfers(Map<Long, TransactionDTO> snapshotsById, Map<Long, Transaction> restoredById) {
        snapshotsById.forEach((id, snapshot) -> {
            Long twinId = snapshot.getTransferTransactionId();
            if (twinId == null) return;
            TransactionDTO twinSnapshot = snapshotsById.get(twinId);
            if (twinSnapshot == null) {
                log.warn("Restauration : jumelle {} absente pour la transaction {}, restauree sans appariement", twinId, id);
                return;
            }
            ensureValidTransferPair(id, snapshot, twinSnapshot);
            restoredById.get(id).setTransferTransaction(restoredById.get(twinId));
        });
    }

    private static void ensureValidTransferPair(Long id, TransactionDTO side, TransactionDTO twin) {
        boolean mutual = id.equals(twin.getTransferTransactionId());
        boolean distinctAccounts = !side.getAccountId().equals(twin.getAccountId());
        boolean oppositeAmounts = side.getAmount().add(twin.getAmount()).signum() == 0;
        if (!mutual || !distinctAccounts || !oppositeAmounts) {
            throw new IllegalArgumentException("Les deux côtés du virement à restaurer ne correspondent pas.");
        }
    }

    /**
     * Retire l'empreinte laissee par la suppression (voir
     * ServiceTransaction#recordDeletion) : restee en place, elle ferait passer
     * pour deja supprimee une autre ligne identique d'un prochain releve.
     */
    private void forgetDeletions(Collection<TransactionDTO> snapshots) {
        Set<Long> accountIds = snapshots.stream().map(TransactionDTO::getAccountId).collect(Collectors.toSet());
        Map<Empreinte, Deque<TransactionSupprimee>> recorded = new HashMap<>();
        for (Long accountId : accountIds) {
            for (TransactionSupprimee deleted : deletedTransactionRepository.findByAccountId(accountId)) {
                recorded.computeIfAbsent(Empreinte.of(deleted), key -> new ArrayDeque<>()).add(deleted);
            }
        }

        List<TransactionSupprimee> obsolete = new ArrayList<>();
        for (TransactionDTO snapshot : snapshots) {
            Deque<TransactionSupprimee> matching = recorded.get(Empreinte.of(snapshot));
            if (matching != null && !matching.isEmpty()) {
                obsolete.add(matching.poll());
            }
        }
        if (!obsolete.isEmpty()) {
            deletedTransactionRepository.deleteAll(obsolete);
        }
    }

    /** Ce qui identifie une transaction dans le journal des suppressions. */
    private record Empreinte(Long accountId, LocalDate date, BigDecimal amount, String payeeName, String bankLabel) {

        static Empreinte of(TransactionSupprimee deleted) {
            return new Empreinte(deleted.getAccountId(), deleted.getDate(),
                    Montant.auCentime(deleted.getAmount(), "Le montant"), deleted.getPayeeName(), deleted.getBankLabel());
        }

        static Empreinte of(TransactionDTO snapshot) {
            BigDecimal statementAmount = snapshot.getSplitFromAmount() != null
                    ? snapshot.getSplitFromAmount()
                    : snapshot.getAmount();
            return new Empreinte(snapshot.getAccountId(), snapshot.getDate(),
                    Montant.auCentime(statementAmount, "Le montant"),
                    snapshot.getPayeeId() != null ? snapshot.getPayee() : null,
                    snapshot.getBankLabel());
        }
    }
}

package com.homebank.service;

import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotLigneImportee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Suppression de transactions : unitaire, groupee, des operations en attente,
 * et annulation d'une creation ou d'un import. La jumelle d'un virement part
 * toujours avec lui, et les tiers devenus orphelins sont nettoyes apres le
 * commit (voir ElagageTiersOrphelins).
 */
@Service
public class ServiceSuppressionTransaction {

    private static final Logger log = LoggerFactory.getLogger(ServiceSuppressionTransaction.class);

    private final DepotTransaction transactionRepository;
    private final DepotTransactionSupprimee deletedTransactionRepository;
    private final ElagageTiersOrphelins elagageTiers;
    private final DepotLigneImportee importedLineRepository;

    public ServiceSuppressionTransaction(DepotTransaction transactionRepository,
                                         DepotTransactionSupprimee deletedTransactionRepository,
                                         ElagageTiersOrphelins elagageTiers,
                                         DepotLigneImportee importedLineRepository) {
        this.transactionRepository = transactionRepository;
        this.deletedTransactionRepository = deletedTransactionRepository;
        this.elagageTiers = elagageTiers;
        this.importedLineRepository = importedLineRepository;
    }

    private Transaction findEntityById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction introuvable : " + id));
    }

    /**
     * Supprime la transaction, et si elle fait partie d'un virement (voir
     * createTransfer), supprime aussi la transaction jumelle avec elle : les
     * deux se referencent mutuellement (transferTransaction dans les deux
     * sens), donc les laisser desappariees romprait l'invariant "un virement
     * = deux transactions liees" — et de toute facon, supprimer l'une sans
     * l'autre serait rejete par la contrainte de cle etrangere en base.
     */
    @Transactional
    public void delete(Long id) {
        Transaction existing = findEntityById(id);

        Map<Long, Transaction> byId = new LinkedHashMap<>();
        byId.put(existing.getId(), existing);
        // Inclut la jumelle d'un virement, supprimee avec elle.
        List<Transaction> transactions = unlinkTransferPairs(byId);

        transactions.forEach(this::recordDeletion);
        transactionRepository.deleteAll(transactions);
        log.info("Transaction supprimee : {}{}", id, transactions.size() > 1 ? " (avec la jumelle du virement)" : "");

        // Tiers de CHAQUE transaction supprimee (jumelle comprise), comme
        // deleteTransactions : sinon le tiers propre a l'autre cote d'un
        // virement restait orphelin dans la liste des tiers.
        elagageTiers.apresCommit(transactions.stream().map(Transaction::getPayee).toList());
    }

    /**
     * Supprime plusieurs transactions a la fois (selection multiple de
     * l'onglet Transactions) : meme empreinte de suppression (recordDeletion)
     * qu'une suppression unitaire (voir delete), pour qu'un futur reimport du
     * releve source ne les fasse pas reapparaitre. Renvoie le nombre de
     * transactions effectivement supprimees.
     */
    @Transactional
    public int deleteBulk(List<Long> ids) {
        return deleteTransactions(ids, true);
    }

    /**
     * Supprime toutes les transactions en attente (non validees) — pendant
     * equivalent de validateAllPending. Contrairement a deleteBulk, ne laisse
     * PAS d'empreinte de suppression (recordDeletion) : une transaction en
     * attente n'a jamais ete rapprochee/confirmee, ce nettoyage sert
     * generalement a repartir d'une base propre avant un reimport du meme
     * releve, qui doit alors pouvoir la recreer normalement plutot que de la
     * rejeter a tort comme doublon.
     * <p>
     * Un cote de virement en attente dont la jumelle est deja validee est
     * exclu de ce nettoyage : deleteTransactions/unlinkTransferPairs emporte
     * toujours les deux cotes ensemble (voir leur javadoc), ce qui supprimerait
     * ici une transaction validee que l'utilisateur n'a pourtant jamais
     * demande a supprimer (seul "en attente" a ete demande) — a supprimer
     * manuellement si c'est bien l'intention.
     * <p>
     * Avec accountId, seules les transactions en attente de ce compte sont
     * concernees (liste filtree par compte cote frontend : le nombre annonce
     * dans la confirmation doit etre celui reellement supprime) ; la jumelle
     * en attente d'un virement, sur un autre compte, part avec elle.
     */
    @Transactional
    public int deleteAllPending(Long accountId) {
        List<Transaction> pending = findPendingWithTransferTransaction(accountId);
        List<Long> pendingIds = new ArrayList<>();
        int skipped = 0;
        for (Transaction t : pending) {
            Transaction pair = t.getTransferTransaction();
            if (pair != null && pair.isValidated()) {
                skipped++;
                continue;
            }
            pendingIds.add(t.getId());
        }
        if (skipped > 0) {
            log.warn("{} transaction(s) de virement en attente ignorees par le nettoyage : leur jumelle est deja validee (suppression manuelle si besoin)", skipped);
        }
        return pendingIds.isEmpty() ? 0 : deleteTransactions(pendingIds, false);
    }

    /** Transactions en attente de tous les comptes (accountId null) ou d'un seul, jumelle de virement chargee. */
    private List<Transaction> findPendingWithTransferTransaction(Long accountId) {
        return accountId == null
                ? transactionRepository.findByValidatedFalseWithTransferTransaction()
                : transactionRepository.findPendingByAccountIdWithTransferTransaction(accountId);
    }

    /**
     * Complete `byId` avec la jumelle de virement de chaque transaction deja
     * presente (voir createTransfer), puis casse la reference mutuelle
     * transferTransaction des deux cotes : sinon la cle etrangere de chaque
     * cote d'un virement empeche de supprimer l'autre. Factorise entre
     * delete(Long) et deleteTransactions, seule differences etant le nombre
     * de transactions de depart.
     */
    private List<Transaction> unlinkTransferPairs(Map<Long, Transaction> byId) {
        for (Transaction t : List.copyOf(byId.values())) {
            Transaction pair = t.getTransferTransaction();
            if (pair != null) {
                byId.putIfAbsent(pair.getId(), pair);
            }
        }
        List<Transaction> transactions = new ArrayList<>(byId.values());

        List<Transaction> transferSides = transactions.stream()
                .filter(t -> t.getTransferTransaction() != null)
                .toList();
        if (!transferSides.isEmpty()) {
            transferSides.forEach(t -> t.setTransferTransaction(null));
            transactionRepository.saveAll(transferSides);
        }

        return transactions;
    }

    /**
     * Annule une creation (saisie, virement, ajustement, 2e partie d'une
     * division) ou un import (voir ServiceImport.ImportResult#importedIds) :
     * supprime les transactions SANS empreinte de suppression. Ce n'est pas
     * un refus de ces operations : la ligne correspondante d'un releve doit
     * rester importable : ses lignes sont retirees du registre des lignes
     * importees (voir LigneImportee). Les tiers crees avec elles et devenus orphelins
     * partent aussi ; les categories creees par un import restent.
     */
    @Transactional
    public int undoCreation(List<Long> ids) {
        int deleted = deleteTransactions(ids, false);
        importedLineRepository.deleteByTransactionIds(ids);
        log.info("Creation annulee : {} transaction(s) supprimee(s)", deleted);
        return deleted;
    }

    /**
     * Suppression en masse factorisee entre deleteBulk, deleteAllPending et undoCreation
     * (voir leur javadoc pour la difference de comportement sur recordTombstone).
     * Comme delete(Long) : la transaction jumelle d'un virement selectionne
     * est toujours emportee avec lui, meme si elle n'a pas ete selectionnee
     * elle-meme, pour ne jamais laisser un virement partiellement supprime
     * (les deux se referencent mutuellement, voir createTransfer).
     */
    private int deleteTransactions(List<Long> ids, boolean recordTombstone) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction sélectionnée.");
        }

        Map<Long, Transaction> byId = new LinkedHashMap<>();
        for (Transaction t : transactionRepository.findAllByIdWithTransferTransaction(ids)) {
            byId.put(t.getId(), t);
        }
        List<Transaction> transactions = unlinkTransferPairs(byId);

        List<Tiers> orphanCandidates = new ArrayList<>();
        for (Transaction t : transactions) {
            if (recordTombstone) recordDeletion(t);
            if (t.getPayee() != null) orphanCandidates.add(t.getPayee());
        }
        transactionRepository.deleteAll(transactions);

        elagageTiers.apresCommit(orphanCandidates);
        log.info("Suppression en masse de {} transaction(s) (empreinte de suppression : {})", transactions.size(), recordTombstone);

        return transactions.size();
    }

    /**
     * Enregistre une empreinte de la transaction avant sa suppression (voir
     * TransactionSupprimee), pour que ServiceImport#processOperation la reconnaisse
     * et evite de la reimporter si le releve bancaire source la contient
     * encore lors d'un futur import — sans quoi une transaction supprimee
     * manuellement (ex : paire "depense remboursee", voir
     * ServiceDoublon#findReimbursementGroups) reapparaissait au prochain import.
     */
    private void recordDeletion(Transaction t) {
        TransactionSupprimee tombstone = new TransactionSupprimee();
        tombstone.setAccountId(t.getAccount().getId());
        tombstone.setDate(t.getDate());
        tombstone.setAmount(t.importKeyAmount());
        tombstone.setPayeeName(t.getPayee() != null ? t.getPayee().getName() : null);
        tombstone.setBankLabel(t.getBankLabel());
        deletedTransactionRepository.save(tombstone);
    }
}

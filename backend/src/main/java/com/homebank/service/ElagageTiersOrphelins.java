package com.homebank.service;

import com.homebank.model.Tiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Supprime les tiers devenus orphelins (plus aucune transaction) apres une
 * modification ou une suppression de transactions : voir ServiceTransaction
 * et ServiceSuppressionTransaction.
 */
@Component
public class ElagageTiersOrphelins {

    private static final Logger log = LoggerFactory.getLogger(ElagageTiersOrphelins.class);

    private final ServiceTiers payeeService;

    public ElagageTiersOrphelins(ServiceTiers payeeService) {
        this.payeeService = payeeService;
    }

    /**
     * Nettoyage "best effort" des anciens tiers devenus orphelins (voir
     * ServiceTiers#deleteIfOrphan), reporte APRES le commit de la transaction
     * en cours. L'executer dedans etait trompeur : une exception, meme
     * rattrapee, marquait toute la transaction rollback-only (la suppression
     * ou modification echouait alors au commit avec une 500), et le DELETE du
     * tiers n'etait de toute facon envoye en base qu'au commit, hors du
     * try/catch. Sans transaction active, le nettoyage est fait tout de suite.
     */
    public void apresCommit(Collection<Tiers> previousPayees) {
        List<Long> payeeIds = previousPayees.stream()
                .filter(Objects::nonNull)
                .map(Tiers::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (payeeIds.isEmpty()) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            prunePayees(payeeIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                prunePayees(payeeIds);
            }
        });
    }

    /**
     * Tous les tiers d'un coup ; si cela echoue, un a la fois, chacun dans sa
     * propre transaction : l'echec de l'un n'empeche pas le nettoyage des autres.
     */
    private void prunePayees(List<Long> payeeIds) {
        if (payeeIds.size() > 1 && prunePayeesTogether(payeeIds)) return;
        for (Long payeeId : payeeIds) {
            try {
                payeeService.deleteIfOrphan(payeeId);
            } catch (RuntimeException e) {
                log.warn("Echec du nettoyage du tiers {} devenu orphelin : {}", payeeId, e.getMessage(), e);
            }
        }
    }

    private boolean prunePayeesTogether(List<Long> payeeIds) {
        try {
            payeeService.deleteOrphans(payeeIds);
            return true;
        } catch (RuntimeException e) {
            log.warn("Nettoyage groupe de {} tiers en echec, reprise un par un : {}", payeeIds.size(), e.getMessage());
            return false;
        }
    }
}

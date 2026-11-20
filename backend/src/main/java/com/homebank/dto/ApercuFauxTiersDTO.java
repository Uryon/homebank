package com.homebank.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tiers ne de la copie d'un libelle calcule (« Virement vers X », « Ajustement
 * de solde »), tel que le nettoyage le traiterait (voir ServiceNettoyageTiers).
 */
public record ApercuFauxTiersDTO(
        Long payeeId,
        String name,
        int transactionsToDetach,
        long otherTransactions,
        boolean willBeDeleted,
        List<Exemple> examples) {

    /** Transaction concernee, avec le libelle qu'elle affichera apres le nettoyage. */
    public record Exemple(LocalDate date, BigDecimal amount, String newLabel) {
    }
}

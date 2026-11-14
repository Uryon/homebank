package com.homebank.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Comparaison du solde d'un compte avec celui d'un releve bancaire a une date
 * donnee (voir ServiceRapprochement) : un ecart non nul signale une operation
 * manquante, en trop ou mal saisie. Les operations encore en attente jusqu'a
 * cette date sont resumees pour aider a localiser l'ecart.
 */
public record RapprochementDTO(
        Long accountId,
        LocalDate date,
        BigDecimal statementBalance,
        BigDecimal bookBalance,
        BigDecimal difference,
        int pendingCount,
        BigDecimal pendingTotal) {
}

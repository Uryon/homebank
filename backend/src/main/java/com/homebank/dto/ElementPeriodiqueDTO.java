package com.homebank.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Derniere occurrence connue d'une transaction periodique pour un tiers
 * (voir ServiceRapport#periodicExpensesSummary). amount est en valeur absolue.
 */
public record ElementPeriodiqueDTO(Long transactionId, String payee, LocalDate date, BigDecimal amount) {
}

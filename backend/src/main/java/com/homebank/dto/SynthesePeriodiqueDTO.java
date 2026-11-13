package com.homebank.dto;

/**
 * Depenses et revenus periodiques, gardes separes (voir
 * ServiceRapport#periodicExpensesSummary) : la case "Periodique" ne
 * distingue pas les deux sur une transaction.
 */
public record SynthesePeriodiqueDTO(GroupePeriodiqueDTO expenses, GroupePeriodiqueDTO revenues) {
}

package com.homebank.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Groupe de transactions (meme tiers, montant proche) suggere comme
 * periodique par TransactionService#findPeriodicSuggestions, pour l'onglet
 * "Périodiques" de la page Transactions.
 */
@Getter
@Setter
@Builder
public class PeriodicSuggestionDTO {

    private String payee;
    private int count;
    private BigDecimal averageAmount;
    private int averageIntervalDays;
    private List<TransactionDTO> transactions;
}

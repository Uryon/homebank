package com.homebank.dto;

import java.math.BigDecimal;

/**
 * Solde de fin de mois (voir ServiceRapport#balanceForecast). type vaut
 * "historique" (solde reel) ou "previsionnel" (projete).
 */
public record PointSoldeDTO(String date, String label, BigDecimal balance, String type) {

    public static final String HISTORIQUE = "historique";
    public static final String PREVISIONNEL = "previsionnel";
}

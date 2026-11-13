package com.homebank.dto;

import java.math.BigDecimal;

/**
 * Epargne (revenus - depenses) d'un mois (voir ServiceRapport#monthlySavings).
 * date = premier jour du mois (ISO) ; forecast = mois pas encore termine,
 * rempli avec la moyenne previsionnelle.
 */
public record EpargneMensuelleDTO(String date, String label, BigDecimal savings, boolean forecast) {
}

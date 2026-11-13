package com.homebank.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Courbe de solde (voir ServiceRapport#balanceForecast) : les points
 * mensuels et la variation mensuelle moyenne appliquee a la partie
 * previsionnelle.
 */
public record PrevisionSoldeDTO(List<PointSoldeDTO> points, BigDecimal monthlyTrend) {
}

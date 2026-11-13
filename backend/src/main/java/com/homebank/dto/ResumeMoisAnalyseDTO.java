package com.homebank.dto;

import java.math.BigDecimal;

/** Revenus, depenses et net d'un mois du tableau d'analyse (voir ServiceRapport#monthlyCategoryAnalysis). */
public record ResumeMoisAnalyseDTO(String key, BigDecimal income, BigDecimal expenses, BigDecimal net, boolean forecast) {
}

package com.homebank.dto;

import java.math.BigDecimal;

/** Revenus, depenses (en valeur absolue) et net sur une periode (voir ServiceRapport#summary). */
public record ResumePeriodeDTO(BigDecimal income, BigDecimal expenses, BigDecimal net) {
}

package com.homebank.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Builder
public class PretDTO {

    private Long id;
    private String name;
    private BigDecimal principal;
    private BigDecimal annualRate;
    private Integer termMonths;
    private LocalDate startDate;
    private BigDecimal monthlyPayment;
    private BigDecimal monthlyInsurance;
    private String notes;

    // --- Indicateurs calcules, pratiques pour l'affichage en liste sans
    // devoir recalculer tout l'echeancier cote front ---
    private BigDecimal currentRemainingBalance;
    private LocalDate payoffDate;
    private BigDecimal totalInterest;
}

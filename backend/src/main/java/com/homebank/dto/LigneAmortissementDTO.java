package com.homebank.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Builder
public class LigneAmortissementDTO {

    private int month;
    private LocalDate date;
    private BigDecimal payment;
    private BigDecimal interest;
    private BigDecimal principal;
    private BigDecimal remainingBalance;
    /** true si l'echeance est deja passee (ou en cours) par rapport a aujourd'hui. */
    private boolean paid;
}

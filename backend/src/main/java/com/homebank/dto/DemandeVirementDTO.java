package com.homebank.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class DemandeVirementDTO {
    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private LocalDate date;
    private String memo;
    /**
     * Statut des deux cotes ; absent = valide (virement saisi a la main).
     * Renseigne a false par l'annulation de la suppression d'un virement en
     * attente, pour le recreer tel qu'il etait.
     */
    private Boolean validated;
}

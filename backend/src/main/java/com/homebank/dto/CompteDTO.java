package com.homebank.dto;

import com.homebank.utils.TypeCompte;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class CompteDTO {

    private Long id;
    private String name;
    private TypeCompte type;
    private BigDecimal initialBalance;
    private String currency;
    private String notes;
    private boolean archived;
}
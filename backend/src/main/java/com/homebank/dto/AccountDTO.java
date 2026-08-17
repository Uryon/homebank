package com.homebank.dto;

import com.homebank.utils.AccountType;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class AccountDTO {

    private Long id;
    private String name;
    private AccountType type;
    private BigDecimal initialBalance;
    private String currency;
    private String notes;
    private boolean archived;
    private String externalRef;
}
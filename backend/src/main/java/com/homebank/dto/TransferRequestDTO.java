package com.homebank.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class TransferRequestDTO {
    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private LocalDate date;
    private String memo;
}

package com.homebank.dto;


import com.homebank.utils.PaymentMode;
import com.homebank.utils.TransactionStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Builder
public class TransactionDTO {

    private Long id;
    private Long accountId;
    private LocalDate date;
    private BigDecimal amount;
    private String payee;
    private String memo;
    private Long categoryId;
    private PaymentMode paymentMode;
    private TransactionStatus status;
    private String tags;
    private Long transferTransactionId;
}
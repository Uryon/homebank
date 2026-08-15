package com.homebank.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
public class PayeeDTO {

    private Long id;
    private String name;
    private String pattern;
    private BigDecimal amount;
    private Long categoryId;
}

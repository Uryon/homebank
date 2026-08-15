package com.homebank.mapper;

import com.homebank.dto.PayeeDTO;
import com.homebank.model.Payee;
import org.springframework.stereotype.Component;

@Component
public class PayeeMapper {

    public PayeeDTO toDTO(Payee payee) {
        return PayeeDTO.builder()
                .id(payee.getId())
                .name(payee.getName())
                .pattern(payee.getPattern())
                .amount(payee.getAmount())
                .categoryId(payee.getCategory() != null
                        ? payee.getCategory().getId()
                        : null)
                .build();
    }
}

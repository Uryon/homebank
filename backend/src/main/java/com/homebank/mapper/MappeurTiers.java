package com.homebank.mapper;

import com.homebank.dto.TiersDTO;
import com.homebank.model.Tiers;
import org.springframework.stereotype.Component;

@Component
public class MappeurTiers {

    public TiersDTO toDTO(Tiers payee) {
        return TiersDTO.builder()
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

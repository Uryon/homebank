package com.homebank.mapper;

import com.homebank.dto.CompteDTO;
import com.homebank.model.Compte;
import org.springframework.stereotype.Component;

@Component
public class MappeurCompte {

    public CompteDTO toDTO(Compte account) {
        return CompteDTO.builder()
                .id(account.getId())
                .name(account.getName())
                .type(account.getType())
                .initialBalance(account.getInitialBalance())
                .currency(account.getCurrency())
                .notes(account.getNotes())
                .archived(account.isArchived())
                .build();
    }
}
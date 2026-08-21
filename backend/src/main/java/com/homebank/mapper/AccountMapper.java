package com.homebank.mapper;

import com.homebank.dto.AccountDTO;
import com.homebank.model.Account;
import org.springframework.stereotype.Component;

@Component
public class AccountMapper {

    public AccountDTO toDTO(Account account) {
        return AccountDTO.builder()
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
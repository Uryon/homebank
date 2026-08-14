package com.homebank.mapper;

import com.homebank.dto.TransactionDTO;
import com.homebank.model.Transaction;
import org.springframework.stereotype.Component;

@Component
public class TransactionMapper {

    public TransactionDTO toDTO(Transaction transaction) {
        return TransactionDTO.builder()
                .id(transaction.getId())
                .accountId(transaction.getAccount() != null
                        ? transaction.getAccount().getId()
                        : null)
                .date(transaction.getDate())
                .amount(transaction.getAmount())
                .payee(transaction.getPayee())
                .memo(transaction.getMemo())
                .categoryId(transaction.getCategory() != null
                        ? transaction.getCategory().getId()
                        : null)
                .paymentMode(transaction.getPaymentMode())
                .status(transaction.getStatus())
                .tags(transaction.getTags())
                .transferTransactionId(transaction.getTransferTransaction() != null
                        ? transaction.getTransferTransaction().getId()
                        : null)
                .build();
    }
}
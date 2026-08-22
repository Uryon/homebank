package com.homebank.mapper;

import com.homebank.dto.TransactionDTO;
import com.homebank.model.Transaction;
import com.homebank.utils.ModePaiement;
import org.springframework.stereotype.Component;

@Component
public class MappeurTransaction {

    public TransactionDTO toDTO(Transaction transaction) {
        return TransactionDTO.builder()
                .id(transaction.getId())
                .accountId(transaction.getAccount() != null
                        ? transaction.getAccount().getId()
                        : null)
                .date(transaction.getDate())
                .amount(transaction.getAmount())
                .payee(payeeLabel(transaction))
                .payeeId(transaction.getPayee() != null ? transaction.getPayee().getId() : null)
                .memo(transaction.getMemo())
                .categoryId(transaction.getCategory() != null
                        ? transaction.getCategory().getId()
                        : null)
                .paymentMode(transaction.getPaymentMode())
                .tags(transaction.getTags())
                .transferTransactionId(transaction.getTransferTransaction() != null
                        ? transaction.getTransferTransaction().getId()
                        : null)
                .validated(transaction.isValidated())
                .periodicExpense(transaction.isPeriodicExpense())
                .externalId(transaction.getExternalId())
                .build();
    }

    /**
     * Libelle affiche pour le "tiers" : le nom du Tiers lie, lu en direct
     * (jamais recopie), ou pour un virement interne (jamais lie a un vrai
     * tiers) un libelle recalcule a partir du compte de destination/origine,
     * pour qu'il reste a jour meme si ce compte est renomme plus tard.
     */
    private String payeeLabel(Transaction transaction) {
        if (transaction.getPayee() != null) {
            return transaction.getPayee().getName();
        }
        if (transaction.getPaymentMode() == ModePaiement.TRANSFER && transaction.getTransferTransaction() != null) {
            String counterpartAccount = transaction.getTransferTransaction().getAccount() != null
                    ? transaction.getTransferTransaction().getAccount().getName()
                    : "";
            String direction = transaction.getAmount() != null && transaction.getAmount().signum() < 0
                    ? "Virement vers "
                    : "Virement depuis ";
            return direction + counterpartAccount;
        }
        return null;
    }
}

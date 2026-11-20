package com.homebank.mapper;

import com.homebank.dto.TransactionDTO;
import com.homebank.model.Transaction;
import com.homebank.utils.ModePaiement;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class MappeurTransaction {

    /** Libelle affiche a la place du tiers pour un ajustement de solde (voir Transaction#balanceAdjustment). */
    static final String BALANCE_ADJUSTMENT_LABEL = "Ajustement de solde";
    private static final String TRANSFER_TO_PREFIX = "Virement vers ";
    private static final String TRANSFER_FROM_PREFIX = "Virement depuis ";

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
                .transferTransactionId(transaction.getTransferTransaction() != null
                        ? transaction.getTransferTransaction().getId()
                        : null)
                .validated(transaction.isValidated())
                .periodicExpense(transaction.isPeriodicExpense())
                .exceptional(transaction.isExceptional())
                .bankLabel(transaction.getBankLabel())
                .duplicateIgnored(transaction.isDuplicateIgnored())
                .balanceAdjustment(transaction.isBalanceAdjustment())
                .splitFromAmount(transaction.getSplitFromAmount())
                .periodicSuggestionIgnored(transaction.isPeriodicSuggestionIgnored())
                .build();
    }

    /**
     * Libelle affiche pour le "tiers" : le nom du Tiers lie, lu en direct
     * (jamais recopie), ou pour un virement interne (jamais lie a un vrai
     * tiers) un libelle recalcule a partir du compte de destination/origine,
     * pour qu'il reste a jour meme si ce compte est renomme plus tard.
     */
    public static String payeeLabel(Transaction transaction) {
        return transaction.getPayee() != null
                ? transaction.getPayee().getName()
                : computedLabel(transaction);
    }

    /** Vrai si la transaction affiche un libelle calcule quand elle n'a pas de tiers. */
    public static boolean hasComputedLabel(Transaction transaction) {
        return isLinkedTransfer(transaction) || transaction.isBalanceAdjustment();
    }

    /** Vrai si ce nom a la forme d'un libelle calcule (casse ignoree, le compte a pu etre renomme depuis). */
    public static boolean looksLikeComputedLabel(String name) {
        if (name == null) return false;
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return lower.equals(BALANCE_ADJUSTMENT_LABEL.toLowerCase(Locale.ROOT))
                || lower.startsWith(TRANSFER_TO_PREFIX.toLowerCase(Locale.ROOT))
                || lower.startsWith(TRANSFER_FROM_PREFIX.toLowerCase(Locale.ROOT));
    }

    /** Libelle affiche en l'absence de tiers, null si la transaction n'en a pas. */
    public static String computedLabel(Transaction transaction) {
        if (isLinkedTransfer(transaction)) {
            String counterpartAccount = transaction.getTransferTransaction().getAccount() != null
                    ? transaction.getTransferTransaction().getAccount().getName()
                    : "";
            String direction = transaction.getAmount() != null && transaction.getAmount().signum() < 0
                    ? TRANSFER_TO_PREFIX
                    : TRANSFER_FROM_PREFIX;
            return direction + counterpartAccount;
        }
        return transaction.isBalanceAdjustment() ? BALANCE_ADJUSTMENT_LABEL : null;
    }

    private static boolean isLinkedTransfer(Transaction transaction) {
        return transaction.getPaymentMode() == ModePaiement.TRANSFER && transaction.getTransferTransaction() != null;
    }
}

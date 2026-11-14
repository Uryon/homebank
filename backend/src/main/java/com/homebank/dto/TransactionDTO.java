package com.homebank.dto;


import com.homebank.utils.ModePaiement;
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
    /** Id du tiers reellement lie (voir Transaction#payee), pour reference ; le champ payee ci-dessus reste le nom affichable. */
    private Long payeeId;
    private String memo;
    private Long categoryId;
    private ModePaiement paymentMode;
    private Long transferTransactionId;
    private boolean validated;
    private boolean periodicExpense;
    private boolean exceptional;
    /** Libelle bancaire d'origine (voir Transaction#bankLabel) : pris en compte a la creation seulement (restauration apres annulation). */
    private String bankLabel;
    private boolean duplicateIgnored;
    /** Ajustement de solde (voir Transaction#balanceAdjustment) : pris en compte a la creation seulement, jamais modifie par une mise a jour. */
    private boolean balanceAdjustment;
}

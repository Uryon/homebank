package com.homebank.dto;


import com.fasterxml.jackson.annotation.JsonInclude;
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
    /** Libelle bancaire d'origine (voir Transaction#bankLabel) : repris uniquement par une restauration (voir ServiceRestaurationTransaction). */
    private String bankLabel;
    private boolean duplicateIgnored;
    /** Ajustement de solde (voir Transaction#balanceAdjustment) : repris uniquement par une restauration. */
    private boolean balanceAdjustment;
    /** Montant d'avant division (voir Transaction#splitFromAmount), omis s'il est absent : la liste complete reste legere. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal splitFromAmount;
    /** Suggestion periodique refusee (voir Transaction#periodicSuggestionIgnored), omis si faux, comme ci-dessus. */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean periodicSuggestionIgnored;
}

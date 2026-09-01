package com.homebank.dto;

import com.homebank.utils.ModePaiement;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Payload de mise a jour en masse (edition multi-selection sur la page
 * Transactions) : seuls les champs dont le drapeau "...Provided" est a true
 * sont appliques a chaque transaction de la liste "ids", les autres restent
 * inchanges. Necessaire car une valeur null (ex : categoryId) est une valeur
 * metier valide ("aucune categorie") et doit donc etre distinguee de
 * "champ non fourni".
 */
@Getter
@Setter
public class TransactionBulkUpdateDTO {

    private List<Long> ids;

    private boolean categoryIdProvided;
    private Long categoryId;

    private boolean payeeProvided;
    private String payee;

    private boolean paymentModeProvided;
    private ModePaiement paymentMode;

    private boolean periodicExpenseProvided;
    private boolean periodicExpense;

    private boolean exceptionalProvided;
    private boolean exceptional;

    private boolean validatedProvided;
    private boolean validated;
}

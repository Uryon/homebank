package com.homebank.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Requete de division d'une transaction en deux (voir
 * ServiceTransaction#splitTransaction) : la transaction d'origine devient la
 * "1ere partie" (montant/categorie/memo remplaces sur place), et une
 * nouvelle transaction est creee pour la "2e partie", dont le montant est
 * calcule cote serveur (montant d'origine - firstAmount) pour garantir que
 * la somme des deux parties egale toujours le montant d'origine — le solde
 * du compte n'est jamais affecte par une division.
 */
@Getter
@Setter
public class DemandeDivisionDTO {
    /** Montant de la 1ere partie, meme signe que la transaction d'origine. */
    private BigDecimal firstAmount;
    private Long firstCategoryId;
    private String firstMemo;
    private Long secondCategoryId;
    private String secondMemo;
}

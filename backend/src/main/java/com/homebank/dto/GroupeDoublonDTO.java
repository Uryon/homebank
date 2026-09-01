package com.homebank.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Groupe de transactions deja en base qui semblent doublonnees (import en
 * double), detecte par ServiceTransaction#findDuplicateGroups, pour l'onglet
 * "Doublons" de la page Transactions.
 */
@Getter
@Setter
@Builder
public class GroupeDoublonDTO {

    private int count;
    private List<TransactionDTO> transactions;
    /**
     * true : meme compte + date + montant + tiers (ou meme identifiant
     * externe fourni par la banque) — les occurrences sont interchangeables,
     * n'importe laquelle peut etre supprimee.
     * false : meme compte + date + montant, mais tiers different (ex : un
     * import brut "ACHAT CB..." jamais nettoye a cote d'une transaction deja
     * categorisee/renommee pour le meme paiement) — probable doublon, mais
     * les occurrences ne sont pas interchangeables, l'utilisateur doit
     * choisir laquelle garder.
     * Toujours false quand reimbursement est true (voir ci-dessous).
     */
    private boolean certain;

    /**
     * true : pas un doublon d'import, mais un groupe "remboursement" —
     * meme tiers, montants opposes de meme valeur absolue (ex : achat de 19€
     * suivi d'un remboursement de 19€ par ce meme tiers). Detecte separement
     * de certain/probable ci-dessus, qui exigent un montant identique (signe
     * compris) et ne peuvent donc jamais matcher ce cas. Aucune suppression
     * proposee ici (ce sont de vraies transactions, pas un import en double) :
     * seulement un reperage, que l'utilisateur peut ignorer une fois verifie.
     */
    private boolean reimbursement;
}

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
}

package com.homebank.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Resultat de la categorisation automatique en masse des tiers (voir
 * ServiceTiers#autoCategorize) : nombre de tiers auxquels une categorie
 * deduite a ete affectee, et nombre de transactions (jusque-la sans
 * categorie) qui ont herite de la categorie de leur tiers a cette occasion.
 */
@Getter
@AllArgsConstructor
public class CategorisationAutoResultatDTO {
    private int payeesUpdated;
    private int transactionsUpdated;
}

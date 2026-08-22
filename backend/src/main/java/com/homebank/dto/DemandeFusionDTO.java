package com.homebank.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DemandeFusionDTO {

    /** Identifiants des lignes a fusionner et supprimer (peut inclure ou non targetId, ignore si present) */
    private List<Long> sourceIds;

    /** Identifiant de la ligne qui recevra toutes les operations des sources et qui sera conservee */
    private Long targetId;

    /**
     * Motif de reconnaissance final a appliquer au tiers cible (uniquement
     * pertinent pour la fusion de tiers, ignore pour les categories).
     * Optionnel : si absent ou vide, le motif du tiers cible n'est pas
     * modifie (les motifs des tiers sources ne sont plus concatenes
     * automatiquement, voir ServiceTiers#merge).
     */
    private String pattern;
}

package com.homebank.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Ligne du tableau d'analyse (voir ServiceAnalyseMensuelle#monthlyCategoryAnalysis) :
 * une categorie/sous-categorie pour un type de flux ("income"/"expense"), ou
 * une ligne de total (categoryId/parentId/parentName null, level 0).
 * values : montant (positif) par cle de mois (voir MoisAnalyseDTO#key).
 * previousTotal : total de la meme ligne sur les 12 mois precedents (voir
 * AnalyseMensuelleDTO#previousFrom), 0 si elle n'y avait aucune activite.
 */
public record LigneAnalyseDTO(
        String key,
        Long categoryId,
        String name,
        Long parentId,
        String parentName,
        int level,
        String type,
        Map<String, BigDecimal> values,
        BigDecimal average,
        BigDecimal total,
        BigDecimal previousTotal) {
}

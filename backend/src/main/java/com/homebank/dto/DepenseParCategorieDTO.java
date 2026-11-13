package com.homebank.dto;

import java.math.BigDecimal;

/**
 * Une ligne de la repartition des depenses par categorie (voir
 * ServiceRapport#expensesByCategory). categoryId est null pour la ligne
 * "Non categorise" (uncategorized = true).
 */
public record DepenseParCategorieDTO(
        String category,
        Long categoryId,
        boolean uncategorized,
        BigDecimal total,
        BigDecimal percentage) {
}

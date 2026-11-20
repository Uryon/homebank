package com.homebank.dto;

/**
 * Colonne mois du tableau d'analyse (voir ServiceAnalyseMensuelle#monthlyCategoryAnalysis).
 * key = premier jour du mois (ISO), cle des "values" de chaque ligne ;
 * forecast = mois pas encore termine (valeurs previsionnelles).
 */
public record MoisAnalyseDTO(String key, String label, int year, int month, boolean forecast) {
}

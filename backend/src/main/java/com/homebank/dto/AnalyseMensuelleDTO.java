package com.homebank.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Analyse des revenus/depenses par categorie et par mois sur [from, to]
 * (voir ServiceRapport#monthlyCategoryAnalysis).
 */
public record AnalyseMensuelleDTO(
        LocalDate from,
        LocalDate to,
        List<MoisAnalyseDTO> months,
        List<LigneAnalyseDTO> income,
        LigneAnalyseDTO incomeTotal,
        List<LigneAnalyseDTO> expenses,
        LigneAnalyseDTO expenseTotal,
        List<ResumeMoisAnalyseDTO> summary) {
}

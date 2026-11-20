package com.homebank.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Analyse des revenus/depenses par categorie et par mois sur [from, to]
 * (voir ServiceAnalyseMensuelle#monthlyCategoryAnalysis).
 * <p>
 * Comparaison avec l'annee precedente : [previousFrom, previousTo] est la
 * meme fenetre de 12 mois decalee d'un an, calculee avec les memes regles
 * (voir LigneAnalyseDTO#previousTotal) ; previousSummary en donne le resume
 * mois par mois. historyStart est la date de la plus ancienne transaction
 * (null s'il n'y en a aucune) : posterieure a previousFrom, la fenetre
 * precedente n'est que partiellement couverte ; posterieure a previousTo,
 * il n'y a rien a comparer.
 */
public record AnalyseMensuelleDTO(
        LocalDate from,
        LocalDate to,
        List<MoisAnalyseDTO> months,
        List<LigneAnalyseDTO> income,
        LigneAnalyseDTO incomeTotal,
        List<LigneAnalyseDTO> expenses,
        LigneAnalyseDTO expenseTotal,
        List<ResumeMoisAnalyseDTO> summary,
        LocalDate previousFrom,
        LocalDate previousTo,
        LocalDate historyStart,
        List<ResumeMoisAnalyseDTO> previousSummary) {
}

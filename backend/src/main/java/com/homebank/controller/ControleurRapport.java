package com.homebank.controller;

import com.homebank.dto.AnalyseMensuelleDTO;
import com.homebank.dto.DepenseParCategorieDTO;
import com.homebank.dto.EpargneMensuelleDTO;
import com.homebank.dto.PrevisionSoldeDTO;
import com.homebank.dto.SynthesePeriodiqueDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.service.ServiceRapport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ControleurRapport {

    private final ServiceRapport reportService;

    public ControleurRapport(ServiceRapport reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/expenses-by-category")
    public List<DepenseParCategorieDTO> expensesByCategory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId) {
        return reportService.expensesByCategory(from, to, accountId);
    }

    /**
     * Analyse détaillée revenus/dépenses par catégorie sur les 12 mois terminés
     * précédant referenceMonth (dernier mois inclus). referenceMonth omis =
     * dernier mois calendaire déjà terminé (comportement historique).
     */
    @GetMapping("/monthly-category-analysis")
    public AnalyseMensuelleDTO monthlyCategoryAnalysis(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceMonth) {
        return reportService.monthlyCategoryAnalysis(accountId, referenceMonth);
    }

    /** Epargne mensuelle (revenus - depenses) sur les N derniers mois termines, jusqu'a referenceMonth inclus (omis = dernier mois calendaire deja termine). */
    @GetMapping("/monthly-savings")
    public List<EpargneMensuelleDTO> monthlySavings(
            @RequestParam(required = false, defaultValue = "12") int months,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceMonth) {
        return reportService.monthlySavings(months, accountId, referenceMonth);
    }

    /**
     * Solde : 12 mois historiques + present + 12 mois prevus, ancres sur
     * referenceMonth (omis = dernier mois calendaire deja termine). Renvoie
     * "points" (la serie) et "monthlyTrend" (variation mensuelle moyenne
     * appliquee a la partie previsionnelle, voir ServiceRapport#balanceForecast).
     */
    @GetMapping("/balance-forecast")
    public PrevisionSoldeDTO balanceForecast(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate referenceMonth) {
        return reportService.balanceForecast(accountId, referenceMonth);
    }

    /** Total des transactions marquees "depense periodique". */
    @GetMapping("/periodic-expenses")
    public SynthesePeriodiqueDTO periodicExpenses() {
        return reportService.periodicExpensesSummary();
    }

    /**
     * Detail des transactions individuelles derriere une cellule d'un tableau
     * du tableau de bord (survol), calcule avec exactement les memes regles
     * que les agregats correspondants (voir ServiceRapport#cellTransactions).
     */
    @GetMapping("/cell-transactions")
    public List<TransactionDTO> cellTransactions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String flow,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long rootCategoryId,
            @RequestParam(required = false, defaultValue = "false") boolean uncategorized) {
        return reportService.cellTransactions(from, to, accountId, flow, categoryId, rootCategoryId, uncategorized);
    }
}

package com.homebank.controller;

import com.homebank.service.ServiceRapport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ControleurRapport {

    private final ServiceRapport reportService;

    public ControleurRapport(ServiceRapport reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/expenses-by-category")
    public List<Map<String, Object>> expensesByCategory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId) {
        return reportService.expensesByCategory(from, to, accountId);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId) {
        return reportService.summary(from, to, accountId);
    }

    /** Analyse détaillée revenus/dépenses par catégorie sur les 12 derniers mois terminés. */
    @GetMapping("/monthly-category-analysis")
    public Map<String, Object> monthlyCategoryAnalysis(
            @RequestParam(required = false) Long accountId) {
        return reportService.monthlyCategoryAnalysis(accountId);
    }

    /** Epargne mensuelle (revenus - depenses) sur les N derniers mois termines. */
    @GetMapping("/monthly-savings")
    public List<Map<String, Object>> monthlySavings(
            @RequestParam(required = false, defaultValue = "12") int months,
            @RequestParam(required = false) Long accountId) {
        return reportService.monthlySavings(months, accountId);
    }

    /** Solde : 12 mois historiques + present + 12 mois prevus. */
    @GetMapping("/balance-forecast")
    public List<Map<String, Object>> balanceForecast(@RequestParam(required = false) Long accountId) {
        return reportService.balanceForecast(accountId);
    }

    /** Total des transactions marquees "depense periodique". */
    @GetMapping("/periodic-expenses")
    public Map<String, Object> periodicExpenses() {
        return reportService.periodicExpensesSummary();
    }
}

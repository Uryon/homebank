package com.homebank.service;

import com.homebank.model.Account;
import com.homebank.model.Category;
import com.homebank.model.Transaction;
import com.homebank.repository.AccountRepository;
import com.homebank.repository.TransactionRepository;
import com.homebank.utils.PaymentMode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public ReportService(TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    private List<Transaction> loadTransactions(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);
        LocalDate today = LocalDate.now();
        return transactions.stream()
                // Les virements entre comptes ne sont ni une depense ni un revenu reel.
                .filter(t -> t.getPaymentMode() != PaymentMode.TRANSFER)
                // Une transaction datee dans le futur est seulement prevue : elle n'a pas
                // encore reellement eu lieu et ne doit donc pas fausser les statistiques
                // (voir l'affichage attenue de ces transactions dans l'onglet Transactions).
                .filter(t -> !t.getDate().isAfter(today))
                .toList();
    }

    /**
     * Repartition des depenses (montants negatifs) par categorie racine,
     * sur une periode donnee. Renvoie une liste triee par montant decroissant.
     * Les virements entre comptes sont exclus.
     */
    public List<Map<String, Object>> expensesByCategory(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = loadTransactions(from, to, accountId);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            if (t.getAmount().signum() >= 0) continue; // on ne garde que les depenses
            String label = categoryLabel(t.getCategory());
            totals.merge(label, t.getAmount().abs(), BigDecimal::add);
        }

        BigDecimal grandTotal = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return totals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", e.getKey());
                    row.put("total", e.getValue());
                    row.put("percentage", grandTotal.signum() == 0
                            ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                                    .divide(grandTotal, 1, RoundingMode.HALF_UP));
                    return row;
                })
                .collect(Collectors.toList());
    }

    /** Resume global : revenus, depenses et net sur la periode. Les virements sont exclus. */
    public Map<String, Object> summary(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = loadTransactions(from, to, accountId);

        BigDecimal income = transactions.stream()
                .map(Transaction::getAmount)
                .filter(a -> a.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expenses = transactions.stream()
                .map(Transaction::getAmount)
                .filter(a -> a.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("income", income);
        result.put("expenses", expenses.abs());
        result.put("net", income.add(expenses));
        return result;
    }

    /**
     * Epargne mensuelle (revenus - depenses, virements exclus) sur les N
     * derniers mois entierement termines.
     */
    public List<Map<String, Object>> monthlySavings(int monthsBack, Long accountId) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate firstOfCurrentMonth = LocalDate.now().withDayOfMonth(1);

        for (int i = monthsBack; i >= 1; i--) {
            LocalDate monthStart = firstOfCurrentMonth.minusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            Map<String, Object> s = summary(monthStart, monthEnd, accountId);

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", monthStart.toString());
            point.put("label", monthLabel(monthStart));
            point.put("savings", s.get("net"));
            result.add(point);
        }
        return result;
    }

    /**
     * Solde mensuel sur 12 mois historiques (deja termines) + une projection
     * sur 12 mois a partir du mois en cours inclus ("previsionnel", fin de
     * mois projetee des le mois en cours), basee sur la variation mensuelle
     * moyenne observee sur les 12 derniers mois. Pas de point separe pour
     * "aujourd'hui" : le mois en cours n'etant pas termine, il releve deja
     * entierement du previsionnel (voir balanceAsOf pour le solde de
     * reference utilise comme point de depart de la projection). Chaque
     * point precise son "type" (historique | previsionnel) pour permettre au
     * frontend de les distinguer visuellement.
     */
    public List<Map<String, Object>> balanceForecast(Long accountId) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate firstOfCurrentMonth = today.withDayOfMonth(1);
        // Charge les comptes une seule fois pour tout le calcul (13 points),
        // plutot qu'a chaque appel de balanceAsOf (findAll()/findById() repete).
        List<Account> accounts = loadAccounts(accountId);

        List<BigDecimal> historicalBalances = new ArrayList<>();
        for (int i = 12; i >= 1; i--) {
            LocalDate monthStart = firstOfCurrentMonth.minusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            BigDecimal balance = balanceAsOf(monthEnd, accounts);
            historicalBalances.add(balance);
            result.add(point(monthEnd, balance, "historique"));
        }

        BigDecimal avgMonthlyNet = BigDecimal.ZERO;
        if (historicalBalances.size() >= 2) {
            BigDecimal totalChange = historicalBalances.get(historicalBalances.size() - 1)
                    .subtract(historicalBalances.get(0));
            avgMonthlyNet = totalChange.divide(
                    BigDecimal.valueOf(historicalBalances.size() - 1), 2, RoundingMode.HALF_UP);
        }

        // Meme regle pour tous les mois previsionnels, mois en cours inclus :
        // chaque point = le precedent + la variation mensuelle moyenne, en
        // partant du dernier solde reel connu (dernier point historique, fin
        // du mois precedent). Ne part jamais du solde partiel d'aujourd'hui :
        // sinon le mois en cours melangerait donnees reelles partielles et
        // moyenne, avec un point previsionnel incoherent par rapport aux
        // mois suivants (ex : en baisse alors que la tendance moyenne monte).
        BigDecimal projected = historicalBalances.get(historicalBalances.size() - 1);
        for (int i = 0; i < 12; i++) {
            projected = projected.add(avgMonthlyNet);
            LocalDate futureMonthEnd = firstOfCurrentMonth.plusMonths(i + 1).minusDays(1);
            result.add(point(futureMonthEnd, projected, "previsionnel"));
        }

        return result;
    }

    /** Somme des transactions marquees "depense periodique" (une ligne par tiers, la plus recente). */
    public Map<String, Object> periodicExpensesSummary() {
        LocalDate today = LocalDate.now();
        List<Transaction> flagged = transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc()
                .stream()
                // Une occurrence future n'a pas encore reellement eu lieu : on ne la
                // retient pas comme "derniere" occurrence connue de la depense.
                .filter(t -> !t.getDate().isAfter(today))
                .toList();

        Map<String, Transaction> latestByPayee = new LinkedHashMap<>();
        for (Transaction t : flagged) {
            String key = (t.getPayee() != null ? t.getPayee().getName().toLowerCase(Locale.ROOT) : "id-" + t.getId());
            latestByPayee.putIfAbsent(key, t);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : latestByPayee.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("payee", t.getPayee() != null ? t.getPayee().getName() : null);
            item.put("amount", t.getAmount().abs());
            items.add(item);
            total = total.add(t.getAmount().abs());
        }
        items.sort((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        return result;
    }

    private BigDecimal balanceAsOf(LocalDate date, List<Account> accounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (Account a : accounts) {
            total = total.add(a.getInitialBalance())
                    .add(transactionRepository.sumAmountByAccountIdAsOf(a.getId(), date));
        }
        return total;
    }

    private List<Account> loadAccounts(Long accountId) {
        return accountId != null
                ? List.of(accountRepository.findById(accountId).orElseThrow())
                : accountRepository.findAll();
    }

    private Map<String, Object> point(LocalDate date, BigDecimal balance, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("label", monthLabel(date));
        m.put("balance", balance);
        m.put("type", type);
        return m;
    }

    private String monthLabel(LocalDate date) {
        String month = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.FRENCH);
        return Character.toUpperCase(month.charAt(0)) + month.substring(1) + " " + date.getYear();
    }

    private String categoryLabel(Category category) {
        if (category == null) return "Non categorise";
        if (category.getParent() != null) {
            return category.getParent().getName() + " : " + category.getName();
        }
        return category.getName();
    }

    /**
     * Tableau d'analyse des revenus et dépenses par catégorie/sous-catégorie
     * sur les 12 derniers mois entièrement terminés.
     *
     * Les montants sont agrégés en base, puis structurés côté backend afin que
     * le frontend ne charge jamais l'ensemble des transactions.
     */
    public Map<String, Object> monthlyCategoryAnalysis(Long accountId) {
        LocalDate firstOfCurrentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate from = firstOfCurrentMonth.minusMonths(12);
        LocalDate to = firstOfCurrentMonth.minusDays(1);

        List<Map<String, Object>> months = new ArrayList<>();
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMM.", Locale.FRENCH);
        for (LocalDate month = from; month.isBefore(firstOfCurrentMonth); month = month.plusMonths(1)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", month.toString());
            m.put("label", capitalize(month.format(monthFormatter)));
            m.put("year", month.getYear());
            m.put("month", month.getMonthValue());
            months.add(m);
        }

        // Clé stable : catégorie (ou catégorie racine + sous-catégorie) + type de flux.
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        Map<String, BigDecimal> monthlyIncomeTotals = new LinkedHashMap<>();
        Map<String, BigDecimal> monthlyExpenseTotals = new LinkedHashMap<>();

        for (Map<String, Object> m : months) {
            monthlyIncomeTotals.put((String) m.get("key"), BigDecimal.ZERO);
            monthlyExpenseTotals.put((String) m.get("key"), BigDecimal.ZERO);
        }

        for (Object[] r : transactionRepository.aggregateMonthlyByCategory(from, to, accountId)) {
            Long categoryId = (Long) r[0];
            String categoryName = (String) r[1];
            Long parentId = (Long) r[2];
            String parentName = (String) r[3];
            BigDecimal amount = (BigDecimal) r[7];

            // Les transactions sans catégorie restent visibles dans le tableau.
            String safeName = categoryName != null ? categoryName : "Sans catégorie";
            String key = (categoryId != null ? categoryId.toString() : "uncategorized")
                    + "|" + (amount.signum() > 0 ? "income" : "expense");

            Map<String, Object> row = rows.get(key);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("key", key);
                row.put("categoryId", categoryId);
                row.put("name", safeName);
                row.put("parentId", parentId);
                row.put("parentName", parentName);
                row.put("level", parentId == null ? 0 : 1);
                row.put("type", amount.signum() > 0 ? "income" : "expense");
                Map<String, BigDecimal> values = new LinkedHashMap<>();
                for (Map<String, Object> m : months) {
                    values.put((String) m.get("key"), BigDecimal.ZERO);
                }
                row.put("values", values);
                rows.put(key, row);
            }

            String monthKey = LocalDate.of(((Number) r[5]).intValue(), ((Number) r[6]).intValue(), 1).toString();
            @SuppressWarnings("unchecked")
            Map<String, BigDecimal> values = (Map<String, BigDecimal>) row.get("values");
            values.put(monthKey, values.getOrDefault(monthKey, BigDecimal.ZERO)
                    .add(amount.abs()));

            if (amount.signum() > 0) {
                monthlyIncomeTotals.merge(monthKey, amount, BigDecimal::add);
            } else if (amount.signum() < 0) {
                monthlyExpenseTotals.merge(monthKey, amount.abs(), BigDecimal::add);
            }
        }

        // Construit la hiérarchie : racines d'abord, sous-catégories juste après.
        List<Map<String, Object>> incomeRows = rows.values().stream()
                .filter(r -> "income".equals(r.get("type")))
                .sorted(this::compareCategoryRows)
                .map(this::completeAnalysisRow)
                .toList();

        List<Map<String, Object>> expenseRows = rows.values().stream()
                .filter(r -> "expense".equals(r.get("type")))
                .sorted(this::compareCategoryRows)
                .map(this::completeAnalysisRow)
                .toList();

        Map<String, Object> incomeTotal = totalAnalysisRow("Total revenus", "income", months, monthlyIncomeTotals);
        Map<String, Object> expenseTotal = totalAnalysisRow("Total dépenses", "expense", months, monthlyExpenseTotals);

        List<Map<String, Object>> monthlySummary = new ArrayList<>();
        for (Map<String, Object> m : months) {
            String key = (String) m.get("key");
            BigDecimal income = monthlyIncomeTotals.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal expenses = monthlyExpenseTotals.getOrDefault(key, BigDecimal.ZERO);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("key", key);
            summary.put("income", income);
            summary.put("expenses", expenses);
            summary.put("net", income.subtract(expenses));
            monthlySummary.add(summary);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("months", months);
        result.put("income", incomeRows);
        result.put("incomeTotal", incomeTotal);
        result.put("expenses", expenseRows);
        result.put("expenseTotal", expenseTotal);
        result.put("summary", monthlySummary);
        return result;
    }

    private Map<String, Object> completeAnalysisRow(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> values = (Map<String, BigDecimal>) row.get("values");
        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = values.isEmpty()
                ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
        row.put("average", average);
        row.put("total", total);
        return row;
    }

    private Map<String, Object> totalAnalysisRow(
            String name,
            String type,
            List<Map<String, Object>> months,
            Map<String, BigDecimal> monthlyTotals) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", "total-" + type);
        row.put("name", name);
        row.put("level", 0);
        row.put("type", type);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (Map<String, Object> month : months) {
            String key = (String) month.get("key");
            values.put(key, monthlyTotals.getOrDefault(key, BigDecimal.ZERO));
        }
        row.put("values", values);
        return completeAnalysisRow(row);
    }

    private int compareCategoryRows(Map<String, Object> a, Map<String, Object> b) {
        String ap = Objects.toString(a.get("parentName"), "");
        String bp = Objects.toString(b.get("parentName"), "");
        int parent = ap.compareToIgnoreCase(bp);
        if (parent != 0) return parent;

        int level = Integer.compare(
                ((Number) a.get("level")).intValue(),
                ((Number) b.get("level")).intValue());
        if (level != 0) return level;

        return Objects.toString(a.get("name"), "").compareToIgnoreCase(
                Objects.toString(b.get("name"), ""));
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.FRENCH) + value.substring(1);
    }

}

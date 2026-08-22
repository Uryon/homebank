package com.homebank.service;

import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Categorie;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotCompte;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.ModePaiement;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ServiceRapport {

    private final DepotTransaction transactionRepository;
    private final DepotCompte accountRepository;
    private final MappeurTransaction transactionMapper;

    public ServiceRapport(DepotTransaction transactionRepository, DepotCompte accountRepository, MappeurTransaction transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.transactionMapper = transactionMapper;
    }

    private List<Transaction> loadTransactions(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);
        LocalDate today = LocalDate.now();
        return transactions.stream()
                // Les virements entre comptes ne sont ni une depense ni un revenu reel.
                .filter(t -> t.getPaymentMode() != ModePaiement.TRANSFER)
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
        // Categorie (id, ou absence = "non categorise") representative de chaque
        // libelle, memorisee au passage pour permettre au frontend de retrouver
        // ensuite les transactions exactes d'une ligne (voir cellTransactions).
        Map<String, Long> categoryIdByLabel = new LinkedHashMap<>();
        Set<String> uncategorizedLabels = new HashSet<>();
        for (Transaction t : transactions) {
            if (t.getAmount().signum() >= 0) continue; // on ne garde que les depenses
            String label = categoryLabel(t.getCategory());
            totals.merge(label, t.getAmount().abs(), BigDecimal::add);
            if (t.getCategory() != null) {
                categoryIdByLabel.putIfAbsent(label, t.getCategory().getId());
            } else {
                uncategorizedLabels.add(label);
            }
        }

        BigDecimal grandTotal = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return totals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", e.getKey());
                    row.put("categoryId", categoryIdByLabel.get(e.getKey()));
                    row.put("uncategorized", uncategorizedLabels.contains(e.getKey()));
                    row.put("total", e.getValue());
                    row.put("percentage", grandTotal.signum() == 0
                            ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                                    .divide(grandTotal, 1, RoundingMode.HALF_UP));
                    return row;
                })
                .collect(Collectors.toList());
    }

    /**
     * Transactions individuelles derriere une cellule d'un tableau du tableau
     * de bord, calculees avec exactement les memes regles que les agregats
     * (loadTransactions : virements exclus, transactions futures exclues) —
     * pour que le detail affiche au survol corresponde toujours au montant
     * affiche dans la cellule. Filtres tous optionnels et combinables :
     * - flow ("income"/"expense") : signe du montant ;
     * - categoryId : categorie exacte (sous-categorie, ou racine sans enfant) ;
     * - rootCategoryId : categorie racine ELLE-MEME ou une de ses
     *   sous-categories (regroupement utilise par les tableaux "par categorie") ;
     * - uncategorized : transactions sans categorie.
     * Sans aucun filtre categorie, toutes les categories sont incluses (ex :
     * ligne "Total" d'un tableau mensuel).
     */
    public List<TransactionDTO> cellTransactions(
            LocalDate from, LocalDate to, Long accountId,
            String flow, Long categoryId, Long rootCategoryId, boolean uncategorized) {

        List<Transaction> transactions = loadTransactions(from, to, accountId);

        return transactions.stream()
                .filter(t -> matchesFlow(t, flow))
                .filter(t -> matchesCategory(t, categoryId, rootCategoryId, uncategorized))
                .sorted(Comparator.comparing(Transaction::getDate).reversed())
                .map(transactionMapper::toDTO)
                .toList();
    }

    private boolean matchesFlow(Transaction t, String flow) {
        if (flow == null) return true;
        return "income".equals(flow) ? t.getAmount().signum() > 0 : t.getAmount().signum() < 0;
    }

    private boolean matchesCategory(Transaction t, Long categoryId, Long rootCategoryId, boolean uncategorized) {
        if (uncategorized) return t.getCategory() == null;
        if (categoryId != null) {
            return t.getCategory() != null && categoryId.equals(t.getCategory().getId());
        }
        if (rootCategoryId != null) {
            Categorie category = t.getCategory();
            if (category == null) return false;
            if (rootCategoryId.equals(category.getId())) return true;
            return category.getParent() != null && rootCategoryId.equals(category.getParent().getId());
        }
        return true;
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
        List<Compte> accounts = loadAccounts(accountId);

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

    /**
     * Transactions marquees "periodique", regroupees en deux ensembles
     * distincts (une ligne par tiers, la plus recente occurrence connue) :
     * "expenses" pour les depenses (montant negatif) et "revenues" pour les
     * revenus (montant positif) — la case a cocher "Periodique" ne distingue
     * pas les deux sur une transaction (loyer/abonnement comme salaire/CPAM
     * peuvent tous deux etre recurrents), il ne faut donc pas les melanger
     * dans un seul total affiche comme "Depenses periodiques".
     */
    public Map<String, Object> periodicExpensesSummary() {
        LocalDate today = LocalDate.now();
        List<Transaction> flagged = transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc()
                .stream()
                // Une occurrence future n'a pas encore reellement eu lieu : on ne la
                // retient pas comme "derniere" occurrence connue de la depense.
                .filter(t -> !t.getDate().isAfter(today))
                .toList();

        List<Transaction> expenses = flagged.stream().filter(t -> t.getAmount().signum() < 0).toList();
        List<Transaction> revenues = flagged.stream().filter(t -> t.getAmount().signum() >= 0).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expenses", summarizePeriodicGroup(expenses));
        result.put("revenues", summarizePeriodicGroup(revenues));
        return result;
    }

    /** Une ligne par tiers (la plus recente occurrence) + le total, pour un groupe de transactions periodiques deja filtre par signe. */
    private Map<String, Object> summarizePeriodicGroup(List<Transaction> transactions) {
        Map<String, Transaction> latestByPayee = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            String key = (t.getPayee() != null ? t.getPayee().getName().toLowerCase(Locale.ROOT) : "id-" + t.getId());
            latestByPayee.putIfAbsent(key, t);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : latestByPayee.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("transactionId", t.getId());
            item.put("payee", t.getPayee() != null ? t.getPayee().getName() : null);
            item.put("date", t.getDate());
            item.put("amount", t.getAmount().abs());
            items.add(item);
            total = total.add(t.getAmount().abs());
        }
        items.sort((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("items", items);
        group.put("total", total);
        return group;
    }

    private BigDecimal balanceAsOf(LocalDate date, List<Compte> accounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (Compte a : accounts) {
            total = total.add(a.getInitialBalance())
                    .add(transactionRepository.sumAmountByAccountIdAsOf(a.getId(), date));
        }
        return total;
    }

    private List<Compte> loadAccounts(Long accountId) {
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

    private String categoryLabel(Categorie category) {
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

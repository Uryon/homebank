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

    /**
     * Une transaction (reelle) attribuee a un mois donne pour un montant
     * donne — en general la transaction elle-meme sur sa propre date, sauf
     * pour une transaction "annualisee" (voir Transaction#annualized) qui
     * genere une ligne par mois de la fenetre glissante de 12 mois demarrant
     * a son propre mois de paiement, chacune pour 1/12 de son montant (voir
     * {@link #attributedTransactions}).
     */
    private record AttributedTransaction(Transaction source, LocalDate date, BigDecimal amount) {}

    /**
     * Transactions "attribuables" a la periode [from,to] : les transactions
     * normales, sur leur propre date reelle ; et, pour toute transaction
     * marquee "annualisee" (ex : taxe fonciere payee en une fois mais qui
     * couvre toute l'annee), une ligne par mois de la fenetre glissante de
     * 12 mois qui commence a son propre mois de paiement (ex : payee en
     * octobre 2025 => repartie d'octobre 2025 a septembre 2026 inclus) qui
     * tombe dans la periode — chacune pour 1/12 de son montant — meme quand
     * la date reelle du paiement elle-meme est hors periode. Une transaction
     * annualisee ne compte donc jamais en une fois sur son seul mois de
     * paiement : uniquement lissee sur les 12 mois suivants. Le solde reel
     * des comptes (balanceForecast) n'utilise jamais cette methode : il
     * reste base sur la date et le montant reels.
     */
    private List<AttributedTransaction> attributedTransactions(LocalDate from, LocalDate to, Long accountId) {
        LocalDate today = LocalDate.now();
        List<AttributedTransaction> result = new ArrayList<>();

        List<Transaction> inRange = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);
        for (Transaction t : inRange) {
            if (t.getPaymentMode() == ModePaiement.TRANSFER) continue;
            if (t.getDate().isAfter(today)) continue;
            if (t.isAnnualized()) continue; // traitee ci-dessous, jamais pour son montant plein
            result.add(new AttributedTransaction(t, t.getDate(), t.getAmount()));
        }

        for (Transaction t : transactionRepository.findByAnnualizedTrue()) {
            if (t.getPaymentMode() == ModePaiement.TRANSFER) continue;
            if (t.getDate().isAfter(today)) continue;
            if (accountId != null && (t.getAccount() == null || !accountId.equals(t.getAccount().getId()))) continue;

            BigDecimal monthlyShare = t.getAmount().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            LocalDate firstMonth = t.getDate().withDayOfMonth(1);
            for (int i = 0; i < 12; i++) {
                LocalDate monthStart = firstMonth.plusMonths(i);
                if (monthStart.isBefore(from) || monthStart.isAfter(to)) continue;
                result.add(new AttributedTransaction(t, monthStart, monthlyShare));
            }
        }

        return result;
    }

    /**
     * Repartition des depenses (montants negatifs) par categorie racine,
     * sur une periode donnee. Renvoie une liste triee par montant decroissant.
     * Les virements entre comptes sont exclus.
     */
    public List<Map<String, Object>> expensesByCategory(LocalDate from, LocalDate to, Long accountId) {
        List<AttributedTransaction> transactions = attributedTransactions(from, to, accountId);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        // Categorie (id, ou absence = "non categorise") representative de chaque
        // libelle, memorisee au passage pour permettre au frontend de retrouver
        // ensuite les transactions exactes d'une ligne (voir cellTransactions).
        Map<String, Long> categoryIdByLabel = new LinkedHashMap<>();
        Set<String> uncategorizedLabels = new HashSet<>();
        for (AttributedTransaction at : transactions) {
            if (at.amount().signum() >= 0) continue; // on ne garde que les depenses
            Categorie category = at.source().getCategory();
            String label = categoryLabel(category);
            totals.merge(label, at.amount().abs(), BigDecimal::add);
            if (category != null) {
                categoryIdByLabel.putIfAbsent(label, category.getId());
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
     * (attributedTransactions : virements exclus, transactions futures
     * exclues, annualisees lissees sur leur annee) —
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

        List<AttributedTransaction> transactions = attributedTransactions(from, to, accountId);

        return transactions.stream()
                .filter(at -> matchesFlow(at, flow))
                .filter(at -> matchesCategory(at.source(), categoryId, rootCategoryId, uncategorized))
                .sorted(Comparator.comparing(AttributedTransaction::date).reversed())
                .map(at -> {
                    // Pour une tranche annualisee, le montant et la date affiches sont
                    // ceux de la tranche (1/12, mois attribue), jamais ceux de la
                    // transaction source, pour que le detail corresponde exactement
                    // au total affiche dans la cellule survolee.
                    TransactionDTO dto = transactionMapper.toDTO(at.source());
                    dto.setAmount(at.amount());
                    dto.setDate(at.date());
                    return dto;
                })
                .toList();
    }

    private boolean matchesFlow(AttributedTransaction at, String flow) {
        if (flow == null) return true;
        return "income".equals(flow) ? at.amount().signum() > 0 : at.amount().signum() < 0;
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
        List<AttributedTransaction> transactions = attributedTransactions(from, to, accountId);

        BigDecimal income = transactions.stream()
                .map(AttributedTransaction::amount)
                .filter(a -> a.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expenses = transactions.stream()
                .map(AttributedTransaction::amount)
                .filter(a -> a.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("income", income);
        result.put("expenses", expenses.abs());
        result.put("net", income.add(expenses));
        return result;
    }

    /**
     * Premier jour du mois "courant" a utiliser comme ancre pour toutes les
     * fenetres de mois des rapports (analyse mensuelle, epargne, solde
     * previsionnel) : referenceMonth + 1 mois si fourni (navigation depuis
     * le tableau de bord, voir Dashboard.vue), sinon le mois calendaire en
     * cours. Chaque rapport reconstruit ensuite sa fenetre de mois EXACTEMENT
     * comme avant (derniers mois avant cette ancre), ce qui garantit que
     * referenceMonth omis reproduit le comportement historique (dernier mois
     * calendaire deja termine).
     */
    private LocalDate anchorMonth(LocalDate referenceMonth) {
        return referenceMonth != null
                ? referenceMonth.withDayOfMonth(1).plusMonths(1)
                : LocalDate.now().withDayOfMonth(1);
    }

    /**
     * Epargne mensuelle (revenus - depenses, virements exclus) sur les N
     * mois entierement termines precedant referenceMonth (dernier mois
     * inclus) — ou les N derniers mois deja termines si referenceMonth est
     * omis.
     */
    public List<Map<String, Object>> monthlySavings(int monthsBack, Long accountId, LocalDate referenceMonth) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate firstOfCurrentMonth = anchorMonth(referenceMonth);

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
     * Solde mensuel sur une fenetre de 24 mois (12 avant, 12 apres), ancree
     * sur referenceMonth (navigation dans le tableau de bord, y compris dans
     * le futur) si fourni, sinon sur le mois calendaire en cours. Un mois de
     * la fenetre est "historique" (solde reel, balanceAsOf) s'il est deja
     * termine par rapport au vrai calendrier, "previsionnel" (projete) sinon
     * — independamment de sa position par rapport a referenceMonth : naviguer
     * loin dans le passe ou dans le futur ne change jamais ce qui est reel.
     * La tendance de projection (variation mensuelle moyenne) est toujours
     * calculee sur les 12 vrais mois precedant aujourd'hui, jamais sur la
     * fenetre affichee, pour rester coherente quelle que soit la navigation.
     * Pas de point separe pour "aujourd'hui" : le mois en cours n'etant pas
     * termine, il releve deja entierement du previsionnel. Chaque point
     * precise son "type" (historique | previsionnel) pour permettre au
     * frontend de les distinguer visuellement.
     */
    public List<Map<String, Object>> balanceForecast(Long accountId, LocalDate referenceMonth) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate firstOfCurrentMonth = anchorMonth(referenceMonth);
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);
        // Charge les comptes une seule fois pour tout le calcul, plutot qu'a
        // chaque appel de balanceAsOf (findAll()/findById() repete).
        List<Compte> accounts = loadAccounts(accountId);

        List<BigDecimal> trendBalances = new ArrayList<>();
        for (int i = 12; i >= 1; i--) {
            LocalDate monthEnd = currentMonthStart.minusMonths(i).plusMonths(1).minusDays(1);
            trendBalances.add(balanceAsOf(monthEnd, accounts));
        }
        BigDecimal avgMonthlyNet = BigDecimal.ZERO;
        if (trendBalances.size() >= 2) {
            BigDecimal totalChange = trendBalances.get(trendBalances.size() - 1)
                    .subtract(trendBalances.get(0));
            avgMonthlyNet = totalChange.divide(
                    BigDecimal.valueOf(trendBalances.size() - 1), 2, RoundingMode.HALF_UP);
        }

        // Chaque point = le precedent + la variation mensuelle moyenne des
        // qu'on entre dans le previsionnel, en partant du dernier solde reel
        // connu. Ne part jamais du solde partiel d'aujourd'hui : sinon le
        // mois en cours melangerait donnees reelles partielles et moyenne,
        // avec un point previsionnel incoherent par rapport aux mois
        // suivants (ex : en baisse alors que la tendance moyenne monte).
        BigDecimal projected = trendBalances.get(trendBalances.size() - 1);
        for (int i = -12; i <= 11; i++) {
            LocalDate monthStart = firstOfCurrentMonth.plusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            if (monthStart.isBefore(currentMonthStart)) {
                projected = balanceAsOf(monthEnd, accounts);
                result.add(point(monthEnd, projected, "historique"));
            } else {
                projected = projected.add(avgMonthlyNet);
                result.add(point(monthEnd, projected, "previsionnel"));
            }
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
        LocalDate threeMonthsAgo = today.minusMonths(3);
        List<Transaction> flagged = transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc()
                .stream()
                // Une occurrence future n'a pas encore reellement eu lieu : on ne la
                // retient pas comme "derniere" occurrence connue de la depense.
                .filter(t -> !t.getDate().isAfter(today))
                // Une depense periodique dont la derniere occurrence connue remonte a
                // plus de 3 mois est probablement obsolete (abonnement resilie, etc.) :
                // on ne l'affiche plus dans le tableau de bord.
                .filter(t -> !t.getDate().isBefore(threeMonthsAgo))
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
     * sur les 12 mois entièrement terminés précédant referenceMonth (dernier
     * mois inclus) — ou les 12 derniers mois déjà terminés si referenceMonth
     * est omis. Les transactions annualisées y sont lissées sur leur année
     * (voir attributedTransactions), ce qui impose une agrégation côté
     * backend plutôt qu'en base.
     */
    public Map<String, Object> monthlyCategoryAnalysis(Long accountId, LocalDate referenceMonth) {
        LocalDate firstOfCurrentMonth = anchorMonth(referenceMonth);
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

        // Pre-agregation par (categorie, mois) — equivalent du GROUP BY qu'une
        // requete SQL ferait, mais en Java pour pouvoir inclure les tranches
        // de transactions annualisees (voir attributedTransactions), qui
        // n'existent qu'en memoire (jamais en base sous cette forme).
        Map<String, BigDecimal> perCategoryMonth = new LinkedHashMap<>();
        Map<String, Categorie> categoryByKey = new LinkedHashMap<>();
        for (AttributedTransaction at : attributedTransactions(from, to, accountId)) {
            Categorie category = at.source().getCategory();
            String catKey = category != null ? category.getId().toString() : "uncategorized";
            String monthKey = at.date().withDayOfMonth(1).toString();
            perCategoryMonth.merge(catKey + "|" + monthKey, at.amount(), BigDecimal::add);
            categoryByKey.putIfAbsent(catKey, category);
        }

        for (Map.Entry<String, BigDecimal> entry : perCategoryMonth.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String catKey = parts[0];
            String monthKey = parts[1];
            BigDecimal amount = entry.getValue();

            Categorie category = categoryByKey.get(catKey);
            Long categoryId = category != null ? category.getId() : null;
            Long parentId = category != null && category.getParent() != null ? category.getParent().getId() : null;
            String parentName = category != null && category.getParent() != null ? category.getParent().getName() : null;

            // Les transactions sans catégorie restent visibles dans le tableau.
            String safeName = category != null ? category.getName() : "Sans catégorie";
            String key = catKey + "|" + (amount.signum() > 0 ? "income" : "expense");

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

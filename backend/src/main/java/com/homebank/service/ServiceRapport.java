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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ServiceRapport {

    private static final String INCOME = "income";
    private static final String EXPENSE = "expense";
    private static final String EXPENSES_KEY = "expenses";
    private static final String TOTAL_KEY = "total";
    private static final String LEVEL_KEY = "level";
    private static final String PARENT_NAME_KEY = "parentName";
    private static final String VALUES_KEY = "values";
    private static final String LABEL_KEY = "label";
    private static final String AMOUNT_KEY = "amount";

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
     * pour une transaction dont la categorie est marquee "annuelle" (voir
     * Categorie#annualized) qui genere une ligne par mois de la fenetre
     * glissante de 12 mois demarrant a son propre mois de paiement, chacune
     * pour 1/12 de son montant (voir {@link #attributedTransactions}).
     */
    private record AttributedTransaction(Transaction source, LocalDate date, BigDecimal amount) {}

    /** Vrai si la transaction est affectee a une categorie marquee "annuelle" (voir Categorie#annualized). */
    private boolean hasAnnualizedCategory(Transaction t) {
        return t.getCategory() != null && t.getCategory().isAnnualized();
    }

    /**
     * Transactions "attribuables" a la periode [from,to] : les transactions
     * normales, sur leur propre date reelle ; et, pour toute transaction dont
     * la categorie est marquee "annuelle" (ex : taxe fonciere payee en une
     * fois mais qui couvre toute l'annee), une ligne par mois de la fenetre
     * glissante de 12 mois qui commence a son propre mois de paiement (ex :
     * payee en octobre 2025 => repartie d'octobre 2025 a septembre 2026
     * inclus) qui tombe dans la periode — chacune pour 1/12 de son montant —
     * meme quand la date reelle du paiement elle-meme est hors periode. Une
     * transaction d'une categorie annuelle ne compte donc jamais en une fois
     * sur son seul mois de paiement : uniquement lissee sur les 12 mois
     * suivants. Les points "historique"/"previsionnel" de balanceForecast
     * restent bases sur le solde reel des comptes (balanceAsOf, jamais lisse) ;
     * seule sa tendance mensuelle moyenne (avgMonthlyNet) passe par summary()
     * et donc par cette methode.
     * <p>
     * Une transaction marquee "exceptionnelle" (voir Transaction#exceptional)
     * est totalement exclue de attributedTransactions, quel que soit le
     * caractere annuel de sa categorie, pour ne jamais fausser les moyennes/
     * tendances des tableaux de bord (ex : achat d'une voiture).
     */
    private List<AttributedTransaction> attributedTransactions(LocalDate from, LocalDate to, Long accountId) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        List<AttributedTransaction> result = new ArrayList<>();

        List<Transaction> inRange = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);
        for (Transaction t : inRange) {
            addIfAttributable(t, today, result);
        }

        for (Transaction t : transactionRepository.findByCategory_AnnualizedTrue()) {
            addAnnualizedShares(t, today, from, to, accountId, result);
        }

        return result;
    }

    /** Vrai si t doit etre exclue de tous les rapports, quelle que soit sa categorie : virement, transaction future, ou marquee exceptionnelle. */
    private static boolean isExcludedFromReports(Transaction t, LocalDate today) {
        return t.getPaymentMode() == ModePaiement.TRANSFER || t.getDate().isAfter(today) || t.isExceptional();
    }

    /** Ajoute t a result s'il est attribuable normalement (voir attributedTransactions) : les categories annuelles sont traitees a part par addAnnualizedShares, jamais ici pour leur montant plein. */
    private void addIfAttributable(Transaction t, LocalDate today, List<AttributedTransaction> result) {
        if (isExcludedFromReports(t, today)) return;
        if (hasAnnualizedCategory(t)) return;
        result.add(new AttributedTransaction(t, t.getDate(), t.getAmount()));
    }

    /** Ajoute a result une ligne par mois de la fenetre glissante de 12 mois de t qui tombe dans [from,to] (voir attributedTransactions), pour le compte filtre (accountId==null = tous). */
    private void addAnnualizedShares(Transaction t, LocalDate today, LocalDate from, LocalDate to, Long accountId, List<AttributedTransaction> result) {
        if (isExcludedFromReports(t, today)) return;
        if (accountId != null && (t.getAccount() == null || !accountId.equals(t.getAccount().getId()))) return;

        BigDecimal monthlyShare = t.getAmount().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        LocalDate firstMonth = t.getDate().withDayOfMonth(1);
        for (int i = 0; i < 12; i++) {
            LocalDate monthStart = firstMonth.plusMonths(i);
            if (monthStart.isBefore(from) || monthStart.isAfter(to)) continue;
            result.add(new AttributedTransaction(t, monthStart, monthlyShare));
        }
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
                    row.put(TOTAL_KEY, e.getValue());
                    row.put("percentage", grandTotal.signum() == 0
                            ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                                    .divide(grandTotal, 1, RoundingMode.HALF_UP));
                    return row;
                })
                .toList();
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
        return INCOME.equals(flow) ? at.amount().signum() > 0 : at.amount().signum() < 0;
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
        result.put(INCOME, income);
        result.put(EXPENSES_KEY, expenses.abs());
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
                : LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);
    }

    /**
     * Epargne mensuelle (revenus - depenses, virements exclus) sur les N
     * mois entierement termines precedant referenceMonth (dernier mois
     * inclus) — ou les N derniers mois deja termines si referenceMonth est
     * omis. En naviguant dans le futur (referenceMonth au-dela du mois
     * calendaire reel en cours), les mois pas encore termines sont
     * previsionnels : remplis avec avgMonthlyNetOverTrailing12Months (ancree
     * sur les 12 VRAIS derniers mois, jamais sur la fenetre affichee — sinon
     * la moyenne finit par n'avoir plus aucun mois reel a moyenner, et donc
     * par retomber a 0, en naviguant suffisamment loin dans le futur),
     * plutot qu'une epargne reelle forcement nulle — chaque point precise
     * "forecast" pour permettre au frontend de les distinguer visuellement.
     */
    public List<Map<String, Object>> monthlySavings(int monthsBack, Long accountId, LocalDate referenceMonth) {
        LocalDate firstOfCurrentMonth = anchorMonth(referenceMonth);
        LocalDate currentMonthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);
        BigDecimal forecastAverage = avgMonthlyNetOverTrailing12Months(accountId, currentMonthStart);

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = monthsBack; i >= 1; i--) {
            LocalDate monthStart = firstOfCurrentMonth.minusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            boolean forecast = !monthStart.isBefore(currentMonthStart);
            BigDecimal net = forecast ? forecastAverage : (BigDecimal) summary(monthStart, monthEnd, accountId).get("net");

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", monthStart.toString());
            point.put(LABEL_KEY, monthLabel(monthStart));
            point.put("savings", net);
            point.put("forecast", forecast);
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
     * La navigation dans le tableau de bord n'est plus bornee dans le futur :
     * si la fenetre affichee (12 mois avant/apres referenceMonth) ne touche
     * plus le mois reel en cours, "projected" est d'abord avance du nombre de
     * mois qui separent aujourd'hui du debut de cette fenetre, pour que la
     * tendance continue de composer mois par mois au lieu d'etre sous-estimee.
     * Le resultat expose aussi "monthlyTrend" (la variation mensuelle moyenne
     * elle-meme, voir avgMonthlyNet) a cote de "points", pour que le frontend
     * puisse afficher le montant ajoute chaque mois a la courbe previsionnelle.
     * <p>
     * avgMonthlyNet est la moyenne du "net" (revenus - depenses) des 12 vrais
     * derniers mois termines, calcule via {@link #summary} exactement comme
     * partout ailleurs dans le tableau de bord (virements, transactions
     * futures et marquees "exceptionnelles" exclus, voir isExcludedFromReports)
     * — plutot que l'ecart brut de solde entre compte, qui aurait inclus ces
     * mouvements ponctuels et rendu la tendance incoherente avec le "Net"
     * affiche ailleurs (cartes Revenus/Depenses, Resume mensuel).
     */
    public Map<String, Object> balanceForecast(Long accountId, LocalDate referenceMonth) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate firstOfCurrentMonth = anchorMonth(referenceMonth);
        LocalDate currentMonthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);
        // Charge les comptes une seule fois pour tout le calcul, plutot qu'a
        // chaque appel de balanceAsOf (findAll()/findById() repete).
        List<Compte> accounts = loadAccounts(accountId);

        BigDecimal avgMonthlyNet = avgMonthlyNetOverTrailing12Months(accountId, currentMonthStart);

        // Chaque point = le precedent + la variation mensuelle moyenne des
        // qu'on entre dans le previsionnel, en partant du dernier solde reel
        // connu. Ne part jamais du solde partiel d'aujourd'hui : sinon le
        // mois en cours melangerait donnees reelles partielles et moyenne,
        // avec un point previsionnel incoherent par rapport aux mois
        // suivants (ex : en baisse alors que la tendance moyenne monte).
        BigDecimal projected = balanceAsOf(currentMonthStart.minusDays(1), accounts);

        // Navigation loin dans le futur (au-dela de l'horizon usuel de 12
        // mois) : le debut de la fenetre affichee peut lui-meme etre deja
        // dans le previsionnel. Sans ce rattrapage, la boucle ci-dessous ne
        // composerait qu'un seul mois de tendance sur ce premier point, quel
        // que soit le nombre de mois reellement ecoules depuis aujourd'hui.
        LocalDate windowStart = firstOfCurrentMonth.minusMonths(12);
        long monthsBeforeWindow = ChronoUnit.MONTHS.between(currentMonthStart, windowStart);
        if (monthsBeforeWindow > 0) {
            projected = projected.add(avgMonthlyNet.multiply(BigDecimal.valueOf(monthsBeforeWindow)));
        }

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

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("points", result);
        response.put("monthlyTrend", avgMonthlyNet);
        return response;
    }

    /**
     * Variation mensuelle moyenne (revenus - depenses, voir {@link #summary})
     * sur les 12 VRAIS derniers mois termines, ancree sur currentMonthStart —
     * jamais sur une fenetre affichee/referenceMonth navigable. Base commune
     * du previsionnel pour balanceForecast (solde) et monthlySavings (epargne
     * mensuelle), pour que la projection reste la meme peu importe jusqu'ou
     * on navigue dans le futur (meme principe que categoryForecastAverages
     * pour monthlyCategoryAnalysis).
     */
    private BigDecimal avgMonthlyNetOverTrailing12Months(Long accountId, LocalDate currentMonthStart) {
        BigDecimal totalNet = BigDecimal.ZERO;
        for (int i = 12; i >= 1; i--) {
            LocalDate monthStart = currentMonthStart.minusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            totalNet = totalNet.add((BigDecimal) summary(monthStart, monthEnd, accountId).get("net"));
        }
        return totalNet.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
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
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate threeMonthsAgo = today.minusMonths(3);
        List<Transaction> flagged = transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc()
                .stream()
                // Une transaction exceptionnelle est exclue de tous les tableaux de
                // bord (voir Transaction#exceptional), y compris celui-ci.
                .filter(t -> !t.isExceptional())
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
        result.put(EXPENSES_KEY, summarizePeriodicGroup(expenses));
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
            item.put(AMOUNT_KEY, t.getAmount().abs());
            items.add(item);
            total = total.add(t.getAmount().abs());
        }
        items.sort((a, b) -> ((BigDecimal) b.get(AMOUNT_KEY)).compareTo((BigDecimal) a.get(AMOUNT_KEY)));

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("items", items);
        group.put(TOTAL_KEY, total);
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
        m.put(LABEL_KEY, monthLabel(date));
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
        // Mois calendaire reel en cours (independant de referenceMonth, qui
        // peut avoir ete navigue vers le futur) : sert a distinguer les mois
        // "reels" des mois "previsionnels" ci-dessous, meme convention que
        // balanceForecast (currentMonthStart la-bas).
        LocalDate currentMonthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);

        List<Map<String, Object>> months = buildMonthsWindow(from, firstOfCurrentMonth, currentMonthStart);
        Map<String, BigDecimal> monthlyIncomeTotals = zeroedMonthlyTotals(months);
        Map<String, BigDecimal> monthlyExpenseTotals = zeroedMonthlyTotals(months);

        // Clé stable : catégorie (ou catégorie racine + sous-catégorie) + type de flux.
        Map<String, Map<String, Object>> rows = buildCategoryMonthRows(
                attributedTransactions(from, to, accountId), months, monthlyIncomeTotals, monthlyExpenseTotals);

        // Previsionnel par categorie/flux, ancre sur les 12 VRAIS derniers
        // mois (jamais sur la fenetre affichee ci-dessus, qui peut ne plus
        // contenir aucun mois reel en naviguant loin dans le futur — d'ou des
        // montants a 0 sans cette fenetre de reference fixe, voir
        // categoryForecastAverages). Complete aussi rows avec une ligne
        // vierge pour une categorie qui a de l'activite sur ces 12 vrais
        // mois mais AUCUNE transaction dans la fenetre affichee (sinon elle
        // n'apparaitrait pas du tout dans le tableau en naviguant loin dans
        // le futur, alors qu'elle a un previsionnel a montrer).
        CategoryForecast forecast = categoryForecastAverages(accountId, currentMonthStart);
        for (String key : forecast.averages().keySet()) {
            if (key.equals("total-" + INCOME) || key.equals("total-" + EXPENSE) || rows.containsKey(key)) continue;
            String[] parts = key.split("\\|", 2);
            Categorie category = forecast.categoriesByKey().get(parts[0]);
            rows.put(key, newCategoryRow(key, category, parts[1], months));
        }

        // Construit la hiérarchie : racines d'abord, sous-catégories juste après.
        List<Map<String, Object>> incomeRows = extractSortedRows(rows, INCOME, currentMonthStart, forecast.averages());
        List<Map<String, Object>> expenseRows = extractSortedRows(rows, EXPENSE, currentMonthStart, forecast.averages());
        Map<String, Object> incomeTotal = totalAnalysisRow("Total revenus", INCOME, months, monthlyIncomeTotals,
                currentMonthStart, forecast.averages().getOrDefault("total-" + INCOME, BigDecimal.ZERO));
        Map<String, Object> expenseTotal = totalAnalysisRow("Total dépenses", EXPENSE, months, monthlyExpenseTotals,
                currentMonthStart, forecast.averages().getOrDefault("total-" + EXPENSE, BigDecimal.ZERO));
        // Reutilise les valeurs de incomeTotal/expenseTotal (deja completees
        // par le previsionnel ci-dessus) plutot que les totaux bruts, pour
        // que le resume mensuel (Revenus/Depenses/Solde) affiche lui aussi
        // le previsionnel sur les mois futurs au lieu de zeros.
        List<Map<String, Object>> monthlySummary = buildMonthlySummary(months, valuesOf(incomeTotal), valuesOf(expenseTotal));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("months", months);
        result.put(INCOME, incomeRows);
        result.put("incomeTotal", incomeTotal);
        result.put(EXPENSES_KEY, expenseRows);
        result.put("expenseTotal", expenseTotal);
        result.put("summary", monthlySummary);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BigDecimal> valuesOf(Map<String, Object> row) {
        return (Map<String, BigDecimal>) row.get(VALUES_KEY);
    }

    /** Un point par mois entierement termine de [from, firstOfCurrentMonth[ (voir monthlyCategoryAnalysis), marque "forecast" a partir du mois calendaire reel en cours (currentMonthStart). */
    private List<Map<String, Object>> buildMonthsWindow(LocalDate from, LocalDate firstOfCurrentMonth, LocalDate currentMonthStart) {
        List<Map<String, Object>> months = new ArrayList<>();
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMM.", Locale.FRENCH);
        for (LocalDate month = from; month.isBefore(firstOfCurrentMonth); month = month.plusMonths(1)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", month.toString());
            m.put(LABEL_KEY, capitalize(month.format(monthFormatter)));
            m.put("year", month.getYear());
            m.put("month", month.getMonthValue());
            m.put("forecast", !month.isBefore(currentMonthStart));
            months.add(m);
        }
        return months;
    }

    private static Map<String, BigDecimal> zeroedMonthlyTotals(List<Map<String, Object>> months) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Map<String, Object> m : months) {
            totals.put((String) m.get("key"), BigDecimal.ZERO);
        }
        return totals;
    }

    /**
     * Pre-agrege les transactions attribuees en lignes categorie/mois (voir
     * monthlyCategoryAnalysis) — equivalent du GROUP BY qu'une requete SQL
     * ferait, mais en Java pour pouvoir inclure les tranches de transactions
     * annualisees (voir attributedTransactions), qui n'existent qu'en
     * memoire (jamais en base sous cette forme) — en cumulant au passage les
     * totaux mensuels income/expense (monthlyIncomeTotals/monthlyExpenseTotals,
     * mutes directement).
     */
    private Map<String, Map<String, Object>> buildCategoryMonthRows(
            List<AttributedTransaction> transactions,
            List<Map<String, Object>> months,
            Map<String, BigDecimal> monthlyIncomeTotals,
            Map<String, BigDecimal> monthlyExpenseTotals) {

        Map<String, BigDecimal> perCategoryMonth = new LinkedHashMap<>();
        Map<String, Categorie> categoryByKey = new LinkedHashMap<>();
        for (AttributedTransaction at : transactions) {
            Categorie category = at.source().getCategory();
            String catKey = category != null ? category.getId().toString() : "uncategorized";
            String monthKey = at.date().withDayOfMonth(1).toString();
            perCategoryMonth.merge(catKey + "|" + monthKey, at.amount(), BigDecimal::add);
            categoryByKey.putIfAbsent(catKey, category);
        }

        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : perCategoryMonth.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String catKey = parts[0];
            String monthKey = parts[1];
            BigDecimal amount = entry.getValue();
            Categorie category = categoryByKey.get(catKey);

            String type = amount.signum() > 0 ? INCOME : EXPENSE;
            Map<String, Object> row = rows.computeIfAbsent(
                    catKey + "|" + type,
                    key -> newCategoryRow(key, category, type, months));

            addMonthAmount(row, monthKey, amount);
            mergeMonthlyTotal(amount, monthKey, monthlyIncomeTotals, monthlyExpenseTotals);
        }
        return rows;
    }

    /** Nouvelle ligne vierge (valeurs a zero sur tous les mois) pour une categorie/type de flux (voir buildCategoryMonthRows). */
    private Map<String, Object> newCategoryRow(String key, Categorie category, String type, List<Map<String, Object>> months) {
        Long categoryId = category != null ? category.getId() : null;
        Long parentId = category != null && category.getParent() != null ? category.getParent().getId() : null;
        String parentName = category != null && category.getParent() != null ? category.getParent().getName() : null;
        // Les transactions sans catégorie restent visibles dans le tableau.
        String safeName = category != null ? category.getName() : "Sans catégorie";

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("categoryId", categoryId);
        row.put("name", safeName);
        row.put("parentId", parentId);
        row.put(PARENT_NAME_KEY, parentName);
        row.put(LEVEL_KEY, parentId == null ? 0 : 1);
        row.put("type", type);
        row.put(VALUES_KEY, zeroedMonthlyTotals(months));
        return row;
    }

    /**
     * averages : moyenne mensuelle par categorie/flux (meme cle
     * "catId|income|expense" que les lignes de monthlyCategoryAnalysis, voir
     * buildCategoryMonthRows), plus deux entrees "total-income"/"total-expense"
     * (meme cle que les lignes de total, voir totalAnalysisRow) pour la
     * moyenne globale. categoriesByKey : la Categorie derriere chaque "catId"
     * (sans le suffixe "|income|expense"), pour pouvoir synthetiser une ligne
     * pour une categorie qui n'a AUCUNE transaction dans la fenetre affichee
     * (voir monthlyCategoryAnalysis) mais en a dans la fenetre de reference.
     */
    private record CategoryForecast(Map<String, BigDecimal> averages, Map<String, Categorie> categoriesByKey) {}

    /**
     * Calcule le previsionnel par categorie/flux sur les 12 VRAIS derniers
     * mois termines, ancre sur currentMonthStart — jamais sur la fenetre
     * affichee/referenceMonth, qui peut avoir ete navigee loin dans le futur
     * au point de ne plus contenir aucun mois reel. Sert de previsionnel
     * pour les mois futurs de la fenetre affichee (voir completeAnalysisRow),
     * pour que la projection reste la meme peu importe jusqu'ou on navigue.
     */
    private CategoryForecast categoryForecastAverages(Long accountId, LocalDate currentMonthStart) {
        LocalDate from = currentMonthStart.minusMonths(12);
        LocalDate to = currentMonthStart.minusDays(1);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        Map<String, Categorie> categoriesByKey = new LinkedHashMap<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (AttributedTransaction at : attributedTransactions(from, to, accountId)) {
            Categorie category = at.source().getCategory();
            String catKey = category != null ? category.getId().toString() : "uncategorized";
            categoriesByKey.putIfAbsent(catKey, category);
            BigDecimal amount = at.amount();
            String key = catKey + "|" + (amount.signum() > 0 ? INCOME : EXPENSE);
            totals.merge(key, amount.abs(), BigDecimal::add);
            if (amount.signum() > 0) totalIncome = totalIncome.add(amount);
            else if (amount.signum() < 0) totalExpense = totalExpense.add(amount.abs());
        }
        totals.put("total-" + INCOME, totalIncome);
        totals.put("total-" + EXPENSE, totalExpense);

        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            averages.put(entry.getKey(), entry.getValue().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
        }
        return new CategoryForecast(averages, categoriesByKey);
    }

    @SuppressWarnings("unchecked")
    private static void addMonthAmount(Map<String, Object> row, String monthKey, BigDecimal amount) {
        Map<String, BigDecimal> values = (Map<String, BigDecimal>) row.get(VALUES_KEY);
        values.put(monthKey, values.getOrDefault(monthKey, BigDecimal.ZERO).add(amount.abs()));
    }

    private static void mergeMonthlyTotal(BigDecimal amount, String monthKey,
                                           Map<String, BigDecimal> monthlyIncomeTotals, Map<String, BigDecimal> monthlyExpenseTotals) {
        if (amount.signum() > 0) {
            monthlyIncomeTotals.merge(monthKey, amount, BigDecimal::add);
        } else if (amount.signum() < 0) {
            monthlyExpenseTotals.merge(monthKey, amount.abs(), BigDecimal::add);
        }
    }

    private List<Map<String, Object>> extractSortedRows(
            Map<String, Map<String, Object>> rows, String type, LocalDate currentMonthStart, Map<String, BigDecimal> forecastAverages) {
        return rows.values().stream()
                .filter(r -> type.equals(r.get("type")))
                .sorted(this::compareCategoryRows)
                .map(r -> completeAnalysisRow(r, currentMonthStart, forecastAverages.getOrDefault((String) r.get("key"), BigDecimal.ZERO)))
                .toList();
    }

    private List<Map<String, Object>> buildMonthlySummary(
            List<Map<String, Object>> months, Map<String, BigDecimal> monthlyIncomeTotals, Map<String, BigDecimal> monthlyExpenseTotals) {
        List<Map<String, Object>> monthlySummary = new ArrayList<>();
        for (Map<String, Object> m : months) {
            String key = (String) m.get("key");
            BigDecimal income = monthlyIncomeTotals.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal expenses = monthlyExpenseTotals.getOrDefault(key, BigDecimal.ZERO);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("key", key);
            summary.put(INCOME, income);
            summary.put(EXPENSES_KEY, expenses);
            summary.put("net", income.subtract(expenses));
            summary.put("forecast", m.get("forecast"));
            monthlySummary.add(summary);
        }
        return monthlySummary;
    }

    /**
     * Complete une ligne (valeurs construites a partir des seules transactions
     * reelles, donc a 0 sur les mois futurs) : les mois a partir du mois
     * calendaire reel en cours (currentMonthStart, meme convention que
     * balanceForecast) sont remplaces par forecastAverage — la moyenne
     * previsionnelle de cette ligne sur les 12 VRAIS derniers mois (voir
     * categoryForecastAverages), fixe quelle que soit la fenetre affichee —
     * plutot que des zeros qui donneraient l'impression trompeuse qu'aucun
     * revenu/depense n'est prevu. "average" est recalculee APRES ce
     * remplacement (moyenne des 12 valeurs desormais affichees, reelles ou
     * previsionnelles) pour rester la moyenne de ce qui est effectivement
     * montre a l'ecran.
     */
    private Map<String, Object> completeAnalysisRow(Map<String, Object> row, LocalDate currentMonthStart, BigDecimal forecastAverage) {
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> values = (Map<String, BigDecimal>) row.get(VALUES_KEY);

        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            if (!LocalDate.parse(entry.getKey()).isBefore(currentMonthStart)) {
                entry.setValue(forecastAverage);
            }
        }

        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = values.isEmpty()
                ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
        row.put("average", average);
        row.put(TOTAL_KEY, total);
        return row;
    }

    private Map<String, Object> totalAnalysisRow(
            String name,
            String type,
            List<Map<String, Object>> months,
            Map<String, BigDecimal> monthlyTotals,
            LocalDate currentMonthStart,
            BigDecimal forecastAverage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", "total-" + type);
        row.put("name", name);
        row.put(LEVEL_KEY, 0);
        row.put("type", type);
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (Map<String, Object> month : months) {
            String key = (String) month.get("key");
            values.put(key, monthlyTotals.getOrDefault(key, BigDecimal.ZERO));
        }
        row.put(VALUES_KEY, values);
        return completeAnalysisRow(row, currentMonthStart, forecastAverage);
    }

    private int compareCategoryRows(Map<String, Object> a, Map<String, Object> b) {
        String ap = Objects.toString(a.get(PARENT_NAME_KEY), "");
        String bp = Objects.toString(b.get(PARENT_NAME_KEY), "");
        int parent = ap.compareToIgnoreCase(bp);
        if (parent != 0) return parent;

        int level = Integer.compare(
                ((Number) a.get(LEVEL_KEY)).intValue(),
                ((Number) b.get(LEVEL_KEY)).intValue());
        if (level != 0) return level;

        return Objects.toString(a.get("name"), "").compareToIgnoreCase(
                Objects.toString(b.get("name"), ""));
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.FRENCH) + value.substring(1);
    }

}

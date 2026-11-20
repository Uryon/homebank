package com.homebank.service;

import com.homebank.dto.DepenseParCategorieDTO;
import com.homebank.dto.ElementPeriodiqueDTO;
import com.homebank.dto.EpargneMensuelleDTO;
import com.homebank.dto.GroupePeriodiqueDTO;
import com.homebank.dto.PointSoldeDTO;
import com.homebank.dto.PrevisionSoldeDTO;
import com.homebank.dto.SynthesePeriodiqueDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Categorie;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotCompte;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.Periode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homebank.service.AttributionTransactions.AttributedTransaction;
import com.homebank.service.AttributionTransactions.CategorieMois;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.homebank.service.AttributionTransactions.INCOME;

/**
 * Rapports du tableau de bord : lecture seule (Hibernate ne garde alors aucun
 * instantane des milliers d'entites chargees pour detecter des modifications).
 */
@Service
@Transactional(readOnly = true)
public class ServiceRapport {

    /** Fenetre maximale de l'epargne mensuelle (10 ans) : le tableau de bord en demande 12. */
    static final int MAX_SAVINGS_MONTHS = 120;

    private final DepotTransaction transactionRepository;
    private final DepotCompte accountRepository;
    private final MappeurTransaction transactionMapper;
    private final AttributionTransactions attribution;

    public ServiceRapport(DepotTransaction transactionRepository, DepotCompte accountRepository, MappeurTransaction transactionMapper,
                          AttributionTransactions attribution) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.transactionMapper = transactionMapper;
        this.attribution = attribution;
    }

    /**
     * Repartition des depenses (montants negatifs) par categorie racine,
     * sur une periode donnee, remboursements deduits (voir netByCategoryMonth).
     * Renvoie une liste triee par montant decroissant. Les virements entre
     * comptes sont exclus.
     */
    public List<DepenseParCategorieDTO> expensesByCategory(LocalDate from, LocalDate to, Long accountId) {
        Periode.verifierOrdre(from, to);
        List<AttributedTransaction> transactions = attribution.attributedTransactions(from, to, accountId);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        // Categorie (id, ou absence = "non categorise") representative de chaque
        // libelle, memorisee au passage pour permettre au frontend de retrouver
        // ensuite les transactions exactes d'une ligne (voir cellTransactions).
        Map<String, Long> categoryIdByLabel = new LinkedHashMap<>();
        Set<String> uncategorizedLabels = new HashSet<>();
        Map<Long, Categorie> categories = AttributionTransactions.categoriesById(transactions);
        for (Map.Entry<CategorieMois, BigDecimal> net : AttributionTransactions.netByCategoryMonth(transactions).entrySet()) {
            if (net.getValue().signum() >= 0) continue; // on ne garde que les depenses
            Categorie category = categories.get(net.getKey().categoryId());
            String label = categoryLabel(category);
            totals.merge(label, net.getValue().abs(), BigDecimal::add);
            if (category != null) {
                categoryIdByLabel.putIfAbsent(label, category.getId());
            } else {
                uncategorizedLabels.add(label);
            }
        }

        BigDecimal grandTotal = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return totals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new DepenseParCategorieDTO(
                        e.getKey(),
                        categoryIdByLabel.get(e.getKey()),
                        uncategorizedLabels.contains(e.getKey()),
                        e.getValue(),
                        grandTotal.signum() == 0
                                ? BigDecimal.ZERO
                                : e.getValue().multiply(BigDecimal.valueOf(100))
                                        .divide(grandTotal, 1, RoundingMode.HALF_UP)))
                .toList();
    }

    /**
     * Transactions individuelles derriere une cellule d'un tableau du tableau
     * de bord, calculees avec exactement les memes regles que les agregats
     * (attributedTransactions : virements exclus, transactions futures
     * exclues, annualisees lissees sur leur annee) —
     * pour que le detail affiche au survol corresponde toujours au montant
     * affiche dans la cellule. Filtres tous optionnels et combinables :
     * - flow ("income"/"expense") : signe du net de la categorie sur le mois
     *   (voir netByCategoryMonth), remboursements compris dans le detail ;
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
        Periode.verifierOrdre(from, to);

        List<AttributedTransaction> transactions = attribution.attributedTransactions(from, to, accountId);
        Map<CategorieMois, BigDecimal> nets = AttributionTransactions.netByCategoryMonth(transactions);

        return transactions.stream()
                .filter(at -> matchesFlow(nets.get(CategorieMois.de(at)), flow))
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

    private boolean matchesFlow(BigDecimal net, String flow) {
        if (flow == null) return true;
        return INCOME.equals(flow) ? net.signum() > 0 : net.signum() < 0;
    }

    private boolean matchesCategory(Transaction t, Long categoryId, Long rootCategoryId, boolean uncategorized) {
        if (uncategorized) return t.getCategory() == null;
        if (categoryId != null) {
            return t.getCategory() != null && categoryId.equals(t.getCategory().getId());
        }
        if (rootCategoryId != null) {
            Categorie category = t.getCategory();
            if (category == null) return false;
            for (Categorie current = category; current != null; current = current.getParent()) {
                if (rootCategoryId.equals(current.getId())) return true;
            }
            return false;
        }
        return true;
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
    public List<EpargneMensuelleDTO> monthlySavings(int monthsBack, Long accountId, LocalDate referenceMonth) {
        if (monthsBack < 1 || monthsBack > MAX_SAVINGS_MONTHS) {
            throw new IllegalArgumentException("Le nombre de mois doit être compris entre 1 et " + MAX_SAVINGS_MONTHS + ".");
        }
        LocalDate firstOfCurrentMonth = AttributionTransactions.anchorMonth(referenceMonth);
        LocalDate currentMonthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);
        BigDecimal forecastAverage = avgMonthlyNetOverTrailing12Months(accountId, currentMonthStart);
        Map<YearMonth, BigDecimal> nets = monthlyNets(firstOfCurrentMonth.minusMonths(monthsBack), monthsBack, accountId);

        List<EpargneMensuelleDTO> result = new ArrayList<>();
        for (int i = monthsBack; i >= 1; i--) {
            LocalDate monthStart = firstOfCurrentMonth.minusMonths(i);
            boolean forecast = !monthStart.isBefore(currentMonthStart);
            BigDecimal net = forecast ? forecastAverage : nets.getOrDefault(YearMonth.from(monthStart), BigDecimal.ZERO);
            result.add(new EpargneMensuelleDTO(monthStart.toString(), monthLabel(monthStart), net, forecast));
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
     * derniers mois termines, calcule via {@link #monthlyNets} exactement comme
     * partout ailleurs dans le tableau de bord (virements, transactions
     * futures et marquees "exceptionnelles" exclus, voir isExcludedFromReports)
     * — plutot que l'ecart brut de solde entre compte, qui aurait inclus ces
     * mouvements ponctuels et rendu la tendance incoherente avec le "Net"
     * affiche ailleurs (cartes Revenus/Depenses, Resume mensuel).
     */
    public PrevisionSoldeDTO balanceForecast(Long accountId, LocalDate referenceMonth) {
        List<PointSoldeDTO> result = new ArrayList<>();
        LocalDate firstOfCurrentMonth = AttributionTransactions.anchorMonth(referenceMonth);
        LocalDate currentMonthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);
        // Charge les comptes une seule fois pour tout le calcul, plutot qu'a
        // chaque appel de balanceAsOf, qui les relisait a chaque fois.
        List<Compte> accounts = loadAccounts(accountId);

        BigDecimal avgMonthlyNet = avgMonthlyNetOverTrailing12Months(accountId, currentMonthStart);

        // Chaque point = le precedent + la variation mensuelle moyenne des
        // qu'on entre dans le previsionnel, en partant du dernier solde reel
        // connu. Ne part jamais du solde partiel d'aujourd'hui : sinon le
        // mois en cours melangerait donnees reelles partielles et moyenne,
        // avec un point previsionnel incoherent par rapport aux mois
        // suivants (ex : en baisse alors que la tendance moyenne monte).
        LocalDate windowStart = firstOfCurrentMonth.minusMonths(12);
        BigDecimal projected;
        Map<YearMonth, BigDecimal> historicalMovements;
        if (windowStart.isBefore(currentMonthStart)) {
            // Soldes reels de fin de mois : solde a la veille de la fenetre,
            // puis cumul des mouvements de chaque mois (une requete groupee)
            // au lieu d'une somme par compte et par mois.
            projected = balanceAsOf(windowStart.minusDays(1), accounts);
            LocalDate lastHistoricalDay = earliest(currentMonthStart, windowStart.plusMonths(24)).minusDays(1);
            historicalMovements = monthlyMovements(windowStart, lastHistoricalDay, accountId);
        } else {
            // Navigation loin dans le futur (au-dela de l'horizon usuel de 12
            // mois) : le debut de la fenetre affichee est lui-meme deja dans
            // le previsionnel. Sans ce rattrapage, la boucle ci-dessous ne
            // composerait qu'un seul mois de tendance sur ce premier point,
            // quel que soit le nombre de mois reellement ecoules depuis aujourd'hui.
            long monthsBeforeWindow = ChronoUnit.MONTHS.between(currentMonthStart, windowStart);
            projected = balanceAsOf(currentMonthStart.minusDays(1), accounts)
                    .add(avgMonthlyNet.multiply(BigDecimal.valueOf(monthsBeforeWindow)));
            historicalMovements = Map.of();
        }

        for (int i = -12; i <= 11; i++) {
            LocalDate monthStart = firstOfCurrentMonth.plusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            if (monthStart.isBefore(currentMonthStart)) {
                projected = projected.add(historicalMovements.getOrDefault(YearMonth.from(monthStart), BigDecimal.ZERO));
                result.add(point(monthEnd, projected, PointSoldeDTO.HISTORIQUE));
            } else {
                projected = projected.add(avgMonthlyNet);
                result.add(point(monthEnd, projected, PointSoldeDTO.PREVISIONNEL));
            }
        }

        return new PrevisionSoldeDTO(result, avgMonthlyNet);
    }

    /**
     * Variation mensuelle moyenne (revenus - depenses, voir {@link #monthlyNets})
     * sur les 12 VRAIS derniers mois termines, ancree sur currentMonthStart —
     * jamais sur une fenetre affichee/referenceMonth navigable. Base commune
     * du previsionnel pour balanceForecast (solde) et monthlySavings (epargne
     * mensuelle), pour que la projection reste la meme peu importe jusqu'ou
     * on navigue dans le futur (meme principe que categoryForecastAverages
     * pour monthlyCategoryAnalysis).
     */
    private BigDecimal avgMonthlyNetOverTrailing12Months(Long accountId, LocalDate currentMonthStart) {
        BigDecimal totalNet = monthlyNets(currentMonthStart.minusMonths(12), 12, accountId).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalNet.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    /**
     * Net (revenus - depenses, voir attributedTransactions) de chaque mois de
     * [firstMonth, firstMonth + monthCount[, en UN seul chargement des
     * transactions au lieu d'un par mois : l'attribution est additive mois
     * par mois (chaque transaction, et chaque part mensuelle d'une categorie
     * annuelle, tombe dans un seul mois). Mois sans transaction : absent.
     */
    private Map<YearMonth, BigDecimal> monthlyNets(LocalDate firstMonth, int monthCount, Long accountId) {
        Map<YearMonth, BigDecimal> nets = new HashMap<>();
        if (monthCount <= 0) return nets;
        LocalDate lastDay = firstMonth.plusMonths(monthCount).minusDays(1);
        for (AttributedTransaction t : attribution.attributedTransactions(firstMonth, lastDay, accountId)) {
            nets.merge(YearMonth.from(t.date()), t.amount(), BigDecimal::add);
        }
        return nets;
    }

    /**
     * Variation brute du solde (toutes transactions, virements compris, comme
     * balanceAsOf) de chaque mois de [from, to], en une requete groupee.
     * Toute transaction a un compte (Transaction#account non nul) : sans
     * filtre, le total couvre exactement tous les comptes.
     */
    private Map<YearMonth, BigDecimal> monthlyMovements(LocalDate from, LocalDate to, Long accountId) {
        List<Object[]> dailySums = accountId != null
                ? transactionRepository.sumAmountByAccountIdGroupedByDate(accountId, from, to)
                : transactionRepository.sumAmountGroupedByDate(from, to);
        Map<YearMonth, BigDecimal> movements = new HashMap<>();
        for (Object[] row : dailySums) {
            movements.merge(YearMonth.from((LocalDate) row[0]), (BigDecimal) row[1], BigDecimal::add);
        }
        return movements;
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
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
    public SynthesePeriodiqueDTO periodicExpensesSummary() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate threeMonthsAgo = today.minusMonths(3);
        List<Transaction> flagged = transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc()
                .stream()
                // Memes exclusions que tous les rapports. Une occurrence future n'a
                // pas encore eu lieu : elle n'est pas la "derniere" occurrence connue.
                .filter(t -> !AttributionTransactions.isExcludedFromReports(t, today))
                // Une depense periodique dont la derniere occurrence connue remonte a
                // plus de 3 mois est probablement obsolete (abonnement resilie, etc.) :
                // on ne l'affiche plus dans le tableau de bord.
                .filter(t -> !t.getDate().isBefore(threeMonthsAgo))
                .toList();

        List<Transaction> expenses = flagged.stream().filter(t -> t.getAmount().signum() < 0).toList();
        List<Transaction> revenues = flagged.stream().filter(t -> t.getAmount().signum() >= 0).toList();

        return new SynthesePeriodiqueDTO(summarizePeriodicGroup(expenses), summarizePeriodicGroup(revenues));
    }

    /** Une ligne par tiers (la plus recente occurrence) + le total, pour un groupe de transactions periodiques deja filtre par signe. */
    private GroupePeriodiqueDTO summarizePeriodicGroup(List<Transaction> transactions) {
        // Cle = id du tiers, pas son nom : deux tiers homonymes distingues par
        // leur montant ou leur motif (ex : deux lignes "Bouygues Telecom") sont
        // deux abonnements distincts, qu'un regroupement par nom fusionnait en
        // une seule ligne, sous-estimant le total.
        // Toutes les transactions de la date la plus recente (liste triee par
        // date decroissante) : une echeance divisee (capital + interets) compte
        // pour son montant complet, et non pour une seule de ses parties.
        Map<String, List<Transaction>> latestByPayee = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            String key = t.getPayee() != null ? "tiers-" + t.getPayee().getId() : "id-" + t.getId();
            List<Transaction> latest = latestByPayee.computeIfAbsent(key, k -> new ArrayList<>());
            if (latest.isEmpty() || latest.getFirst().getDate().equals(t.getDate())) latest.add(t);
        }

        List<ElementPeriodiqueDTO> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (List<Transaction> latest : latestByPayee.values()) {
            Transaction t = latest.getFirst();
            BigDecimal amount = latest.stream().map(x -> x.getAmount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
            items.add(new ElementPeriodiqueDTO(
                    t.getId(),
                    t.getPayee() != null ? t.getPayee().getName() : null,
                    t.getDate(),
                    amount));
            total = total.add(amount);
        }
        items.sort(Comparator.comparing(ElementPeriodiqueDTO::amount).reversed());

        return new GroupePeriodiqueDTO(items, total);
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
                ? List.of(accountRepository.findById(accountId)
                        .orElseThrow(() -> new NoSuchElementException("Compte introuvable : " + accountId)))
                : accountRepository.findAll();
    }

    private PointSoldeDTO point(LocalDate date, BigDecimal balance, String type) {
        return new PointSoldeDTO(date.toString(), monthLabel(date), balance, type);
    }

    private String monthLabel(LocalDate date) {
        String month = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.FRENCH);
        return Character.toUpperCase(month.charAt(0)) + month.substring(1) + " " + date.getYear();
    }

    private String categoryLabel(Categorie category) {
        if (category == null) return "Non catégorisé";
        if (category.getParent() != null) {
            return category.getParent().getName() + " : " + category.getName();
        }
        return category.getName();
    }


}

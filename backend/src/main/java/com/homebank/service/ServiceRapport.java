package com.homebank.service;

import com.homebank.dto.AnalyseMensuelleDTO;
import com.homebank.dto.DepenseParCategorieDTO;
import com.homebank.dto.ElementPeriodiqueDTO;
import com.homebank.dto.EpargneMensuelleDTO;
import com.homebank.dto.GroupePeriodiqueDTO;
import com.homebank.dto.LigneAnalyseDTO;
import com.homebank.dto.MoisAnalyseDTO;
import com.homebank.dto.PointSoldeDTO;
import com.homebank.dto.PrevisionSoldeDTO;
import com.homebank.dto.ResumeMoisAnalyseDTO;
import com.homebank.dto.ResumePeriodeDTO;
import com.homebank.dto.SynthesePeriodiqueDTO;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ServiceRapport {

    private static final String INCOME = "income";
    private static final String EXPENSE = "expense";

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

        LocalDate firstMonth = t.getDate().withDayOfMonth(1);
        for (int i = 0; i < ANNUALIZED_MONTHS; i++) {
            LocalDate monthStart = firstMonth.plusMonths(i);
            if (monthStart.isBefore(from) || monthStart.isAfter(to)) continue;
            result.add(new AttributedTransaction(t, monthStart, annualizedShare(t.getAmount(), i)));
        }
    }

    private static final int ANNUALIZED_MONTHS = 12;

    /**
     * Part du mois `monthIndex` (0 a 11) d'un montant lisse sur 12 mois : 1/12
     * arrondi au centime, sauf le dernier mois qui prend le reste. Sans ce
     * reste, 12 parts arrondies ne redonnaient pas le montant (100,00 ->
     * 12 x 8,33 = 99,96) et les totaux sur l'annee derivaient de quelques centimes.
     */
    static BigDecimal annualizedShare(BigDecimal amount, int monthIndex) {
        BigDecimal share = amount.divide(BigDecimal.valueOf(ANNUALIZED_MONTHS), 2, RoundingMode.HALF_UP);
        if (monthIndex < ANNUALIZED_MONTHS - 1) return share;
        return amount.subtract(share.multiply(BigDecimal.valueOf(ANNUALIZED_MONTHS - 1L)));
    }

    /**
     * Repartition des depenses (montants negatifs) par categorie racine,
     * sur une periode donnee. Renvoie une liste triee par montant decroissant.
     * Les virements entre comptes sont exclus.
     */
    public List<DepenseParCategorieDTO> expensesByCategory(LocalDate from, LocalDate to, Long accountId) {
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
    public ResumePeriodeDTO summary(LocalDate from, LocalDate to, Long accountId) {
        List<AttributedTransaction> transactions = attributedTransactions(from, to, accountId);

        BigDecimal income = transactions.stream()
                .map(AttributedTransaction::amount)
                .filter(a -> a.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expenses = transactions.stream()
                .map(AttributedTransaction::amount)
                .filter(a -> a.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ResumePeriodeDTO(income, expenses.abs(), income.add(expenses));
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
    public List<EpargneMensuelleDTO> monthlySavings(int monthsBack, Long accountId, LocalDate referenceMonth) {
        LocalDate firstOfCurrentMonth = anchorMonth(referenceMonth);
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
     * derniers mois termines, calcule via {@link #summary} exactement comme
     * partout ailleurs dans le tableau de bord (virements, transactions
     * futures et marquees "exceptionnelles" exclus, voir isExcludedFromReports)
     * — plutot que l'ecart brut de solde entre compte, qui aurait inclus ces
     * mouvements ponctuels et rendu la tendance incoherente avec le "Net"
     * affiche ailleurs (cartes Revenus/Depenses, Resume mensuel).
     */
    public PrevisionSoldeDTO balanceForecast(Long accountId, LocalDate referenceMonth) {
        List<PointSoldeDTO> result = new ArrayList<>();
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
     * Variation mensuelle moyenne (revenus - depenses, voir {@link #summary})
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
     * Net (revenus - depenses, memes regles que summary) de chaque mois de
     * [firstMonth, firstMonth + monthCount[, en UN seul chargement des
     * transactions au lieu d'un par mois : l'attribution est additive mois
     * par mois (chaque transaction, et chaque part mensuelle d'une categorie
     * annuelle, tombe dans un seul mois). Mois sans transaction : absent.
     */
    private Map<YearMonth, BigDecimal> monthlyNets(LocalDate firstMonth, int monthCount, Long accountId) {
        Map<YearMonth, BigDecimal> nets = new HashMap<>();
        if (monthCount <= 0) return nets;
        LocalDate lastDay = firstMonth.plusMonths(monthCount).minusDays(1);
        for (AttributedTransaction t : attributedTransactions(firstMonth, lastDay, accountId)) {
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

        return new SynthesePeriodiqueDTO(summarizePeriodicGroup(expenses), summarizePeriodicGroup(revenues));
    }

    /** Une ligne par tiers (la plus recente occurrence) + le total, pour un groupe de transactions periodiques deja filtre par signe. */
    private GroupePeriodiqueDTO summarizePeriodicGroup(List<Transaction> transactions) {
        Map<String, Transaction> latestByPayee = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            String key = (t.getPayee() != null ? t.getPayee().getName().toLowerCase(Locale.ROOT) : "id-" + t.getId());
            latestByPayee.putIfAbsent(key, t);
        }

        List<ElementPeriodiqueDTO> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : latestByPayee.values()) {
            items.add(new ElementPeriodiqueDTO(
                    t.getId(),
                    t.getPayee() != null ? t.getPayee().getName() : null,
                    t.getDate(),
                    t.getAmount().abs()));
            total = total.add(t.getAmount().abs());
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

    /**
     * Tableau d'analyse des revenus et dépenses par catégorie/sous-catégorie
     * sur les 12 mois entièrement terminés précédant referenceMonth (dernier
     * mois inclus) — ou les 12 derniers mois déjà terminés si referenceMonth
     * est omis. Les transactions annualisées y sont lissées sur leur année
     * (voir attributedTransactions), ce qui impose une agrégation côté
     * backend plutôt qu'en base.
     */
    public AnalyseMensuelleDTO monthlyCategoryAnalysis(Long accountId, LocalDate referenceMonth) {
        LocalDate firstOfCurrentMonth = anchorMonth(referenceMonth);
        LocalDate from = firstOfCurrentMonth.minusMonths(12);
        LocalDate to = firstOfCurrentMonth.minusDays(1);
        // Mois calendaire reel en cours (independant de referenceMonth, qui
        // peut avoir ete navigue vers le futur) : sert a distinguer les mois
        // "reels" des mois "previsionnels" ci-dessous, meme convention que
        // balanceForecast (currentMonthStart la-bas).
        LocalDate currentMonthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);

        List<MoisAnalyseDTO> months = buildMonthsWindow(from, firstOfCurrentMonth, currentMonthStart);
        Map<String, BigDecimal> monthlyIncomeTotals = zeroedMonthlyTotals(months);
        Map<String, BigDecimal> monthlyExpenseTotals = zeroedMonthlyTotals(months);

        // Clé stable : catégorie (ou catégorie racine + sous-catégorie) + type de flux.
        Map<String, LigneEnConstruction> rows = buildCategoryMonthRows(
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
            if (key.equals(totalKey(INCOME)) || key.equals(totalKey(EXPENSE)) || rows.containsKey(key)) continue;
            String[] parts = key.split("\\|", 2);
            Categorie category = forecast.categoriesByKey().get(parts[0]);
            rows.put(key, LigneEnConstruction.pourCategorie(key, category, parts[1], months));
        }

        // Construit la hiérarchie : racines d'abord, sous-catégories juste après.
        List<LigneAnalyseDTO> incomeRows = extractSortedRows(rows, INCOME, currentMonthStart, forecast.averages());
        List<LigneAnalyseDTO> expenseRows = extractSortedRows(rows, EXPENSE, currentMonthStart, forecast.averages());
        LigneAnalyseDTO incomeTotal = totalAnalysisRow("Total revenus", INCOME, months, monthlyIncomeTotals,
                currentMonthStart, forecast.averages().getOrDefault(totalKey(INCOME), BigDecimal.ZERO));
        LigneAnalyseDTO expenseTotal = totalAnalysisRow("Total dépenses", EXPENSE, months, monthlyExpenseTotals,
                currentMonthStart, forecast.averages().getOrDefault(totalKey(EXPENSE), BigDecimal.ZERO));
        // Reutilise les valeurs de incomeTotal/expenseTotal (deja completees
        // par le previsionnel ci-dessus) plutot que les totaux bruts, pour
        // que le resume mensuel (Revenus/Depenses/Solde) affiche lui aussi
        // le previsionnel sur les mois futurs au lieu de zeros.
        List<ResumeMoisAnalyseDTO> monthlySummary = buildMonthlySummary(months, incomeTotal.values(), expenseTotal.values());

        return new AnalyseMensuelleDTO(from, to, months, incomeRows, incomeTotal, expenseRows, expenseTotal, monthlySummary);
    }

    /** Cle de la ligne de total d'un type de flux, partagee avec les moyennes previsionnelles (voir categoryForecastAverages). */
    private static String totalKey(String type) {
        return "total-" + type;
    }

    /**
     * Ligne categorie/flux (ou total) en cours d'agregation : ses valeurs
     * mensuelles restent mutables pendant le cumul des transactions, puis la
     * ligne est figee en LigneAnalyseDTO une fois completee par le
     * previsionnel (voir completeAnalysisRow).
     */
    private static final class LigneEnConstruction {
        private final String key;
        private final Long categoryId;
        private final String name;
        private final Long parentId;
        private final String parentName;
        private final String type;
        private final Map<String, BigDecimal> values;

        private LigneEnConstruction(String key, Long categoryId, String name, Long parentId, String parentName,
                                    String type, Map<String, BigDecimal> values) {
            this.key = key;
            this.categoryId = categoryId;
            this.name = name;
            this.parentId = parentId;
            this.parentName = parentName;
            this.type = type;
            this.values = values;
        }

        /** Ligne vierge (valeurs a zero sur tous les mois) pour une categorie/type de flux (voir buildCategoryMonthRows). */
        static LigneEnConstruction pourCategorie(String key, Categorie category, String type, List<MoisAnalyseDTO> months) {
            Categorie parent = category != null ? category.getParent() : null;
            // Les transactions sans catégorie restent visibles dans le tableau.
            return new LigneEnConstruction(
                    key,
                    category != null ? category.getId() : null,
                    category != null ? category.getName() : "Sans catégorie",
                    parent != null ? parent.getId() : null,
                    parent != null ? parent.getName() : null,
                    type,
                    zeroedMonthlyTotals(months));
        }

        /** Ligne de total (sans categorie) d'un type de flux, valeurs deja calculees. */
        static LigneEnConstruction pourTotal(String name, String type, Map<String, BigDecimal> values) {
            return new LigneEnConstruction(totalKey(type), null, name, null, null, type, values);
        }

        int level() {
            return parentId == null ? 0 : 1;
        }

        void addMonthAmount(String monthKey, BigDecimal amount) {
            values.merge(monthKey, amount.abs(), BigDecimal::add);
        }
    }

    /** Un point par mois entierement termine de [from, firstOfCurrentMonth[ (voir monthlyCategoryAnalysis), marque "forecast" a partir du mois calendaire reel en cours (currentMonthStart). */
    private List<MoisAnalyseDTO> buildMonthsWindow(LocalDate from, LocalDate firstOfCurrentMonth, LocalDate currentMonthStart) {
        List<MoisAnalyseDTO> months = new ArrayList<>();
        // "MMM" en français porte déjà son point d'abréviation ("nov.", "janv.")
        // et n'en met pas aux mois courts ("mai", "juin") : ne pas en ajouter un.
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.FRENCH);
        for (LocalDate month = from; month.isBefore(firstOfCurrentMonth); month = month.plusMonths(1)) {
            months.add(new MoisAnalyseDTO(
                    month.toString(),
                    capitalize(month.format(monthFormatter)),
                    month.getYear(),
                    month.getMonthValue(),
                    !month.isBefore(currentMonthStart)));
        }
        return months;
    }

    private static Map<String, BigDecimal> zeroedMonthlyTotals(List<MoisAnalyseDTO> months) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (MoisAnalyseDTO m : months) {
            totals.put(m.key(), BigDecimal.ZERO);
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
    private Map<String, LigneEnConstruction> buildCategoryMonthRows(
            List<AttributedTransaction> transactions,
            List<MoisAnalyseDTO> months,
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

        Map<String, LigneEnConstruction> rows = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : perCategoryMonth.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String catKey = parts[0];
            String monthKey = parts[1];
            BigDecimal amount = entry.getValue();
            Categorie category = categoryByKey.get(catKey);

            String type = amount.signum() > 0 ? INCOME : EXPENSE;
            LigneEnConstruction row = rows.computeIfAbsent(
                    catKey + "|" + type,
                    key -> LigneEnConstruction.pourCategorie(key, category, type, months));

            row.addMonthAmount(monthKey, amount);
            mergeMonthlyTotal(amount, monthKey, monthlyIncomeTotals, monthlyExpenseTotals);
        }
        return rows;
    }

    /**
     * averages : moyenne mensuelle par categorie/flux (meme cle
     * "catId|income|expense" que les lignes de monthlyCategoryAnalysis, voir
     * buildCategoryMonthRows), plus deux entrees "total-income"/"total-expense"
     * (meme cle que les lignes de total, voir totalKey) pour la
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
        totals.put(totalKey(INCOME), totalIncome);
        totals.put(totalKey(EXPENSE), totalExpense);

        Map<String, BigDecimal> averages = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            averages.put(entry.getKey(), entry.getValue().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
        }
        return new CategoryForecast(averages, categoriesByKey);
    }

    private static void mergeMonthlyTotal(BigDecimal amount, String monthKey,
                                           Map<String, BigDecimal> monthlyIncomeTotals, Map<String, BigDecimal> monthlyExpenseTotals) {
        if (amount.signum() > 0) {
            monthlyIncomeTotals.merge(monthKey, amount, BigDecimal::add);
        } else if (amount.signum() < 0) {
            monthlyExpenseTotals.merge(monthKey, amount.abs(), BigDecimal::add);
        }
    }

    /** Ordre d'affichage : par categorie racine (nom), la racine avant ses sous-categories, puis par nom. */
    private static final Comparator<LigneEnConstruction> CATEGORY_ROW_ORDER = Comparator
            .comparing((LigneEnConstruction r) -> Objects.toString(r.parentName, ""), String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(LigneEnConstruction::level)
            .thenComparing(r -> Objects.toString(r.name, ""), String.CASE_INSENSITIVE_ORDER);

    private List<LigneAnalyseDTO> extractSortedRows(
            Map<String, LigneEnConstruction> rows, String type, LocalDate currentMonthStart, Map<String, BigDecimal> forecastAverages) {
        return rows.values().stream()
                .filter(r -> type.equals(r.type))
                .sorted(CATEGORY_ROW_ORDER)
                .map(r -> completeAnalysisRow(r, currentMonthStart, forecastAverages.getOrDefault(r.key, BigDecimal.ZERO)))
                .toList();
    }

    private List<ResumeMoisAnalyseDTO> buildMonthlySummary(
            List<MoisAnalyseDTO> months, Map<String, BigDecimal> monthlyIncomeTotals, Map<String, BigDecimal> monthlyExpenseTotals) {
        List<ResumeMoisAnalyseDTO> monthlySummary = new ArrayList<>();
        for (MoisAnalyseDTO m : months) {
            BigDecimal income = monthlyIncomeTotals.getOrDefault(m.key(), BigDecimal.ZERO);
            BigDecimal expenses = monthlyExpenseTotals.getOrDefault(m.key(), BigDecimal.ZERO);
            monthlySummary.add(new ResumeMoisAnalyseDTO(m.key(), income, expenses, income.subtract(expenses), m.forecast()));
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
    private LigneAnalyseDTO completeAnalysisRow(LigneEnConstruction row, LocalDate currentMonthStart, BigDecimal forecastAverage) {
        Map<String, BigDecimal> values = row.values;
        for (Map.Entry<String, BigDecimal> entry : values.entrySet()) {
            if (!LocalDate.parse(entry.getKey()).isBefore(currentMonthStart)) {
                entry.setValue(forecastAverage);
            }
        }

        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = values.isEmpty()
                ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
        return new LigneAnalyseDTO(row.key, row.categoryId, row.name, row.parentId, row.parentName,
                row.level(), row.type, values, average, total);
    }

    private LigneAnalyseDTO totalAnalysisRow(
            String name,
            String type,
            List<MoisAnalyseDTO> months,
            Map<String, BigDecimal> monthlyTotals,
            LocalDate currentMonthStart,
            BigDecimal forecastAverage) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (MoisAnalyseDTO month : months) {
            values.put(month.key(), monthlyTotals.getOrDefault(month.key(), BigDecimal.ZERO));
        }
        return completeAnalysisRow(LigneEnConstruction.pourTotal(name, type, values), currentMonthStart, forecastAverage);
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.FRENCH) + value.substring(1);
    }

}

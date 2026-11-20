package com.homebank.service;

import com.homebank.dto.AnalyseMensuelleDTO;
import com.homebank.dto.LigneAnalyseDTO;
import com.homebank.dto.MoisAnalyseDTO;
import com.homebank.dto.ResumeMoisAnalyseDTO;
import com.homebank.model.Categorie;
import com.homebank.repository.DepotTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

import com.homebank.service.AttributionTransactions.AttributedTransaction;
import com.homebank.service.AttributionTransactions.CategorieMois;

import static com.homebank.service.AttributionTransactions.EXPENSE;
import static com.homebank.service.AttributionTransactions.INCOME;

/**
 * Tableau d'analyse mensuelle du tableau de bord : revenus et depenses par
 * categorie et sous-categorie sur 12 mois, moyenne, total, mois
 * previsionnels et comparaison aux memes 12 mois de l'annee precedente.
 * Lecture seule.
 */
@Service
@Transactional(readOnly = true)
public class ServiceAnalyseMensuelle {

    private final DepotTransaction transactionRepository;
    private final AttributionTransactions attribution;

    public ServiceAnalyseMensuelle(DepotTransaction transactionRepository, AttributionTransactions attribution) {
        this.transactionRepository = transactionRepository;
        this.attribution = attribution;
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
        LocalDate firstOfCurrentMonth = AttributionTransactions.anchorMonth(referenceMonth);
        LocalDate from = firstOfCurrentMonth.minusMonths(12);
        LocalDate to = firstOfCurrentMonth.minusDays(1);
        // Mois calendaire reel en cours (independant de referenceMonth, qui
        // peut avoir ete navigue vers le futur) : sert a distinguer les mois
        // "reels" des mois "previsionnels" ci-dessous, meme convention que
        // balanceForecast (currentMonthStart la-bas).
        LocalDate currentMonthStart = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);
        // Meme fenetre decalee d'un an : comparaison avec l'annee precedente.
        LocalDate previousFrom = from.minusMonths(12);
        LocalDate previousTo = from.minusDays(1);

        List<MoisAnalyseDTO> months = buildMonthsWindow(from, firstOfCurrentMonth, currentMonthStart);
        List<MoisAnalyseDTO> previousMonths = buildMonthsWindow(previousFrom, from, currentMonthStart);
        Map<String, BigDecimal> monthlyIncomeTotals = zeroedMonthlyTotals(months);
        Map<String, BigDecimal> monthlyExpenseTotals = zeroedMonthlyTotals(months);
        Map<String, BigDecimal> previousIncomeTotals = zeroedMonthlyTotals(previousMonths);
        Map<String, BigDecimal> previousExpenseTotals = zeroedMonthlyTotals(previousMonths);

        // Une seule lecture pour les deux fenetres (24 mois), partagee selon le
        // mois d'attribution : memes regles des deux cotes (lissage des
        // categories annuelles, exclusions), sans seconde requete.
        List<AttributedTransaction> currentTransactions = new ArrayList<>();
        List<AttributedTransaction> previousTransactions = new ArrayList<>();
        for (AttributedTransaction at : attribution.attributedTransactions(previousFrom, to, accountId)) {
            (at.date().isBefore(from) ? previousTransactions : currentTransactions).add(at);
        }

        // Clé stable : catégorie (ou catégorie racine + sous-catégorie) + type de flux.
        Map<String, LigneEnConstruction> rows = buildCategoryMonthRows(
                currentTransactions, months, monthlyIncomeTotals, monthlyExpenseTotals);
        Map<String, LigneEnConstruction> previousRows = buildCategoryMonthRows(
                previousTransactions, previousMonths, previousIncomeTotals, previousExpenseTotals);

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
        // Categorie active l'an dernier mais plus du tout cette annee : ligne
        // vierge, pour que sa disparition se voie dans la comparaison et que
        // les totaux precedents des lignes retombent sur celui du total.
        for (LigneEnConstruction previousRow : previousRows.values()) {
            rows.computeIfAbsent(previousRow.key, key -> previousRow.viergeSur(months));
        }

        // Total precedent d'une ligne, mois futurs completes par le meme
        // previsionnel que la fenetre affichee (fenetre precedente elle aussi
        // dans le futur, en naviguant loin) : comparaison a regles egales.
        Map<String, BigDecimal> forecastAverages = forecast.averages();
        Function<String, BigDecimal> previousTotalOf = key -> sum(completedValues(
                previousRows.containsKey(key) ? previousRows.get(key).values : Map.of(),
                previousMonths, forecastAverages.getOrDefault(key, BigDecimal.ZERO)).values());

        // Construit la hiérarchie : racines d'abord, sous-catégories juste après.
        List<LigneAnalyseDTO> incomeRows = extractSortedRows(rows, INCOME, currentMonthStart, forecastAverages, previousTotalOf);
        List<LigneAnalyseDTO> expenseRows = extractSortedRows(rows, EXPENSE, currentMonthStart, forecastAverages, previousTotalOf);
        BigDecimal incomeForecast = forecastAverages.getOrDefault(totalKey(INCOME), BigDecimal.ZERO);
        BigDecimal expenseForecast = forecastAverages.getOrDefault(totalKey(EXPENSE), BigDecimal.ZERO);
        Map<String, BigDecimal> previousIncome = completedValues(previousIncomeTotals, previousMonths, incomeForecast);
        Map<String, BigDecimal> previousExpenses = completedValues(previousExpenseTotals, previousMonths, expenseForecast);
        LigneAnalyseDTO incomeTotal = totalAnalysisRow("Total revenus", INCOME, months, monthlyIncomeTotals,
                currentMonthStart, incomeForecast, sum(previousIncome.values()));
        LigneAnalyseDTO expenseTotal = totalAnalysisRow("Total dépenses", EXPENSE, months, monthlyExpenseTotals,
                currentMonthStart, expenseForecast, sum(previousExpenses.values()));
        // Reutilise les valeurs de incomeTotal/expenseTotal (deja completees
        // par le previsionnel ci-dessus) plutot que les totaux bruts, pour
        // que le resume mensuel (Revenus/Depenses/Solde) affiche lui aussi
        // le previsionnel sur les mois futurs au lieu de zeros.
        List<ResumeMoisAnalyseDTO> monthlySummary = buildMonthlySummary(months, incomeTotal.values(), expenseTotal.values());
        List<ResumeMoisAnalyseDTO> previousSummary = buildMonthlySummary(previousMonths, previousIncome, previousExpenses);
        LocalDate historyStart = accountId != null
                ? transactionRepository.findEarliestDateByAccountId(accountId)
                : transactionRepository.findEarliestDate();

        return new AnalyseMensuelleDTO(from, to, months, incomeRows, incomeTotal, expenseRows, expenseTotal, monthlySummary,
                previousFrom, previousTo, historyStart, previousSummary);
    }

    /**
     * Valeurs d'une ligne mois par mois sur une fenetre, mois previsionnels
     * (voir MoisAnalyseDTO#forecast) remplaces par forecastAverage : meme regle
     * que completeAnalysisRow, sans modifier les valeurs reelles recues.
     */
    private static Map<String, BigDecimal> completedValues(
            Map<String, BigDecimal> actualValues, List<MoisAnalyseDTO> months, BigDecimal forecastAverage) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (MoisAnalyseDTO month : months) {
            values.put(month.key(), month.forecast() ? forecastAverage : actualValues.getOrDefault(month.key(), BigDecimal.ZERO));
        }
        return values;
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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

        /** Ligne vierge de meme categorie et meme flux sur une autre fenetre de mois (voir monthlyCategoryAnalysis). */
        LigneEnConstruction viergeSur(List<MoisAnalyseDTO> months) {
            return new LigneEnConstruction(key, categoryId, name, parentId, parentName, type, zeroedMonthlyTotals(months));
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

        Map<Long, Categorie> categories = AttributionTransactions.categoriesById(transactions);
        Map<String, LigneEnConstruction> rows = new LinkedHashMap<>();
        for (Map.Entry<CategorieMois, BigDecimal> entry : AttributionTransactions.netByCategoryMonth(transactions).entrySet()) {
            String monthKey = entry.getKey().month().atDay(1).toString();
            BigDecimal amount = entry.getValue();
            Categorie category = categories.get(entry.getKey().categoryId());

            String type = amount.signum() > 0 ? INCOME : EXPENSE;
            LigneEnConstruction row = rows.computeIfAbsent(
                    entry.getKey().categoryKey() + "|" + type,
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
        List<AttributedTransaction> transactions = attribution.attributedTransactions(from, to, accountId);
        Map<Long, Categorie> categories = AttributionTransactions.categoriesById(transactions);
        // Memes nets que les lignes affichees (voir buildCategoryMonthRows).
        for (Map.Entry<CategorieMois, BigDecimal> entry : AttributionTransactions.netByCategoryMonth(transactions).entrySet()) {
            String catKey = entry.getKey().categoryKey();
            categoriesByKey.putIfAbsent(catKey, categories.get(entry.getKey().categoryId()));
            BigDecimal amount = entry.getValue();
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
            Map<String, LigneEnConstruction> rows, String type, LocalDate currentMonthStart,
            Map<String, BigDecimal> forecastAverages, Function<String, BigDecimal> previousTotalOf) {
        return rows.values().stream()
                .filter(r -> type.equals(r.type))
                .sorted(CATEGORY_ROW_ORDER)
                .map(r -> completeAnalysisRow(r, currentMonthStart, forecastAverages.getOrDefault(r.key, BigDecimal.ZERO),
                        previousTotalOf.apply(r.key)))
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
     * montre a l'ecran. previousTotal : total de la ligne sur les 12 mois
     * precedents (comparaison avec l'annee precedente), transmis tel quel.
     */
    private LigneAnalyseDTO completeAnalysisRow(LigneEnConstruction row, LocalDate currentMonthStart,
                                                BigDecimal forecastAverage, BigDecimal previousTotal) {
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
                row.level(), row.type, values, average, total, previousTotal);
    }

    private LigneAnalyseDTO totalAnalysisRow(
            String name,
            String type,
            List<MoisAnalyseDTO> months,
            Map<String, BigDecimal> monthlyTotals,
            LocalDate currentMonthStart,
            BigDecimal forecastAverage,
            BigDecimal previousTotal) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (MoisAnalyseDTO month : months) {
            values.put(month.key(), monthlyTotals.getOrDefault(month.key(), BigDecimal.ZERO));
        }
        return completeAnalysisRow(LigneEnConstruction.pourTotal(name, type, values), currentMonthStart,
                forecastAverage, previousTotal);
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.FRENCH) + value.substring(1);
    }
}

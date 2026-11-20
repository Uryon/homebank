package com.homebank.service;

import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Onglet "Periodiques" de la page Transactions : detection des series de
 * transactions qui semblent periodiques (meme tiers, montant proche,
 * intervalles reguliers) et refus definitif d'une suggestion. Lecture seule
 * sur les transactions, hormis le marqueur periodicSuggestionIgnored.
 */
@Service
public class ServiceSuggestionPeriodique {

    private static final Logger log = LoggerFactory.getLogger(ServiceSuggestionPeriodique.class);

    /**
     * Nombre minimum d'occurrences (meme tiers, montant proche) pour qu'un
     * groupe soit suggere comme periodique : en dessous, le rapprochement est
     * trop peu fiable (pourrait n'etre qu'une coincidence).
     */
    private static final int PERIODIC_MIN_OCCURRENCES = 3;

    /** Tolerance sur le montant a l'interieur d'un groupe (ex : facture d'energie qui varie legerement). */
    private static final BigDecimal PERIODIC_AMOUNT_TOLERANCE = new BigDecimal("1.10");

    /**
     * Frequences reconnues pour la regularite des intervalles entre
     * occurrences : {cible en jours, tolerance en jours}. Une serie n'est
     * retenue que si TOUS ses intervalles tombent dans la tolerance d'une
     * seule et meme frequence (le prelevement reel peut varier de quelques
     * jours autour de la date "ideale" : weekend, jour ferie, mois plus
     * court...). Mensuel/trimestriel/annuel sont les frequences demandees ;
     * une serie a intervalles quelconques (ex : plusieurs achats du meme
     * montant a des dates sans rapport) ne matche aucune d'elles et est donc
     * ecartee.
     */
    private static final int[][] PERIODIC_FREQUENCIES_DAYS = {
            {30, 7},    // mensuel
            {91, 10},   // trimestriel
            {365, 20},  // annuel
    };

    /**
     * Nombre maximum d'occurrences consecutives manquees tolerees dans un
     * intervalle (ex : mois sans prelevement, paiement en retard groupe avec
     * le suivant). Au-dela, un ecart aussi grand a plus de chances d'etre une
     * coincidence qu'une vraie periodicite avec trou.
     */
    private static final int PERIODIC_MAX_MISSED_OCCURRENCES = 3;

    private final DepotTransaction transactionRepository;
    private final MappeurTransaction transactionMapper;

    public ServiceSuggestionPeriodique(DepotTransaction transactionRepository, MappeurTransaction transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.transactionMapper = transactionMapper;
    }

    /**
     * Suggere des groupes de transactions qui semblent periodiques : meme
     * tiers, montant proche (tolerance +/-10%), au moins
     * PERIODIC_MIN_OCCURRENCES occurrences, ET dates reparties a intervalle
     * regulier (mensuel/trimestriel/annuel a tolerance pres) — sans quoi une
     * simple coincidence de montant (ex : plusieurs achats ponctuels du meme
     * montant) serait a tort suggeree comme periodique. Ignore les
     * transactions deja marquees periodiques (deja acceptees), les
     * transactions dont la suggestion a ete refusee par l'utilisateur (voir
     * Transaction#periodicSuggestionIgnored / ignorePeriodicSuggestion
     * ci-dessous) et les virements internes (jamais une depense/revenu
     * periodique au sens metier).
     */
    @Transactional(readOnly = true)
    public List<SuggestionPeriodiqueDTO> findPeriodicSuggestions() {
        List<Transaction> candidates = transactionRepository.findAllByOrderByDateDescIdDesc().stream()
                .filter(t -> !t.isPeriodicExpense())
                .filter(t -> !t.isPeriodicSuggestionIgnored())
                .filter(t -> !t.isInternalTransfer() && !t.isBalanceAdjustment())
                .filter(t -> t.getPayee() != null)
                .toList();

        Map<Long, List<Transaction>> byPayee = candidates.stream()
                .collect(Collectors.groupingBy(t -> t.getPayee().getId()));

        List<SuggestionPeriodiqueDTO> suggestions = new ArrayList<>();
        for (List<Transaction> group : byPayee.values()) {
            // Depenses et revenus d'un meme tiers sont deux series distinctes :
            // melanges, -50 et +50 donnaient une serie au montant moyen proche de 0.
            group.stream()
                    .collect(Collectors.groupingBy(t -> t.getAmount().signum()))
                    .values()
                    .forEach(sameSign -> addSuggestions(sameSign, suggestions));
        }

        suggestions.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        return suggestions;
    }

    /** Ajoute une suggestion par groupe de montants proches et d'intervalles reguliers. */
    private void addSuggestions(List<Transaction> sameSign, List<SuggestionPeriodiqueDTO> suggestions) {
        if (sameSign.size() < PERIODIC_MIN_OCCURRENCES) return;
        for (List<Transaction> amountGroup : groupByToleranteAmount(sameSign)) {
            if (amountGroup.size() >= PERIODIC_MIN_OCCURRENCES && hasRegularInterval(amountGroup)) {
                suggestions.add(buildPeriodicSuggestion(amountGroup));
            }
        }
    }

    /**
     * Vrai si les dates du groupe (deja rapproche par tiers + montant) se
     * repetent selon un intervalle globalement regulier : il existe une
     * frequence (voir PERIODIC_FREQUENCIES_DAYS) dont TOUS les intervalles
     * consecutifs (dates triees) sont proches d'un multiple entier de sa
     * cible, a sa tolerance pres (voir {@link #matchesFrequencyWithGaps}).
     */
    private boolean hasRegularInterval(List<Transaction> transactions) {
        List<LocalDate> dates = transactions.stream()
                .map(Transaction::getDate)
                .sorted()
                .toList();

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            intervals.add(ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
        }

        for (int[] frequency : PERIODIC_FREQUENCIES_DAYS) {
            int target = frequency[0];
            int tolerance = frequency[1];
            if (intervals.stream().allMatch(days -> matchesFrequencyWithGaps(days, target, tolerance))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vrai si l'intervalle (en jours) correspond a la frequence cible, ou a
     * un multiple entier proche de celle-ci — une ou plusieurs occurrences
     * manquees (mois sans prelevement, paiement en retard qui se retrouve
     * groupe avec le suivant...). Plafonne a PERIODIC_MAX_MISSED_OCCURRENCES
     * occurrences manquees pour eviter de faire matcher un ecart demesure par
     * pure coincidence.
     */
    private boolean matchesFrequencyWithGaps(long days, int target, int tolerance) {
        long occurrences = Math.clamp(Math.round((double) days / target), 1, PERIODIC_MAX_MISSED_OCCURRENCES + 1L);
        return Math.abs(days - occurrences * target) <= tolerance;
    }

    /**
     * Regroupe des transactions d'un meme tiers par montant proche : trie par
     * montant absolu croissant, puis constitue des groupes successifs tant
     * que chaque nouveau montant reste dans la tolerance de la moyenne du
     * groupe courant. La moyenne (plutot qu'un minimum fige au premier
     * element du cluster) suit le groupe au fur et a mesure des ajouts : elle
     * absorbe aussi bien une derive progressive (facture d'energie qui
     * augmente legerement mois apres mois) qu'une fluctuation dans les deux
     * sens autour d'une valeur centrale (revenu variable, ex : commissions).
     */
    private List<List<Transaction>> groupByToleranteAmount(List<Transaction> transactions) {
        List<Transaction> sorted = transactions.stream()
                .sorted(Comparator.comparing(t -> t.getAmount().abs()))
                .toList();

        List<List<Transaction>> clusters = new ArrayList<>();
        List<Transaction> current = new ArrayList<>();
        BigDecimal clusterSum = BigDecimal.ZERO;

        for (Transaction t : sorted) {
            BigDecimal abs = t.getAmount().abs();
            if (current.isEmpty()) {
                current.add(t);
                clusterSum = abs;
                continue;
            }
            BigDecimal clusterAvg = clusterSum.divide(BigDecimal.valueOf(current.size()), 10, RoundingMode.HALF_UP);
            if (abs.compareTo(clusterAvg.multiply(PERIODIC_AMOUNT_TOLERANCE)) <= 0) {
                current.add(t);
                clusterSum = clusterSum.add(abs);
            } else {
                clusters.add(current);
                current = new ArrayList<>();
                current.add(t);
                clusterSum = abs;
            }
        }
        if (!current.isEmpty()) clusters.add(current);
        return clusters;
    }

    private SuggestionPeriodiqueDTO buildPeriodicSuggestion(List<Transaction> transactions) {
        List<Transaction> sorted = transactions.stream()
                .sorted(Comparator.comparing(Transaction::getDate))
                .toList();

        long totalDays = 0;
        for (int i = 1; i < sorted.size(); i++) {
            totalDays += ChronoUnit.DAYS.between(sorted.get(i - 1).getDate(), sorted.get(i).getDate());
        }
        int averageIntervalDays = sorted.size() > 1
                ? (int) Math.round((double) totalDays / (sorted.size() - 1))
                : 0;

        BigDecimal averageAmount = sorted.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(sorted.size()), 2, RoundingMode.HALF_UP);

        return SuggestionPeriodiqueDTO.builder()
                .payee(sorted.getLast().getPayee().getName())
                .count(sorted.size())
                .averageAmount(averageAmount)
                .averageIntervalDays(averageIntervalDays)
                .transactions(sorted.stream().map(transactionMapper::toDTO).toList())
                .build();
    }

    /**
     * Marque les transactions passees comme "non periodiques" pour de bon
     * (voir Transaction#periodicSuggestionIgnored) : elles seront exclues de
     * findPeriodicSuggestions, pour que refuser une suggestion depuis
     * l'onglet "Periodiques" soit definitif et ne revienne plus a chaque
     * visite.
     */
    @Transactional
    public void ignorePeriodicSuggestion(List<Long> ids) {
        setPeriodicSuggestionIgnored(ids, true);
    }

    /** Inverse de ignorePeriodicSuggestion (bouton « Annuler ») : la suggestion peut de nouveau etre proposee. */
    @Transactional
    public void unignorePeriodicSuggestion(List<Long> ids) {
        setPeriodicSuggestionIgnored(ids, false);
    }

    private void setPeriodicSuggestionIgnored(List<Long> ids, boolean ignored) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction sélectionnée.");
        }
        List<Transaction> transactions = transactionRepository.findAllById(ids);
        transactions.forEach(t -> t.setPeriodicSuggestionIgnored(ignored));
        transactionRepository.saveAll(transactions);
        log.info("{} de {} transaction(s) comme suggestion periodique ignoree", ignored ? "Marquage" : "Retrait du marquage", transactions.size());
    }
}

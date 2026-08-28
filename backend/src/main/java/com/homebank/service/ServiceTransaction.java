package com.homebank.service;

import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.dto.TransactionBulkUpdateDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.dto.DemandeVirementDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.ModePaiement;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class ServiceTransaction {

    private static final Logger log = LoggerFactory.getLogger(ServiceTransaction.class);

    private final DepotTransaction transactionRepository;
    private final ServiceCompte accountService;
    private final ServiceCategorie categoryService;
    private final ServiceTiers payeeService;
    private final MappeurTransaction transactionMapper;

    public ServiceTransaction(
            DepotTransaction transactionRepository,
            ServiceCompte accountService,
            ServiceCategorie categoryService,
            ServiceTiers payeeService,
            MappeurTransaction transactionMapper) {

        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.payeeService = payeeService;
        this.transactionMapper = transactionMapper;
    }

    public List<TransactionDTO> findAll() {
        return transactionRepository.findAllByOrderByDateDescIdDesc()
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public List<TransactionDTO> findByAccount(Long accountId) {
        return transactionRepository
                .findByAccount_IdOrderByDateDescIdDesc(accountId)
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public List<TransactionDTO> findByPeriod(LocalDate from, LocalDate to) {
        return transactionRepository
                .findByDateBetween(from, to)
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    /**
     * Recherche de transactions par montant, avec un operateur de comparaison
     * (">", "<" ou "=") et optionnellement un compte pour restreindre la
     * recherche — utilisee par le champ de recherche de l'onglet Transactions
     * (ex : "> 100", "< 50", "= 42.50"). Filtree directement en base plutot
     * que de charger toutes les transactions cote frontend pour les filtrer
     * ensuite en Javascript.
     */
    public List<TransactionDTO> findByAmountFilter(Long accountId, String operator, BigDecimal amount) {
        List<Transaction> results = switch (operator) {
            case ">" -> accountId != null
                    ? transactionRepository.findByAccount_IdAndAmountGreaterThanOrderByDateDescIdDesc(accountId, amount)
                    : transactionRepository.findByAmountGreaterThanOrderByDateDescIdDesc(amount);
            case "<" -> accountId != null
                    ? transactionRepository.findByAccount_IdAndAmountLessThanOrderByDateDescIdDesc(accountId, amount)
                    : transactionRepository.findByAmountLessThanOrderByDateDescIdDesc(amount);
            case "=" -> accountId != null
                    ? transactionRepository.findByAccount_IdAndAmountOrderByDateDescIdDesc(accountId, amount)
                    : transactionRepository.findByAmountOrderByDateDescIdDesc(amount);
            default -> throw new IllegalArgumentException("Operateur de montant invalide : " + operator);
        };
        return results.stream().map(transactionMapper::toDTO).toList();
    }

    private Transaction findEntityById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Transaction introuvable : " + id
                        ));
    }

    public TransactionDTO create(TransactionDTO payload) {
        Transaction transaction = new Transaction();
        applyPayload(transaction, payload);
        Transaction saved = transactionRepository.save(transaction);
        return transactionMapper.toDTO(saved);
    }

    public TransactionDTO update(Long id, TransactionDTO payload) {
        Transaction existing = findEntityById(id);
        Tiers previousPayee = existing.getPayee();

        applyPayload(existing, payload);
        Transaction saved = transactionRepository.save(existing);

        // Nettoyage de l'ancien tiers "best effort" : ne doit jamais faire
        // echouer la sauvegarde de la transaction elle-meme (deja actee a ce
        // stade), meme en cas de probleme inattendu sur ce nettoyage annexe.
        try {
            payeeService.cleanupIfPayeeChanged(previousPayee, saved.getPayee());
        } catch (Exception e) {
            log.warn("Echec du nettoyage de l'ancien tiers apres modification de la transaction {} : {}", id, e.getMessage(), e);
        }

        return transactionMapper.toDTO(saved);
    }

    /**
     * Modifie en une fois un ou plusieurs champs (categorie, tiers, mode de
     * paiement, depense periodique, annualisation, statut de validation) sur toutes les
     * transactions listees dans payload.getIds() — utilise par la selection
     * multiple de l'onglet Transactions. Seuls les champs dont le drapeau
     * "...Provided" est a true sont modifies ; les autres sont laisses tels
     * quels sur chaque transaction. Le tiers, quand fourni, passe par la meme
     * reconnaissance/creation automatique qu'une saisie manuelle unitaire
     * (voir applyPayload / ServiceTiers#resolveOrCreate).
     */
    @Transactional
    public List<TransactionDTO> bulkUpdate(TransactionBulkUpdateDTO payload) {
        List<Long> ids = payload.getIds();
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction selectionnee");
        }

        Categorie category = payload.isCategoryIdProvided() && payload.getCategoryId() != null
                ? categoryService.findById(payload.getCategoryId())
                : null;

        List<Transaction> transactions = transactionRepository.findAllById(ids);
        List<Tiers> orphanCandidates = new ArrayList<>();

        for (Transaction t : transactions) {
            if (payload.isCategoryIdProvided()) {
                t.setCategory(category);
            }

            if (payload.isPayeeProvided()) {
                Tiers previousPayee = t.getPayee();
                String payeeText = payload.getPayee();
                Tiers resolvedPayee = (payeeText != null && !payeeText.isBlank())
                        ? payeeService.resolveOrCreate(payeeText, t.getAmount())
                        : null;
                t.setPayee(resolvedPayee);
                if (previousPayee != null && !previousPayee.equals(resolvedPayee)) {
                    orphanCandidates.add(previousPayee);
                }
            }

            if (payload.isPaymentModeProvided()) {
                t.setPaymentMode(payload.getPaymentMode());
            }

            if (payload.isPeriodicExpenseProvided()) {
                t.setPeriodicExpense(payload.isPeriodicExpense());
            }

            if (payload.isAnnualizedProvided()) {
                t.setAnnualized(payload.isAnnualized());
            }

            if (payload.isValidatedProvided()) {
                t.setValidated(payload.isValidated());
            }
        }

        List<Transaction> saved = transactionRepository.saveAll(transactions);
        log.info("Modification en masse de {} transaction(s)", saved.size());

        // Nettoyage des anciens tiers devenus orphelins "best effort" : ne
        // doit jamais faire echouer la mise a jour elle-meme (deja actee).
        for (Tiers previousPayee : orphanCandidates) {
            try {
                payeeService.cleanupIfPayeeChanged(previousPayee, null);
            } catch (Exception e) {
                log.warn("Echec du nettoyage d'un ancien tiers apres modification en masse : {}", e.getMessage(), e);
            }
        }

        return saved.stream().map(transactionMapper::toDTO).toList();
    }

    public void delete(Long id) {
        Transaction existing = findEntityById(id);
        Tiers previousPayee = existing.getPayee();

        transactionRepository.deleteById(id);

        try {
            payeeService.cleanupIfPayeeChanged(previousPayee, null);
        } catch (Exception e) {
            log.warn("Echec du nettoyage de l'ancien tiers apres suppression de la transaction {} : {}", id, e.getMessage(), e);
        }
    }

    /** Passe toutes les transactions en attente au statut "validee". Renvoie le nombre concerne. */
    @Transactional
    public int validateAllPending() {
        return transactionRepository.validateAllPending();
    }

    /** Bascule le statut valide/en attente d'une seule transaction. */
    public TransactionDTO toggleValidated(Long id) {
        Transaction t = findEntityById(id);
        t.setValidated(!t.isValidated());
        return transactionMapper.toDTO(transactionRepository.save(t));
    }

    /** Bascule le statut "depense periodique" d'une transaction. */
    public TransactionDTO togglePeriodicExpense(Long id) {
        Transaction t = findEntityById(id);
        t.setPeriodicExpense(!t.isPeriodicExpense());
        return transactionMapper.toDTO(transactionRepository.save(t));
    }

    /** Bascule le statut "annualisee" (lissee sur l'annee dans les rapports) d'une transaction. */
    public TransactionDTO toggleAnnualized(Long id) {
        Transaction t = findEntityById(id);
        t.setAnnualized(!t.isAnnualized());
        return transactionMapper.toDTO(transactionRepository.save(t));
    }

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

    /**
     * Suggere des groupes de transactions qui semblent periodiques : meme
     * tiers, montant proche (tolerance +/-10%), au moins
     * PERIODIC_MIN_OCCURRENCES occurrences, ET dates reparties a intervalle
     * regulier (mensuel/trimestriel/annuel a tolerance pres) — sans quoi une
     * simple coincidence de montant (ex : plusieurs achats ponctuels du meme
     * montant) serait a tort suggeree comme periodique. Ignore les
     * transactions deja marquees periodiques (deja acceptees) et les
     * virements internes (jamais une depense/revenu periodique au sens
     * metier).
     */
    public List<SuggestionPeriodiqueDTO> findPeriodicSuggestions() {
        List<Transaction> candidates = transactionRepository.findAllByOrderByDateDescIdDesc().stream()
                .filter(t -> !t.isPeriodicExpense())
                .filter(t -> t.getPaymentMode() != ModePaiement.TRANSFER)
                .filter(t -> t.getPayee() != null)
                .toList();

        Map<Long, List<Transaction>> byPayee = candidates.stream()
                .collect(Collectors.groupingBy(t -> t.getPayee().getId()));

        List<SuggestionPeriodiqueDTO> suggestions = new ArrayList<>();
        for (List<Transaction> group : byPayee.values()) {
            if (group.size() < PERIODIC_MIN_OCCURRENCES) continue;
            for (List<Transaction> amountGroup : groupByToleranteAmount(group)) {
                if (amountGroup.size() >= PERIODIC_MIN_OCCURRENCES && hasRegularInterval(amountGroup)) {
                    suggestions.add(buildPeriodicSuggestion(amountGroup));
                }
            }
        }

        suggestions.sort((a, b) -> b.getCount() - a.getCount());
        return suggestions;
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
        long occurrences = Math.max(1, Math.min(
                Math.round((double) days / target),
                PERIODIC_MAX_MISSED_OCCURRENCES + 1L));
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
                .payee(sorted.get(sorted.size() - 1).getPayee().getName())
                .count(sorted.size())
                .averageAmount(averageAmount)
                .averageIntervalDays(averageIntervalDays)
                .transactions(sorted.stream().map(transactionMapper::toDTO).toList())
                .build();
    }

    /**
     * Regroupe les transactions deja en base qui semblent doublonnees, selon
     * la meme definition que la detection a l'import (voir
     * ServiceImport#isDuplicate) : meme compte, et soit le meme identifiant
     * externe (fourni par la banque/le fichier importe), soit la meme date +
     * montant + tiers canonique (insensible a la casse). Pour l'onglet
     * "Doublons" de la page Transactions, qui permet de nettoyer les
     * doublons importes par erreur (ex : meme fichier importe deux fois).
     */
    public List<GroupeDoublonDTO> findDuplicateGroups() {
        Map<String, List<Transaction>> groups = transactionRepository.findAllByOrderByDateDescIdDesc().stream()
                .collect(Collectors.groupingBy(this::duplicateGroupKey, LinkedHashMap::new, Collectors.toList()));

        return groups.values().stream()
                .filter(group -> group.size() > 1)
                .map(this::buildDuplicateGroup)
                .sorted(Comparator.comparing((GroupeDoublonDTO g) -> g.getTransactions().get(0).getDate()).reversed())
                .toList();
    }

    /**
     * Cle de regroupement d'une transaction pour la detection de doublons :
     * l'identifiant externe prime s'il est fourni (identique a
     * ServiceImport#isDuplicate), sinon on retombe sur compte+date+montant+
     * tiers canonique (voir CleDoublon).
     */
    private String duplicateGroupKey(Transaction t) {
        if (t.getExternalId() != null && !t.getExternalId().isBlank()) {
            return "ext:" + t.getAccount().getId() + "|" + t.getExternalId();
        }
        String canonicalPayee = t.getPayee() != null ? t.getPayee().getName() : null;
        CleDoublon key = CleDoublon.of(t.getDate(), t.getAmount(), canonicalPayee);
        return "key:" + t.getAccount().getId() + "|" + key;
    }

    private GroupeDoublonDTO buildDuplicateGroup(List<Transaction> transactions) {
        List<Transaction> sorted = transactions.stream()
                .sorted(Comparator.comparing(Transaction::getId))
                .toList();
        return GroupeDoublonDTO.builder()
                .count(sorted.size())
                .transactions(sorted.stream().map(transactionMapper::toDTO).toList())
                .build();
    }

    /**
     * Cree un virement entre deux comptes : deux transactions liees
     * (debit sur le compte source, credit sur le compte destination),
     * chacune reliee a l'autre via transferTransaction, et marquees
     * ModePaiement.TRANSFER pour etre exclues des rapports de depenses/revenus.
     */
    @Transactional
    public List<TransactionDTO> createTransfer(DemandeVirementDTO request) {
        if (request.getFromAccountId() == null || request.getToAccountId() == null) {
            throw new IllegalArgumentException("Les deux comptes sont obligatoires");
        }
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new IllegalArgumentException("Le compte source et le compte destination doivent etre differents");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Le montant du virement doit etre positif");
        }

        Compte from = accountService.findById(request.getFromAccountId());
        Compte to = accountService.findById(request.getToAccountId());
        LocalDate date = request.getDate() != null ? request.getDate() : LocalDate.now();
        BigDecimal amount = request.getAmount().abs();

        Transaction debit = new Transaction();
        debit.setAccount(from);
        debit.setDate(date);
        debit.setAmount(amount.negate());
        debit.setMemo(request.getMemo());
        debit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setValidated(true);

        Transaction credit = new Transaction();
        credit.setAccount(to);
        credit.setDate(date);
        credit.setAmount(amount);
        credit.setMemo(request.getMemo());
        credit.setPaymentMode(ModePaiement.TRANSFER);
        credit.setValidated(true);

        Transaction savedDebit = transactionRepository.save(debit);
        credit.setTransferTransaction(savedDebit);
        Transaction savedCredit = transactionRepository.save(credit);
        savedDebit.setTransferTransaction(savedCredit);
        Transaction finalDebit = transactionRepository.save(savedDebit);

        return List.of(transactionMapper.toDTO(finalDebit), transactionMapper.toDTO(savedCredit));
    }

    private void applyPayload(Transaction transaction, TransactionDTO payload) {

        if (payload.getAccountId() == null) {
            throw new IllegalArgumentException(
                    "Le compte est obligatoire"
            );
        }

        Compte account = accountService.findById(payload.getAccountId());
        transaction.setAccount(account);

        transaction.setDate(payload.getDate());
        transaction.setAmount(payload.getAmount());

        // Le tiers saisi/modifie passe par la meme reconnaissance/creation
        // automatique que l'import (voir ServiceTiers#resolveOrCreate), afin
        // que l'onglet "Tiers" reste toujours a jour avec les tiers realement
        // utilises sur les transactions, meme lors d'une saisie manuelle.
        String payeeText = payload.getPayee();
        Tiers resolvedPayee = (payeeText != null && !payeeText.isBlank())
                ? payeeService.resolveOrCreate(payeeText, payload.getAmount())
                : null;
        transaction.setPayee(resolvedPayee);

        transaction.setMemo(sanitizeMemo(payload.getMemo()));
        transaction.setPaymentMode(payload.getPaymentMode());
        transaction.setTags(payload.getTags());
        transaction.setPeriodicExpense(payload.isPeriodicExpense());
        transaction.setAnnualized(payload.isAnnualized());
        transaction.setExternalId(payload.getExternalId());
        // Une transaction saisie/modifiee manuellement est consideree comme validee ;
        // seules les transactions importees/synchronisees demarrent "en attente".
        transaction.setValidated(payload.isValidated());

        if (payload.getCategoryId() != null) {
            // Categorie choisie explicitement (saisie manuelle, ou deja
            // presente sur une transaction existante en cours de modification) :
            // ce choix prevaut toujours et n'est jamais ecrase.
            Categorie category =
                    categoryService.findById(payload.getCategoryId());

            transaction.setCategory(category);
        } else if (resolvedPayee != null && resolvedPayee.getCategory() != null) {
            // Aucune categorie fournie explicitement : on applique la
            // categorie par defaut du tiers, si elle existe (source unique
            // de categorisation automatique, cf. Tiers#category). On ne
            // touche a rien si le tiers n'a pas de categorie par defaut.
            transaction.setCategory(resolvedPayee.getCategory());
        } else {
            transaction.setCategory(null);
        }
    }

    /** Remplace un memo litteralement "(NULL)"/"NULL" ou vide par null. */
    static String sanitizeMemo(String memo) {
        if (memo == null) return null;
        String trimmed = memo.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("(NULL)") || trimmed.equalsIgnoreCase("NULL")) {
            return null;
        }
        return trimmed;
    }
}

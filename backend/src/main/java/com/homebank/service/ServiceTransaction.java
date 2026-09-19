package com.homebank.service;

import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.dto.TransactionBulkUpdateDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.dto.DemandeDivisionDTO;
import com.homebank.dto.DemandeVirementDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import com.homebank.utils.ModePaiement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ServiceTransaction {

    private static final Logger log = LoggerFactory.getLogger(ServiceTransaction.class);

    private final DepotTransaction transactionRepository;
    private final DepotTransactionSupprimee deletedTransactionRepository;
    private final ServiceCompte accountService;
    private final ServiceCategorie categoryService;
    private final ServiceTiers payeeService;
    private final MappeurTransaction transactionMapper;

    public ServiceTransaction(
            DepotTransaction transactionRepository,
            DepotTransactionSupprimee deletedTransactionRepository,
            ServiceCompte accountService,
            ServiceCategorie categoryService,
            ServiceTiers payeeService,
            MappeurTransaction transactionMapper) {

        this.transactionRepository = transactionRepository;
        this.deletedTransactionRepository = deletedTransactionRepository;
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

        // Comme pour splitTransaction : editer une seule des deux transactions
        // d'un virement (compte, montant, date...) sans repercuter le meme
        // changement sur l'autre casserait silencieusement l'appariement
        // (voir createTransfer). Pas de synchronisation cote serveur pour
        // l'instant : on refuse plutot que de laisser desynchroniser.
        if (existing.getPaymentMode() == ModePaiement.TRANSFER || existing.getTransferTransaction() != null) {
            throw new IllegalArgumentException("Un virement ne peut pas etre modifie directement : supprimez-le et recreez-le si besoin.");
        }

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
     * Divise une transaction en deux : la transaction d'origine devient la
     * "1ere partie" (montant/categorie/memo remplaces sur place), et une
     * nouvelle transaction est creee pour la "2e partie" — meme compte, date,
     * tiers, mode de paiement, statut de validation et marqueurs (periodique,
     * exceptionnelle) que l'originale ; seuls le montant, la categorie et le
     * memo different entre les deux parties (le caractere "annuel" suit donc
     * automatiquement la categorie de chaque partie, voir Categorie#annualized).
     * Le montant de la 2e partie est calcule cote serveur (montant d'origine - firstAmount),
     * jamais saisi directement, pour garantir que la somme des deux parties
     * egale toujours le montant d'origine — le solde du compte n'est donc
     * jamais affecte par une division.
     */
    @Transactional
    public List<TransactionDTO> splitTransaction(Long id, DemandeDivisionDTO payload) {
        Transaction original = findEntityById(id);

        if (original.getPaymentMode() == ModePaiement.TRANSFER || original.getTransferTransaction() != null) {
            throw new IllegalArgumentException("Un virement ne peut pas etre divise");
        }

        BigDecimal totalAmount = original.getAmount();
        BigDecimal firstAmount = payload.getFirstAmount();
        if (firstAmount == null || firstAmount.signum() == 0) {
            throw new IllegalArgumentException("Le montant de la 1ere partie est obligatoire et ne peut pas etre nul");
        }
        if (firstAmount.signum() != totalAmount.signum()) {
            throw new IllegalArgumentException("Le montant de la 1ere partie doit avoir le meme signe que le montant d'origine");
        }
        if (firstAmount.abs().compareTo(totalAmount.abs()) >= 0) {
            throw new IllegalArgumentException(
                    "Le montant de la 1ere partie doit etre strictement inferieur, en valeur absolue, au montant d'origine");
        }
        BigDecimal secondAmount = totalAmount.subtract(firstAmount);

        original.setAmount(firstAmount);
        original.setCategory(payload.getFirstCategoryId() != null
                ? categoryService.findById(payload.getFirstCategoryId())
                : null);
        original.setMemo(sanitizeMemo(payload.getFirstMemo()));

        Transaction second = new Transaction();
        second.setAccount(original.getAccount());
        second.setDate(original.getDate());
        second.setAmount(secondAmount);
        second.setPayee(original.getPayee());
        second.setCategory(payload.getSecondCategoryId() != null
                ? categoryService.findById(payload.getSecondCategoryId())
                : null);
        second.setMemo(sanitizeMemo(payload.getSecondMemo()));
        second.setPaymentMode(original.getPaymentMode());
        second.setTags(original.getTags());
        second.setValidated(original.isValidated());
        second.setPeriodicExpense(original.isPeriodicExpense());
        second.setExceptional(original.isExceptional());

        Transaction savedOriginal = transactionRepository.save(original);
        Transaction savedSecond = transactionRepository.save(second);

        log.info("Transaction {} divisee en deux : {} et {}", id, savedOriginal.getId(), savedSecond.getId());

        return List.of(transactionMapper.toDTO(savedOriginal), transactionMapper.toDTO(savedSecond));
    }

    /**
     * Modifie en une fois un ou plusieurs champs (categorie, tiers, mode de
     * paiement, depense periodique, annualisation, caractere exceptionnel,
     * statut de validation) sur toutes les
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

        List<Transaction> transactions = transactionRepository.findAllByIdWithTransferTransaction(ids);
        List<Tiers> orphanCandidates = new ArrayList<>();
        for (Transaction t : transactions) {
            applyBulkFields(t, payload, category, orphanCandidates);
        }

        List<Transaction> saved = transactionRepository.saveAll(transactions);
        log.info("Modification en masse de {} transaction(s)", saved.size());

        cleanupOrphanPayees(orphanCandidates);

        return saved.stream().map(transactionMapper::toDTO).toList();
    }

    /** Applique a une transaction les seuls champs marques "...Provided" dans payload (voir bulkUpdate). */
    private void applyBulkFields(Transaction t, TransactionBulkUpdateDTO payload, Categorie category, List<Tiers> orphanCandidates) {
        if (payload.isCategoryIdProvided()) {
            t.setCategory(category);
        }

        if (payload.isPayeeProvided()) {
            applyBulkPayee(t, payload.getPayee(), orphanCandidates);
        }

        if (payload.isPaymentModeProvided()) {
            applyBulkPaymentMode(t, payload.getPaymentMode());
        }

        if (payload.isPeriodicExpenseProvided()) {
            t.setPeriodicExpense(payload.isPeriodicExpense());
        }

        if (payload.isExceptionalProvided()) {
            t.setExceptional(payload.isExceptional());
        }

        if (payload.isValidatedProvided()) {
            t.setValidated(payload.isValidated());
        }
    }

    /**
     * Ignore le changement de mode de paiement en masse vers ou depuis
     * TRANSFER : un virement n'existe que par sa paire (voir createTransfer),
     * que l'edition en masse ne sait pas creer/rompre proprement — l'appliquer
     * ici casserait silencieusement l'appariement (transaction marquee/
     * demarquee TRANSFER sans que sa jumelle suive), voir aussi le meme garde-fou
     * dans update().
     */
    private void applyBulkPaymentMode(Transaction t, ModePaiement requested) {
        boolean isTransferSide = t.getPaymentMode() == ModePaiement.TRANSFER || t.getTransferTransaction() != null;
        if (isTransferSide || requested == ModePaiement.TRANSFER) {
            log.info("Changement de mode de paiement ignore pour la transaction {} : un virement se cree/modifie uniquement via l'action \"Virement\"", t.getId());
            return;
        }
        t.setPaymentMode(requested);
    }

    /** Resout/cree le nouveau tiers depuis le texte fourni et l'affecte a la transaction ; releve l'ancien tiers s'il change (voir cleanupOrphanPayees). */
    private void applyBulkPayee(Transaction t, String payeeText, List<Tiers> orphanCandidates) {
        Tiers previousPayee = t.getPayee();
        Tiers resolvedPayee = (payeeText != null && !payeeText.isBlank())
                ? payeeService.resolveOrCreate(payeeText, t.getAmount())
                : null;
        t.setPayee(resolvedPayee);
        if (previousPayee != null && !previousPayee.equals(resolvedPayee)) {
            orphanCandidates.add(previousPayee);
        }
    }

    /** Nettoyage "best effort" des anciens tiers devenus orphelins apres une modification en masse : ne doit jamais faire echouer la mise a jour elle-meme (deja actee). */
    private void cleanupOrphanPayees(List<Tiers> orphanCandidates) {
        for (Tiers previousPayee : orphanCandidates) {
            try {
                payeeService.cleanupIfPayeeChanged(previousPayee, null);
            } catch (Exception e) {
                log.warn("Echec du nettoyage d'un ancien tiers apres modification en masse : {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Supprime la transaction, et si elle fait partie d'un virement (voir
     * createTransfer), supprime aussi la transaction jumelle avec elle : les
     * deux se referencent mutuellement (transferTransaction dans les deux
     * sens), donc les laisser desappariees romprait l'invariant "un virement
     * = deux transactions liees" — et de toute facon, supprimer l'une sans
     * l'autre serait rejete par la contrainte de cle etrangere en base.
     */
    @Transactional
    public void delete(Long id) {
        Transaction existing = findEntityById(id);
        Tiers previousPayee = existing.getPayee();

        Map<Long, Transaction> byId = new LinkedHashMap<>();
        byId.put(existing.getId(), existing);
        List<Transaction> transactions = unlinkTransferPairs(byId);

        transactions.forEach(this::recordDeletion);
        transactionRepository.deleteAll(transactions);

        try {
            payeeService.cleanupIfPayeeChanged(previousPayee, null);
        } catch (Exception e) {
            log.warn("Echec du nettoyage de l'ancien tiers apres suppression de la transaction {} : {}", id, e.getMessage(), e);
        }
    }

    /**
     * Supprime plusieurs transactions a la fois (selection multiple de
     * l'onglet Transactions) : meme empreinte de suppression (recordDeletion)
     * qu'une suppression unitaire (voir delete), pour qu'un futur reimport du
     * releve source ne les fasse pas reapparaitre. Renvoie le nombre de
     * transactions effectivement supprimees.
     */
    @Transactional
    public int deleteBulk(List<Long> ids) {
        return deleteTransactions(ids, true);
    }

    /**
     * Supprime toutes les transactions en attente (non validees) — pendant
     * equivalent de validateAllPending. Contrairement a deleteBulk, ne laisse
     * PAS d'empreinte de suppression (recordDeletion) : une transaction en
     * attente n'a jamais ete rapprochee/confirmee, ce nettoyage sert
     * generalement a repartir d'une base propre avant un reimport du meme
     * releve, qui doit alors pouvoir la recreer normalement plutot que de la
     * rejeter a tort comme doublon.
     * <p>
     * Un cote de virement en attente dont la jumelle est deja validee est
     * exclu de ce nettoyage : deleteTransactions/unlinkTransferPairs emporte
     * toujours les deux cotes ensemble (voir leur javadoc), ce qui supprimerait
     * ici une transaction validee que l'utilisateur n'a pourtant jamais
     * demande a supprimer (seul "en attente" a ete demande) — a supprimer
     * manuellement si c'est bien l'intention.
     */
    @Transactional
    public int deleteAllPending() {
        List<Transaction> pending = transactionRepository.findByValidatedFalseWithTransferTransaction();
        List<Long> pendingIds = new ArrayList<>();
        int skipped = 0;
        for (Transaction t : pending) {
            Transaction pair = t.getTransferTransaction();
            if (pair != null && pair.isValidated()) {
                skipped++;
                continue;
            }
            pendingIds.add(t.getId());
        }
        if (skipped > 0) {
            log.warn("{} transaction(s) de virement en attente ignorees par le nettoyage global : leur jumelle est deja validee (suppression manuelle si besoin)", skipped);
        }
        return pendingIds.isEmpty() ? 0 : deleteTransactions(pendingIds, false);
    }

    /**
     * Complete `byId` avec la jumelle de virement de chaque transaction deja
     * presente (voir createTransfer), puis casse la reference mutuelle
     * transferTransaction des deux cotes : sinon la cle etrangere de chaque
     * cote d'un virement empeche de supprimer l'autre. Factorise entre
     * delete(Long) et deleteTransactions, seule differences etant le nombre
     * de transactions de depart.
     */
    private List<Transaction> unlinkTransferPairs(Map<Long, Transaction> byId) {
        for (Transaction t : List.copyOf(byId.values())) {
            Transaction pair = t.getTransferTransaction();
            if (pair != null) {
                byId.putIfAbsent(pair.getId(), pair);
            }
        }
        List<Transaction> transactions = new ArrayList<>(byId.values());

        List<Transaction> transferSides = transactions.stream()
                .filter(t -> t.getTransferTransaction() != null)
                .toList();
        if (!transferSides.isEmpty()) {
            transferSides.forEach(t -> t.setTransferTransaction(null));
            transactionRepository.saveAll(transferSides);
        }

        return transactions;
    }

    /**
     * Suppression en masse factorisee entre deleteBulk et deleteAllPending
     * (voir leur javadoc pour la difference de comportement sur recordTombstone).
     * Comme delete(Long) : la transaction jumelle d'un virement selectionne
     * est toujours emportee avec lui, meme si elle n'a pas ete selectionnee
     * elle-meme, pour ne jamais laisser un virement partiellement supprime
     * (les deux se referencent mutuellement, voir createTransfer).
     */
    private int deleteTransactions(List<Long> ids, boolean recordTombstone) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction selectionnee");
        }

        Map<Long, Transaction> byId = new LinkedHashMap<>();
        for (Transaction t : transactionRepository.findAllByIdWithTransferTransaction(ids)) {
            byId.put(t.getId(), t);
        }
        List<Transaction> transactions = unlinkTransferPairs(byId);

        List<Tiers> orphanCandidates = new ArrayList<>();
        for (Transaction t : transactions) {
            if (recordTombstone) recordDeletion(t);
            if (t.getPayee() != null) orphanCandidates.add(t.getPayee());
        }
        transactionRepository.deleteAll(transactions);

        cleanupOrphanPayees(orphanCandidates);
        log.info("Suppression en masse de {} transaction(s) (empreinte de suppression : {})", transactions.size(), recordTombstone);

        return transactions.size();
    }

    /**
     * Enregistre une empreinte de la transaction avant sa suppression (voir
     * TransactionSupprimee), pour que ServiceImport#isDuplicate la reconnaisse
     * et evite de la reimporter si le releve bancaire source la contient
     * encore lors d'un futur import — sans quoi une transaction supprimee
     * manuellement (ex : paire "depense remboursee", voir
     * findReimbursementGroups) reapparaissait au prochain import.
     */
    private void recordDeletion(Transaction t) {
        TransactionSupprimee tombstone = new TransactionSupprimee();
        tombstone.setAccountId(t.getAccount().getId());
        tombstone.setDate(t.getDate());
        tombstone.setAmount(t.getAmount());
        tombstone.setPayeeName(t.getPayee() != null ? t.getPayee().getName() : null);
        tombstone.setExternalId(t.getExternalId());
        deletedTransactionRepository.save(tombstone);
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

    /** Bascule le statut "exceptionnelle" (exclue des tableaux de bord) d'une transaction. */
    public TransactionDTO toggleExceptional(Long id) {
        Transaction t = findEntityById(id);
        t.setExceptional(!t.isExceptional());
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
     * transactions deja marquees periodiques (deja acceptees), les
     * transactions dont la suggestion a ete refusee par l'utilisateur (voir
     * Transaction#periodicSuggestionIgnored / ignorePeriodicSuggestion
     * ci-dessous) et les virements internes (jamais une depense/revenu
     * periodique au sens metier).
     */
    public List<SuggestionPeriodiqueDTO> findPeriodicSuggestions() {
        List<Transaction> candidates = transactionRepository.findAllByOrderByDateDescIdDesc().stream()
                .filter(t -> !t.isPeriodicExpense())
                .filter(t -> !t.isPeriodicSuggestionIgnored())
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

        suggestions.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
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
                .payee(sorted.get(sorted.size() - 1).getPayee().getName())
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
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction selectionnee");
        }
        List<Transaction> transactions = transactionRepository.findAllById(ids);
        transactions.forEach(t -> t.setPeriodicSuggestionIgnored(true));
        transactionRepository.saveAll(transactions);
        log.info("Marquage de {} transaction(s) comme suggestion periodique ignoree", transactions.size());
    }

    /**
     * Regroupe les transactions deja en base qui semblent doublonnees, en
     * deux categories (voir GroupeDoublonDTO#certain) :
     * - "certains" : meme definition que la detection a l'import (voir
     *   ServiceImport#isDuplicate) — meme compte, et soit le meme identifiant
     *   externe (fourni par la banque/le fichier importe), soit la meme date
     *   + montant + tiers canonique (insensible a la casse). Occurrences
     *   interchangeables.
     * - "probables" : meme compte + date + montant, mais tiers different —
     *   ratees par la detection ci-dessus car elle exige le meme tiers. Cas
     *   frequent d'un meme releve importe deux fois a des moments differents :
     *   la premiere fois categorisee et le tiers renomme a la main, la
     *   seconde fois avec le libelle brut de la banque jamais nettoye (ex :
     *   "Transfer : Prêt immobilier" vs "PRELEVEMENT DE ECHEANCE PRET REF :
     *   ..." le meme jour pour le meme montant). Un groupe deja compte parmi
     *   les "certains" (tiers identiques) n'est jamais liste une deuxieme
     *   fois ici. Pour l'onglet "Doublons" de la page Transactions, qui
     *   permet de nettoyer les doublons importes par erreur.
     */
    public List<GroupeDoublonDTO> findDuplicateGroups() {
        List<Transaction> all = transactionRepository.findAllByOrderByDateDescIdDesc().stream()
                .filter(t -> !t.isDuplicateIgnored())
                .toList();

        Map<String, List<Transaction>> certainGroups = all.stream()
                .collect(Collectors.groupingBy(this::duplicateGroupKey, LinkedHashMap::new, Collectors.toList()));

        Stream<GroupeDoublonDTO> certain = certainGroups.values().stream()
                .filter(group -> group.size() > 1)
                .map(group -> buildDuplicateGroup(group, true, false));

        Map<String, List<Transaction>> looseGroups = all.stream()
                .collect(Collectors.groupingBy(this::looseDuplicateGroupKey, LinkedHashMap::new, Collectors.toList()));

        Stream<GroupeDoublonDTO> probable = looseGroups.values().stream()
                .filter(group -> group.size() > 1)
                .filter(group -> !samePayeeForAll(group)) // sinon deja remonte parmi les "certains" ci-dessus
                .map(group -> buildDuplicateGroup(group, false, false));

        Stream<GroupeDoublonDTO> reimbursements = findReimbursementGroups(all).stream();

        return Stream.concat(Stream.concat(certain, probable), reimbursements)
                .sorted(Comparator.comparing((GroupeDoublonDTO g) -> g.getTransactions().get(0).getDate()).reversed())
                .toList();
    }

    /**
     * Detecte les groupes "remboursement" : PAIRES d'une depense et d'un
     * remboursement de meme montant (valeur absolue) par le meme tiers, a une
     * date quelconque, meme compte ou non. A la difference des doublons
     * "certains"/"probables" ci-dessus (qui exigent un montant identique,
     * signe compris, et ne peuvent donc jamais matcher ce cas), il ne s'agit
     * pas d'un import en double a supprimer : juste un reperage pour que
     * l'utilisateur voie d'un coup d'œil qu'une depense a deja ete remboursee.
     * Ignore les virements internes (jamais un remboursement au sens metier,
     * et de toute facon sans tiers).
     * <p>
     * S'il y a plusieurs depenses du meme montant mais un seul remboursement
     * (ex : 5 pleins d'essence a -50€ chez le meme tiers, un seul rembourse),
     * une seule paire est formee, avec la depense la plus RECENTE parmi
     * celles pas deja appariees (la plus probable d'etre celle concernee par
     * le remboursement) : les autres depenses du meme montant ne sont pas
     * proposees. S'il y a plusieurs remboursements, chacun forme sa propre
     * paire avec la depense la plus recente restante, en repetant l'operation
     * (2e remboursement <-> 2e depense la plus recente, etc.).
     */
    private List<GroupeDoublonDTO> findReimbursementGroups(List<Transaction> all) {
        Map<Long, List<Transaction>> byPayee = all.stream()
                .filter(t -> t.getPayee() != null)
                .filter(t -> t.getPaymentMode() != ModePaiement.TRANSFER)
                .collect(Collectors.groupingBy(t -> t.getPayee().getId()));

        Comparator<Transaction> mostRecentFirst =
                Comparator.comparing(Transaction::getDate).thenComparing(Transaction::getId).reversed();

        List<GroupeDoublonDTO> groups = new ArrayList<>();
        for (List<Transaction> payeeTransactions : byPayee.values()) {
            Map<BigDecimal, List<Transaction>> byAbsAmount = payeeTransactions.stream()
                    .collect(Collectors.groupingBy(t -> t.getAmount().abs().setScale(2, RoundingMode.HALF_UP)));

            for (List<Transaction> sameAbsAmount : byAbsAmount.values()) {
                List<Transaction> expenses = sameAbsAmount.stream()
                        .filter(t -> t.getAmount().signum() < 0)
                        .sorted(mostRecentFirst)
                        .toList();
                List<Transaction> reimbursements = sameAbsAmount.stream()
                        .filter(t -> t.getAmount().signum() > 0)
                        .sorted(mostRecentFirst)
                        .toList();

                int pairCount = Math.min(expenses.size(), reimbursements.size());
                for (int i = 0; i < pairCount; i++) {
                    groups.add(buildDuplicateGroup(List.of(expenses.get(i), reimbursements.get(i)), false, true));
                }
            }
        }
        return groups;
    }

    /**
     * Cle de regroupement d'une transaction pour la detection de doublons
     * "certains" : l'identifiant externe prime s'il est fourni (identique a
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

    /**
     * Cle de regroupement "large" pour la detection des doublons probables :
     * compte + date + montant seulement, sans le tiers (voir
     * findDuplicateGroups).
     */
    private String looseDuplicateGroupKey(Transaction t) {
        return t.getAccount().getId() + "|" + t.getDate() + "|" + t.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    private String canonicalPayee(Transaction t) {
        String name = t.getPayee() != null ? t.getPayee().getName() : null;
        return name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
    }

    private boolean samePayeeForAll(List<Transaction> group) {
        String first = canonicalPayee(group.get(0));
        return group.stream().allMatch(t -> canonicalPayee(t).equals(first));
    }

    /**
     * Marque les transactions passees comme n'etant pas des doublons : elles
     * seront exclues de findDuplicateGroups (voir Transaction#duplicateIgnored),
     * ce qui fait disparaitre de l'onglet "Doublons" tout groupe dont l'une des
     * occurrences ne compte plus (un groupe necessite au moins 2 occurrences
     * restantes pour etre affiche).
     */
    @Transactional
    public void markAsNotDuplicate(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction selectionnee");
        }
        List<Transaction> transactions = transactionRepository.findAllById(ids);
        transactions.forEach(t -> t.setDuplicateIgnored(true));
        transactionRepository.saveAll(transactions);
        log.info("Marquage de {} transaction(s) comme non doublon", transactions.size());
    }

    private GroupeDoublonDTO buildDuplicateGroup(List<Transaction> transactions, boolean certain, boolean reimbursement) {
        List<Transaction> sorted = transactions.stream()
                .sorted(Comparator.comparing(Transaction::getId))
                .toList();
        return GroupeDoublonDTO.builder()
                .count(sorted.size())
                .transactions(sorted.stream().map(transactionMapper::toDTO).toList())
                .certain(certain)
                .reimbursement(reimbursement)
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
        LocalDate date = request.getDate() != null ? request.getDate() : LocalDate.now(ZoneId.systemDefault());
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
        transaction.setExceptional(payload.isExceptional());
        transaction.setExternalId(payload.getExternalId());
        // Une transaction saisie/modifiee manuellement est consideree comme validee ;
        // seules les transactions importees/synchronisees demarrent "en attente".
        transaction.setValidated(payload.isValidated());

        // Categorie appliquee TELLE QUELLE depuis le payload, comme les autres
        // champs (memo, tags...) : payload.getCategoryId() == null signifie
        // "pas de categorie", y compris quand l'utilisateur vient d'effacer
        // volontairement la categorie d'une transaction deja categorisee (le
        // champ CategoryAutocomplete a un bouton dedie pour ca). Le
        // pre-remplissage depuis la categorie par defaut du tiers (voir
        // Tiers#category) reste gere cote FRONTEND uniquement (voir
        // Transactions.vue#applyDefaultCategoryFromPayee et son equivalent
        // dans TransactionRow.vue) : le reappliquer ici aussi ecrasait
        // silencieusement toute tentative de l'utilisateur de rendre une
        // transaction non categorisee des que son tiers avait une categorie
        // par defaut.
        transaction.setCategory(payload.getCategoryId() != null
                ? categoryService.findById(payload.getCategoryId())
                : null);
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

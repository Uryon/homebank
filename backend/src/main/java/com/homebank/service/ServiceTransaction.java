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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
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

    /**
     * Point d'entree unique de GET /api/transactions : choisit le filtre a
     * appliquer selon les parametres fournis, et refuse (400) une combinaison
     * incoherente plutot que de l'ignorer en silence et renvoyer toutes les
     * transactions (ex : "from" sans "to", ou un operateur sans montant).
     */
    public List<TransactionDTO> search(Long accountId, LocalDate from, LocalDate to,
                                       String amountOperator, BigDecimal amountValue) {
        boolean hasAmountFilter = validateAmountFilter(amountOperator, amountValue);
        boolean hasPeriod = validatePeriod(from, to);
        if (hasAmountFilter && hasPeriod) {
            throw new IllegalArgumentException("La recherche par montant ne peut pas être combinée à une période.");
        }
        if (hasAmountFilter) {
            return findByAmountFilter(accountId, amountOperator, amountValue);
        }
        if (hasPeriod) {
            return accountId != null ? findByAccountAndPeriod(accountId, from, to) : findByPeriod(from, to);
        }
        return accountId != null ? findByAccount(accountId) : findAll();
    }

    private static boolean validateAmountFilter(String amountOperator, BigDecimal amountValue) {
        if ((amountOperator == null) != (amountValue == null)) {
            throw new IllegalArgumentException("La recherche par montant attend à la fois un opérateur et un montant.");
        }
        return amountOperator != null;
    }

    private static boolean validatePeriod(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("Une période attend à la fois une date de début et une date de fin.");
        }
        if (from != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Période invalide : la date de début est postérieure à la date de fin.");
        }
        return from != null;
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

    public List<TransactionDTO> findByAccountAndPeriod(Long accountId, LocalDate from, LocalDate to) {
        return transactionRepository
                .findByAccountIdAndDateBetween(accountId, from, to)
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

    // Transactionnel : applyPayload peut creer un tiers a la volee (voir
    // ServiceTiers#resolveOrCreate) ; il ne doit pas survivre seul si
    // l'enregistrement de la transaction echoue ensuite.
    @Transactional
    public TransactionDTO create(TransactionDTO payload) {
        Transaction transaction = new Transaction();
        applyPayload(transaction, payload);
        // Fixe a la creation seulement (jamais par update) : permet a
        // l'annulation d'une suppression de recreer un ajustement de solde
        // tel quel, au lieu d'une transaction ordinaire comptee comme une
        // depense ou un revenu.
        transaction.setBalanceAdjustment(payload.isBalanceAdjustment());
        // Idem pour le libelle bancaire d'une transaction importee restauree.
        transaction.setBankLabel(blankToNull(payload.getBankLabel()));
        Transaction saved = transactionRepository.save(transaction);
        return transactionMapper.toDTO(saved);
    }

    // Transactionnel : applyPayload peut creer un tiers a la volee, qui ne doit
    // pas survivre seul si l'enregistrement echoue. Le nettoyage de l'ancien
    // tiers n'a lieu qu'apres le commit (voir prunePayeesAfterCommit).
    @Transactional
    public TransactionDTO update(Long id, TransactionDTO payload) {
        Transaction existing = findEntityById(id);
        Transaction twin = existing.getTransferTransaction();
        Tiers previousPayee = existing.getPayee();

        applyPayload(existing, payload);
        if (twin != null) {
            syncTransferTwin(existing, twin);
        }
        Transaction saved = transactionRepository.save(existing);

        if (previousPayee != null && !previousPayee.equals(saved.getPayee())) {
            prunePayeesAfterCommit(List.of(previousPayee));
        }

        return transactionMapper.toDTO(saved);
    }

    /**
     * Un virement est une seule operation representee par deux transactions
     * (voir createTransfer) : la modification d'un cote est repercutee sur
     * l'autre pour qu'ils ne se desynchronisent jamais — meme date, montant
     * oppose, meme memo, meme statut de validation. Le compte de chaque cote
     * reste modifiable (correction d'un compte source/destination mal choisi),
     * tant que les deux comptes restent distincts et de meme devise. Tiers,
     * categorie et marqueurs restent propres a chaque cote.
     */
    private void syncTransferTwin(Transaction edited, Transaction twin) {
        if (edited.getAmount().signum() == 0) {
            throw new IllegalArgumentException("Le montant d'un virement ne peut pas être nul.");
        }
        if (edited.getAccount().getId().equals(twin.getAccount().getId())) {
            throw new IllegalArgumentException("Le compte source et le compte destination doivent etre differents");
        }
        ensureSameCurrency(edited.getAccount(), twin.getAccount());

        edited.setPaymentMode(ModePaiement.TRANSFER);
        twin.setDate(edited.getDate());
        twin.setAmount(edited.getAmount().negate());
        twin.setMemo(edited.getMemo());
        twin.setValidated(edited.isValidated());
        transactionRepository.save(twin);
    }

    /** Pas de taux de change gere : un virement relie toujours deux comptes de meme devise. */
    private static void ensureSameCurrency(Compte first, Compte second) {
        String firstCurrency = first.getCurrency();
        String secondCurrency = second.getCurrency();
        if (firstCurrency == null || secondCurrency == null || !firstCurrency.equalsIgnoreCase(secondCurrency)) {
            throw new IllegalArgumentException(
                    "Les virements entre comptes de devises différentes ne sont pas pris en charge"
            );
        }
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

        if (original.isInternalTransfer()) {
            throw new IllegalArgumentException("Un virement ne peut pas etre divise");
        }
        // Ni depense ni revenu : rien a repartir, et la 2e partie, creee sans
        // le marqueur, aurait compte a tort comme une depense/un revenu.
        if (original.isBalanceAdjustment()) {
            throw new IllegalArgumentException("Un ajustement de solde ne peut pas etre divise");
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

        // Garde le montant du releve pour la detection de doublons a l'import
        // (voir Transaction#splitFromAmount) ; une 2e division conserve celui
        // de la toute premiere.
        if (original.getSplitFromAmount() == null) {
            original.setSplitFromAmount(totalAmount);
        }
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

        prunePayeesAfterCommit(orphanCandidates);

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
            setValidatedWithTransferTwin(t, payload.isValidated());
        }
    }

    /**
     * Statut de validation applique aussi a la jumelle d'un virement (meme
     * regle que toggleValidated) : les deux cotes d'une meme operation sont
     * toujours rapproches ensemble. Jumelle chargee par fetch join (voir
     * findAllByIdWithTransferTransaction), enregistree par la transaction JPA.
     */
    private static void setValidatedWithTransferTwin(Transaction t, boolean validated) {
        t.setValidated(validated);
        if (t.getTransferTransaction() != null) {
            t.getTransferTransaction().setValidated(validated);
        }
    }

    /**
     * Le mode de paiement d'un cote de virement interne reste TRANSFER : il
     * n'existe que par sa paire (voir createTransfer / syncTransferTwin), que
     * l'edition en masse ne doit pas denaturer. Pour toute autre transaction,
     * « Virement » est un mode de paiement ordinaire et s'applique normalement.
     */
    private void applyBulkPaymentMode(Transaction t, ModePaiement requested) {
        if (t.isInternalTransfer()) {
            log.info("Changement de mode de paiement ignore pour la transaction {} : cote d'un virement interne", t.getId());
            return;
        }
        t.setPaymentMode(requested);
    }

    /** Resout/cree le nouveau tiers depuis le texte fourni et l'affecte a la transaction ; releve l'ancien tiers s'il change (voir prunePayeesAfterCommit). */
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

    /**
     * Nettoyage "best effort" des anciens tiers devenus orphelins (voir
     * ServiceTiers#deleteIfOrphan), reporte APRES le commit de la transaction
     * en cours. L'executer dedans etait trompeur : une exception, meme
     * rattrapee, marquait toute la transaction rollback-only (la suppression
     * ou modification echouait alors au commit avec une 500), et le DELETE du
     * tiers n'etait de toute facon envoye en base qu'au commit, hors du
     * try/catch. Sans transaction active, le nettoyage est fait tout de suite.
     */
    private void prunePayeesAfterCommit(Collection<Tiers> previousPayees) {
        List<Long> payeeIds = previousPayees.stream()
                .filter(Objects::nonNull)
                .map(Tiers::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (payeeIds.isEmpty()) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            prunePayees(payeeIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                prunePayees(payeeIds);
            }
        });
    }

    /** Un tiers a la fois, chacun dans sa propre transaction : l'echec de l'un n'empeche pas le nettoyage des autres. */
    private void prunePayees(List<Long> payeeIds) {
        for (Long payeeId : payeeIds) {
            try {
                payeeService.deleteIfOrphan(payeeId);
            } catch (RuntimeException e) {
                log.warn("Echec du nettoyage du tiers {} devenu orphelin : {}", payeeId, e.getMessage(), e);
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

        Map<Long, Transaction> byId = new LinkedHashMap<>();
        byId.put(existing.getId(), existing);
        // Inclut la jumelle d'un virement, supprimee avec elle.
        List<Transaction> transactions = unlinkTransferPairs(byId);

        transactions.forEach(this::recordDeletion);
        transactionRepository.deleteAll(transactions);

        // Tiers de CHAQUE transaction supprimee (jumelle comprise), comme
        // deleteTransactions : sinon le tiers propre a l'autre cote d'un
        // virement restait orphelin dans la liste des tiers.
        prunePayeesAfterCommit(transactions.stream().map(Transaction::getPayee).toList());
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

        prunePayeesAfterCommit(orphanCandidates);
        log.info("Suppression en masse de {} transaction(s) (empreinte de suppression : {})", transactions.size(), recordTombstone);

        return transactions.size();
    }

    /**
     * Enregistre une empreinte de la transaction avant sa suppression (voir
     * TransactionSupprimee), pour que ServiceImport#isAlreadyKnown la reconnaisse
     * et evite de la reimporter si le releve bancaire source la contient
     * encore lors d'un futur import — sans quoi une transaction supprimee
     * manuellement (ex : paire "depense remboursee", voir
     * findReimbursementGroups) reapparaissait au prochain import.
     */
    private void recordDeletion(Transaction t) {
        TransactionSupprimee tombstone = new TransactionSupprimee();
        tombstone.setAccountId(t.getAccount().getId());
        tombstone.setDate(t.getDate());
        tombstone.setAmount(t.importKeyAmount());
        tombstone.setPayeeName(t.getPayee() != null ? t.getPayee().getName() : null);
        tombstone.setBankLabel(t.getBankLabel());
        deletedTransactionRepository.save(tombstone);
    }

    /** Passe toutes les transactions en attente au statut "validee". Renvoie le nombre concerne. */
    @Transactional
    public int validateAllPending() {
        return transactionRepository.validateAllPending();
    }

    /**
     * Bascule le statut valide/en attente d'une transaction.
     * Pour un virement, le statut est toujours applique aux deux cotes :
     * ils constituent une seule operation financiere et ne doivent pas
     * pouvoir etre rapproches independamment.
     */
    @Transactional
    public TransactionDTO toggleValidated(Long id) {
        Transaction t = findEntityById(id);
        boolean validated = !t.isValidated();
        t.setValidated(validated);

        Transaction pair = t.getTransferTransaction();
        if (pair != null) {
            pair.setValidated(validated);
            transactionRepository.save(pair);
        }

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
                .filter(t -> !t.isInternalTransfer() && !t.isBalanceAdjustment())
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
     *   ServiceImport#isAlreadyKnown) — meme compte, meme date + montant +
     *   tiers canonique (insensible a la casse). Occurrences interchangeables.
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
                .filter(t -> !t.isDuplicateIgnored() && !t.isBalanceAdjustment())
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
                // Meme tiers partout : deja liste parmi les "certains" (meme cle).
                .filter(group -> !samePayeeForAll(group) && payeesMayBeSameMerchant(group))
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
                .filter(t -> !t.isInternalTransfer() && !t.isBalanceAdjustment())
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
     * "certains" : compte + date + montant + tiers canonique (voir CleDoublon).
     */
    private String duplicateGroupKey(Transaction t) {
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
     * Groupe "large" (meme compte, date, montant) aux tiers differents : n'est
     * un doublon probable que si deux de ses transactions peuvent designer le
     * meme commercant — l'une sans tiers, ou le motif de reconnaissance de
     * l'un des tiers retrouvant le nom de l'autre (cas vise : la meme
     * operation importee deux fois, une fois sous un tiers renomme, une fois
     * sous le libelle brut de la banque). Deux tiers distincts deja reconnus
     * (ex : « Pharmacie » et « Docteur Trillot », 30 € le meme jour) sont deux
     * vraies operations : les proposer en doublon a conduit a en supprimer une
     * a tort, que l'import refusait ensuite de reintegrer.
     */
    private static boolean payeesMayBeSameMerchant(List<Transaction> group) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                if (sameBankLabel(group.get(i), group.get(j))
                        || mayBeSameMerchant(group.get(i).getPayee(), group.get(j).getPayee())) return true;
            }
        }
        return false;
    }

    /** Meme libelle de releve (voir Transaction#bankLabel) : meme operation bancaire, quels que soient les tiers affectes depuis. */
    private static boolean sameBankLabel(Transaction first, Transaction second) {
        return first.getBankLabel() != null && first.getBankLabel().equalsIgnoreCase(second.getBankLabel());
    }

    private static boolean mayBeSameMerchant(Tiers first, Tiers second) {
        if (first == null || second == null) return true;
        return ServiceTiers.matchesPatternField(first.getPattern(), second.getName())
                || ServiceTiers.matchesPatternField(second.getPattern(), first.getName());
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
     * Aligne le solde calcule d'un compte sur sa valeur reelle a une date
     * (ex : placement dont la valeur suit la bourse, livret sans mouvement
     * saisi) : cree une transaction "Ajustement de solde" du montant de
     * l'ecart, validee d'office (la valeur vient de l'utilisateur), et exclue
     * des rapports de depenses/revenus (voir Transaction#balanceAdjustment).
     * Le solde calcule inclut toutes les transactions jusqu'a `date` incluse,
     * meme regle que le rapprochement (voir ServiceRapprochement).
     */
    @Transactional
    public TransactionDTO adjustAccountBalance(Long accountId, LocalDate date, BigDecimal actualBalance) {
        if (date == null) {
            throw new IllegalArgumentException("La date est obligatoire.");
        }
        if (date.isAfter(LocalDate.now(ZoneId.systemDefault()))) {
            throw new IllegalArgumentException("La date ne peut pas être dans le futur.");
        }
        if (actualBalance == null) {
            throw new IllegalArgumentException("Le solde réel est obligatoire.");
        }
        Compte account = accountService.findById(accountId);
        ServiceCompte.ensureNotArchived(account);

        BigDecimal computedBalance = account.getInitialBalance()
                .add(transactionRepository.sumAmountByAccountIdAsOf(accountId, date));
        BigDecimal gap = actualBalance.subtract(computedBalance);
        if (gap.signum() == 0) {
            throw new IllegalArgumentException("Le solde est déjà de " + computedBalance + " à cette date : aucun ajustement nécessaire.");
        }

        Transaction adjustment = new Transaction();
        adjustment.setAccount(account);
        adjustment.setDate(date);
        adjustment.setAmount(gap);
        adjustment.setPaymentMode(ModePaiement.NONE);
        adjustment.setValidated(true);
        adjustment.setBalanceAdjustment(true);
        Transaction saved = transactionRepository.save(adjustment);
        log.info("Ajustement du solde du compte {} au {} : {} (solde réel {})", accountId, date, gap, actualBalance);
        return transactionMapper.toDTO(saved);
    }

    /**
     * Cree un virement entre deux comptes : deux transactions liees
     * (debit sur le compte source, credit sur le compte destination),
     * chacune reliee a l'autre via transferTransaction, et marquees
     * ModePaiement.TRANSFER ; leur liaison les exclut des rapports de depenses/revenus
     * (voir Transaction#isInternalTransfer).
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
        ServiceCompte.ensureNotArchived(from);
        ServiceCompte.ensureNotArchived(to);
        ensureSameCurrency(from, to);
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
        // Verifies avant toute resolution/creation de tiers (qui utilise le
        // montant) : sans quoi l'erreur ne remontait qu'a l'insertion SQL.
        if (payload.getDate() == null) {
            throw new IllegalArgumentException("La date est obligatoire");
        }
        if (payload.getAmount() == null) {
            throw new IllegalArgumentException("Le montant est obligatoire");
        }

        Compte account = accountService.findById(payload.getAccountId());
        // Modifier une transaction deja presente sur un compte archive reste
        // permis ; seule l'arrivee d'une transaction (creation, ou
        // deplacement depuis un autre compte) y est refusee.
        boolean staysOnSameAccount = transaction.getAccount() != null
                && transaction.getAccount().getId().equals(account.getId());
        if (!staysOnSameAccount) {
            ServiceCompte.ensureNotArchived(account);
        }
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
        transaction.setPeriodicExpense(payload.isPeriodicExpense());
        transaction.setExceptional(payload.isExceptional());
        // Une transaction saisie/modifiee manuellement est consideree comme validee ;
        // seules les transactions importees/synchronisees demarrent "en attente".
        transaction.setValidated(payload.isValidated());

        // Categorie appliquee TELLE QUELLE depuis le payload, comme les autres
        // champs (memo...) : payload.getCategoryId() == null signifie
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

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
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

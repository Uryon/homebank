package com.homebank.service;

import com.homebank.dto.TransactionBulkUpdateDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.dto.DemandeAnnulationDivisionDTO;
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
import com.homebank.utils.LongueurTexte;
import com.homebank.utils.ModePaiement;
import com.homebank.utils.Montant;
import com.homebank.utils.Periode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Toutes les transactions, des plus recentes aux plus anciennes (GET /api/transactions, filtrees cote frontend). */
    @Transactional(readOnly = true)
    public List<TransactionDTO> findAll() {
        return transactionRepository.findAllByOrderByDateDescIdDesc()
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
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
        Transaction saved = transactionRepository.save(transaction);
        log.info("Transaction creee : {} (compte {})", saved.getId(), saved.getAccount().getId());
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
        log.info("Transaction modifiee : {}{}", id, twin != null ? " (et la jumelle du virement)" : "");

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
            throw new IllegalArgumentException("Le compte source et le compte destination doivent être différents.");
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
            throw new IllegalArgumentException("Un virement ne peut pas être divisé.");
        }
        // Ni depense ni revenu : rien a repartir, et la 2e partie, creee sans
        // le marqueur, aurait compte a tort comme une depense/un revenu.
        if (original.isBalanceAdjustment()) {
            throw new IllegalArgumentException("Un ajustement de solde ne peut pas être divisé.");
        }

        BigDecimal totalAmount = original.getAmount();
        // Arrondi AVANT le calcul de la 2e partie : la base arrondit chaque
        // montant, et 10,005 + 9,995 y devenait 10,01 + 10,00 pour 20,00 d'origine.
        BigDecimal firstAmount = Montant.auCentime(payload.getFirstAmount(), "Le montant de la 1re partie");
        if (firstAmount == null || firstAmount.signum() == 0) {
            throw new IllegalArgumentException("Le montant de la 1re partie est obligatoire et ne peut pas être nul.");
        }
        if (firstAmount.signum() != totalAmount.signum()) {
            throw new IllegalArgumentException("Le montant de la 1re partie doit avoir le même signe que le montant d'origine.");
        }
        if (firstAmount.abs().compareTo(totalAmount.abs()) >= 0) {
            throw new IllegalArgumentException(
                    "Le montant de la 1re partie doit être strictement inférieur, en valeur absolue, au montant d'origine.");
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
     * Annule une division en une seule operation : en deux appels separes, un
     * echec entre les deux laissait la 1re partie seule, et le solde du compte
     * amputé de la 2e. Refuse si les parties ne totalisent plus le montant
     * d'origine (modifiees entre-temps). Pas d'empreinte de suppression : la
     * 2e partie n'a jamais figure sur un releve.
     */
    @Transactional
    public TransactionDTO undoSplit(Long id, DemandeAnnulationDivisionDTO request) {
        TransactionDTO previous = request.getOriginal();
        Long secondPartId = request.getSecondPartId();
        if (previous == null || secondPartId == null || secondPartId.equals(id)) {
            throw new IllegalArgumentException("La transaction d'origine et sa 2e partie sont obligatoires.");
        }
        BigDecimal originalAmount = verifierChampsObligatoires(previous);
        Transaction first = findEntityById(id);
        Transaction second = findEntityById(secondPartId);
        boolean sameAccount = first.getAccount().getId().equals(second.getAccount().getId());
        if (!sameAccount || first.getAmount().add(second.getAmount()).compareTo(originalAmount) != 0) {
            throw new IllegalStateException("Cette division ne peut plus être annulée : ses parties ont été modifiées.");
        }

        transactionRepository.delete(second);
        applyPayload(first, previous);
        first.setSplitFromAmount(Montant.auCentime(previous.getSplitFromAmount(), "Le montant d'origine"));
        Transaction saved = transactionRepository.save(first);
        log.info("Division annulee : transaction {} (2e partie {} supprimee)", id, secondPartId);

        prunePayeesAfterCommit(Collections.singletonList(second.getPayee()));
        return transactionMapper.toDTO(saved);
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
            throw new IllegalArgumentException("Aucune transaction sélectionnée.");
        }

        // Meme regle qu'a la modification d'une seule transaction (voir
        // applyPayload) : refuse plutot que tronque en silence par la
        // creation du tiers.
        if (payload.isPayeeProvided() && payload.getPayee() != null) {
            LongueurTexte.verifier(payload.getPayee().trim(), "Le nom du tiers");
        }
        Categorie category = payload.isCategoryIdProvided() && payload.getCategoryId() != null
                ? categoryService.findById(payload.getCategoryId())
                : null;
        Tiers payeeById = payload.isPayeeProvided() && payload.getPayeeId() != null
                ? payeeService.findEntityById(payload.getPayeeId())
                : null;

        List<Transaction> transactions = transactionRepository.findAllByIdWithTransferTransaction(ids);
        ensureAllFound(ids, transactions);
        // Charges une seule fois : relire tous les tiers pour chaque ligne
        // rendait l'operation de plus en plus lente avec la selection.
        boolean resolvesPayeeText = payload.isPayeeProvided() && payeeById == null
                && payload.getPayee() != null && !payload.getPayee().isBlank();
        List<Tiers> knownPayees = resolvesPayeeText ? new ArrayList<>(payeeService.findAllForMatching()) : List.of();
        List<Tiers> orphanCandidates = new ArrayList<>();
        for (Transaction t : transactions) {
            applyBulkFields(t, payload, category, payeeById, knownPayees, orphanCandidates);
        }

        List<Transaction> saved = transactionRepository.saveAll(transactions);
        log.info("Modification en masse de {} transaction(s)", saved.size());

        prunePayeesAfterCommit(orphanCandidates);

        return saved.stream().map(transactionMapper::toDTO).toList();
    }

    /**
     * Une selection contenant une transaction deja supprimee (autre onglet,
     * import...) etait appliquee aux seules transactions trouvees, sans le
     * signaler : l'ecran affichait ensuite un nombre de modifications faux.
     */
    private static void ensureAllFound(List<Long> ids, List<Transaction> found) {
        Set<Long> foundIds = found.stream().map(Transaction::getId).collect(Collectors.toSet());
        List<Long> missing = ids.stream().filter(id -> !foundIds.contains(id)).distinct().toList();
        if (!missing.isEmpty()) {
            throw new NoSuchElementException("Transaction(s) introuvable(s) : " + missing
                    + ". Rechargez la page : elles ont peut-être été supprimées entre-temps.");
        }
    }

    /** Applique a une transaction les seuls champs marques "...Provided" dans payload (voir bulkUpdate). */
    private void applyBulkFields(Transaction t, TransactionBulkUpdateDTO payload, Categorie category, Tiers payeeById,
                                 List<Tiers> knownPayees, List<Tiers> orphanCandidates) {
        if (payload.isCategoryIdProvided()) {
            t.setCategory(category);
        }

        if (payload.isPayeeProvided()) {
            Tiers resolved = payeeById != null ? payeeById : resolveBulkPayee(t, payload.getPayee(), knownPayees);
            replacePayee(t, resolved, orphanCandidates);
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
     * Statut de validation applique aussi a la jumelle d'un virement
     * (bulkUpdate, toggleValidated) : les deux cotes d'une meme operation sont
     * toujours rapproches ensemble. La jumelle, entite geree, est enregistree
     * par la transaction JPA de l'appelant (qui doit donc etre @Transactional).
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
        t.setPaymentMode(paymentModeOrNone(requested));
    }

    /** Resout/cree le tiers depuis le texte fourni (null si vide). */
    private Tiers resolveBulkPayee(Transaction t, String payeeText, List<Tiers> knownPayees) {
        return (payeeText != null && !payeeText.isBlank())
                ? payeeService.resolveOrCreate(payeeText, t.getAmount(), knownPayees)
                : null;
    }

    /** Affecte le tiers a la transaction ; releve l'ancien tiers s'il change (voir prunePayeesAfterCommit). */
    private static void replacePayee(Transaction t, Tiers resolvedPayee, List<Tiers> orphanCandidates) {
        Tiers previousPayee = t.getPayee();
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

    /**
     * Tous les tiers d'un coup ; si cela echoue, un a la fois, chacun dans sa
     * propre transaction : l'echec de l'un n'empeche pas le nettoyage des autres.
     */
    private void prunePayees(List<Long> payeeIds) {
        if (payeeIds.size() > 1 && prunePayeesTogether(payeeIds)) return;
        for (Long payeeId : payeeIds) {
            try {
                payeeService.deleteIfOrphan(payeeId);
            } catch (RuntimeException e) {
                log.warn("Echec du nettoyage du tiers {} devenu orphelin : {}", payeeId, e.getMessage(), e);
            }
        }
    }

    private boolean prunePayeesTogether(List<Long> payeeIds) {
        try {
            payeeService.deleteOrphans(payeeIds);
            return true;
        } catch (RuntimeException e) {
            log.warn("Nettoyage groupe de {} tiers en echec, reprise un par un : {}", payeeIds.size(), e.getMessage());
            return false;
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
        log.info("Transaction supprimee : {}{}", id, transactions.size() > 1 ? " (avec la jumelle du virement)" : "");

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
     * <p>
     * Avec accountId, seules les transactions en attente de ce compte sont
     * concernees (liste filtree par compte cote frontend : le nombre annonce
     * dans la confirmation doit etre celui reellement supprime) ; la jumelle
     * en attente d'un virement, sur un autre compte, part avec elle.
     */
    @Transactional
    public int deleteAllPending(Long accountId) {
        List<Transaction> pending = findPendingWithTransferTransaction(accountId);
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
            log.warn("{} transaction(s) de virement en attente ignorees par le nettoyage : leur jumelle est deja validee (suppression manuelle si besoin)", skipped);
        }
        return pendingIds.isEmpty() ? 0 : deleteTransactions(pendingIds, false);
    }

    /** Transactions en attente de tous les comptes (accountId null) ou d'un seul, jumelle de virement chargee. */
    private List<Transaction> findPendingWithTransferTransaction(Long accountId) {
        return accountId == null
                ? transactionRepository.findByValidatedFalseWithTransferTransaction()
                : transactionRepository.findPendingByAccountIdWithTransferTransaction(accountId);
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
     * Annule une creation (saisie, virement, ajustement, 2e partie d'une
     * division) ou un import (voir ServiceImport.ImportResult#importedIds) :
     * supprime les transactions SANS empreinte de suppression. Ce n'est pas
     * un refus de ces operations : la ligne correspondante d'un releve doit
     * rester importable. Les tiers crees avec elles et devenus orphelins
     * partent aussi ; les categories creees par un import restent.
     */
    @Transactional
    public int undoCreation(List<Long> ids) {
        int deleted = deleteTransactions(ids, false);
        log.info("Creation annulee : {} transaction(s) supprimee(s)", deleted);
        return deleted;
    }

    /**
     * Suppression en masse factorisee entre deleteBulk, deleteAllPending et undoCreation
     * (voir leur javadoc pour la difference de comportement sur recordTombstone).
     * Comme delete(Long) : la transaction jumelle d'un virement selectionne
     * est toujours emportee avec lui, meme si elle n'a pas ete selectionnee
     * elle-meme, pour ne jamais laisser un virement partiellement supprime
     * (les deux se referencent mutuellement, voir createTransfer).
     */
    private int deleteTransactions(List<Long> ids, boolean recordTombstone) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction sélectionnée.");
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
     * ServiceDoublon#findReimbursementGroups) reapparaissait au prochain import.
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

    /**
     * Passe au statut "validee" les transactions en attente de tous les
     * comptes (accountId null : simple UPDATE en masse), ou du seul compte
     * filtre. Dans ce cas, chaque transaction est validee avec sa jumelle de
     * virement (voir setValidatedWithTransferTwin), meme si celle-ci est sur
     * un autre compte : un UPDATE restreint au compte laisserait l'autre cote
     * en attente. Renvoie le nombre de transactions validees (jumelles
     * d'un autre compte non comptees).
     */
    @Transactional
    public int validateAllPending(Long accountId) {
        if (accountId == null) {
            int validated = transactionRepository.validateAllPending();
            log.info("Validation de {} transaction(s) en attente, tous comptes", validated);
            return validated;
        }
        List<Transaction> pending = transactionRepository.findPendingByAccountIdWithTransferTransaction(accountId);
        pending.forEach(t -> setValidatedWithTransferTwin(t, true));
        log.info("Validation de {} transaction(s) en attente du compte {}", pending.size(), accountId);
        return pending.size();
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
        setValidatedWithTransferTwin(t, !t.isValidated());
        log.info("Transaction {} {}", id, t.isValidated() ? "validee" : "remise en attente");
        return transactionMapper.toDTO(transactionRepository.save(t));
    }

    /** Bascule le statut "depense periodique" d'une transaction. */
    @Transactional
    public TransactionDTO togglePeriodicExpense(Long id) {
        Transaction t = findEntityById(id);
        t.setPeriodicExpense(!t.isPeriodicExpense());
        log.info("Transaction {} {} comme depense periodique", id, t.isPeriodicExpense() ? "marquee" : "retiree");
        return transactionMapper.toDTO(transactionRepository.save(t));
    }

    /** Bascule le statut "exceptionnelle" (exclue des tableaux de bord) d'une transaction. */
    @Transactional
    public TransactionDTO toggleExceptional(Long id) {
        Transaction t = findEntityById(id);
        t.setExceptional(!t.isExceptional());
        log.info("Transaction {} {} comme exceptionnelle", id, t.isExceptional() ? "marquee" : "retiree");
        return transactionMapper.toDTO(transactionRepository.save(t));
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
        Periode.verifierAnnee(date, "La date");
        BigDecimal actual = Montant.auCentime(actualBalance, "Le solde réel");
        if (actual == null) {
            throw new IllegalArgumentException("Le solde réel est obligatoire.");
        }
        Compte account = accountService.findById(accountId);
        ServiceCompte.ensureNotArchived(account);

        BigDecimal computedBalance = account.getInitialBalance()
                .add(transactionRepository.sumAmountByAccountIdAsOf(accountId, date));
        BigDecimal gap = actual.subtract(computedBalance);
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
        log.info("Ajustement du solde du compte {} au {} : {} (solde réel {})", accountId, date, gap, actual);
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
            throw new IllegalArgumentException("Les deux comptes sont obligatoires.");
        }
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new IllegalArgumentException("Le compte source et le compte destination doivent être différents.");
        }
        BigDecimal amount = Montant.auCentime(request.getAmount(), "Le montant du virement");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Le montant du virement doit être positif.");
        }

        Compte from = accountService.findById(request.getFromAccountId());
        Compte to = accountService.findById(request.getToAccountId());
        ServiceCompte.ensureNotArchived(from);
        ServiceCompte.ensureNotArchived(to);
        ensureSameCurrency(from, to);
        LocalDate date = request.getDate() != null ? request.getDate() : LocalDate.now(ZoneId.systemDefault());
        Periode.verifierAnnee(date, "La date du virement");
        boolean validated = !Boolean.FALSE.equals(request.getValidated());

        Transaction debit = new Transaction();
        debit.setAccount(from);
        debit.setDate(date);
        debit.setAmount(amount.negate());
        String memo = sanitizeMemo(request.getMemo());
        debit.setMemo(memo);
        debit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setValidated(validated);

        Transaction credit = new Transaction();
        credit.setAccount(to);
        credit.setDate(date);
        credit.setAmount(amount);
        credit.setMemo(memo);
        credit.setPaymentMode(ModePaiement.TRANSFER);
        credit.setValidated(validated);

        Transaction savedDebit = transactionRepository.save(debit);
        credit.setTransferTransaction(savedDebit);
        Transaction savedCredit = transactionRepository.save(credit);
        savedDebit.setTransferTransaction(savedCredit);
        Transaction finalDebit = transactionRepository.save(savedDebit);
        log.info("Virement cree du compte {} vers le compte {} : transactions {} et {}",
                from.getId(), to.getId(), finalDebit.getId(), savedCredit.getId());

        return List.of(transactionMapper.toDTO(finalDebit), transactionMapper.toDTO(savedCredit));
    }

    /**
     * Un montant nul a la saisie est presque toujours une faute de frappe
     * (champ vide valide trop vite) : la transaction ne changeait aucun solde
     * et restait affichee comme une operation reelle. Une transaction deja a
     * 0 (ex : ligne importee d'un releve) reste modifiable sans changer son
     * montant, pour pouvoir la categoriser ou la supprimer.
     */
    private static void ensureNonZeroAmount(Transaction transaction, BigDecimal amount) {
        boolean alreadyZero = transaction.getAmount() != null && transaction.getAmount().signum() == 0;
        if (amount.signum() == 0 && !alreadyZero) {
            throw new IllegalArgumentException("Le montant ne peut pas être nul.");
        }
    }

    /**
     * Compte, date et montant sont exiges avant toute resolution/creation de
     * tiers (qui utilise le montant) : sans quoi l'erreur ne remontait qu'a
     * l'insertion SQL. Renvoie le montant arrondi au centime.
     */
    static BigDecimal verifierChampsObligatoires(TransactionDTO payload) {
        if (payload.getAccountId() == null) {
            throw new IllegalArgumentException("Le compte est obligatoire");
        }
        if (payload.getDate() == null) {
            throw new IllegalArgumentException("La date est obligatoire");
        }
        Periode.verifierAnnee(payload.getDate(), "La date");
        BigDecimal amount = Montant.auCentime(payload.getAmount(), "Le montant");
        if (amount == null) {
            throw new IllegalArgumentException("Le montant est obligatoire");
        }
        return amount;
    }

    /**
     * Libelle calcule qu'affiche une transaction sans tiers (« Virement vers
     * X », « Ajustement de solde », voir MappeurTransaction#payeeLabel), null
     * sinon. Le formulaire d'edition le renvoie dans le champ tiers : sans ce
     * repere, il etait pris pour un nom et creait un faux tiers.
     */
    private static String computedPayeeLabel(Transaction transaction) {
        return transaction.getId() != null && transaction.getPayee() == null
                ? MappeurTransaction.payeeLabel(transaction)
                : null;
    }

    private void applyPayload(Transaction transaction, TransactionDTO payload) {
        BigDecimal amount = verifierChampsObligatoires(payload);
        // Lu avant les modifications : le libelle depend du signe du montant.
        String computedLabel = computedPayeeLabel(transaction);

        Compte account = accountService.findById(payload.getAccountId());
        ensureNonZeroAmount(transaction, amount);
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
        transaction.setAmount(amount);

        // Le tiers saisi/modifie passe par la meme reconnaissance/creation
        // automatique que l'import (voir ServiceTiers#resolveOrCreate), afin
        // que l'onglet "Tiers" reste toujours a jour avec les tiers realement
        // utilises sur les transactions, meme lors d'une saisie manuelle.
        String payeeText = payload.getPayee();
        if (payeeText != null) {
            LongueurTexte.verifier(payeeText.trim(), "Le nom du tiers");
        }
        boolean realPayeeText = payeeText != null && !payeeText.isBlank() && !payeeText.equals(computedLabel);
        transaction.setPayee(realPayeeText ? resolveEnteredPayee(transaction, payeeText, amount) : null);

        transaction.setMemo(sanitizeMemo(payload.getMemo()));
        transaction.setPaymentMode(paymentModeOrNone(payload.getPaymentMode()));
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

    /**
     * Un tiers inchange reste celui de la transaction : le formulaire renvoie
     * toujours son nom, et le reconnaitre a nouveau pouvait, pour un simple
     * changement de memo, basculer vers un homonyme ou vers un autre tiers.
     * Un tiers lie a un autre montant est en revanche re-evalue.
     */
    private Tiers resolveEnteredPayee(Transaction transaction, String payeeText, BigDecimal amount) {
        Tiers current = transaction.getPayee();
        boolean unchanged = current != null && payeeText.trim().equals(current.getName())
                && (current.getAmount() == null || current.getAmount().compareTo(amount.abs()) == 0);
        return unchanged ? current : payeeService.resolveOrCreate(payeeText, amount);
    }

    /**
     * Mode de paiement absent = « Aucun », comme dans les formulaires : la
     * colonne est obligatoire, et un mode manquant finissait en 409 generique
     * (contrainte NOT NULL) au lieu d'etre enregistre.
     */
    static ModePaiement paymentModeOrNone(ModePaiement mode) {
        return mode != null ? mode : ModePaiement.NONE;
    }

    /** Remplace un memo litteralement "(NULL)"/"NULL" ou vide par null. */
    static String sanitizeMemo(String memo) {
        if (memo == null) return null;
        String trimmed = memo.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("(NULL)") || trimmed.equalsIgnoreCase("NULL")) {
            return null;
        }
        LongueurTexte.verifier(trimmed, "Le mémo");
        return trimmed;
    }
}

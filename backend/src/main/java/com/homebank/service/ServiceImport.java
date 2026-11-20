package com.homebank.service;

import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Categorie;
import com.homebank.model.LigneImportee;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotLigneImportee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import com.homebank.utils.LongueurTexte;
import com.homebank.utils.Montant;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ServiceImport {

    private static final Logger log = LoggerFactory.getLogger(ServiceImport.class);

    private final ServiceCompte accountService;
    private final ServiceCategorie categoryService;
    private final DepotTransaction transactionRepository;
    private final DepotTransactionSupprimee deletedTransactionRepository;
    private final DepotLigneImportee importedLineRepository;
    private final ServiceTiers payeeService;
    private final ServiceDernierImport dernierImportService;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    /** Lignes enregistrees par transaction lors d'un import (voir processOperations). */
    private static final int IMPORT_BATCH_SIZE = 200;

    public ServiceImport(ServiceCompte accountService, ServiceCategorie categoryService,
                          DepotTransaction transactionRepository,
                          DepotTransactionSupprimee deletedTransactionRepository,
                          DepotLigneImportee importedLineRepository,
                          ServiceTiers payeeService,
                          ServiceDernierImport dernierImportService,
                          PlatformTransactionManager transactionManager,
                          EntityManager entityManager) {
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionRepository = transactionRepository;
        this.deletedTransactionRepository = deletedTransactionRepository;
        this.importedLineRepository = importedLineRepository;
        this.payeeService = payeeService;
        this.dernierImportService = dernierImportService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.entityManager = entityManager;
    }

    // Champs prives (accessibles depuis ServiceImport, classe englobante) :
    // seuls les getters (utilises par Jackson pour la reponse JSON) exposent
    // l'etat en dehors du service.
    @Getter
    public static class ImportResult {
        private int imported;
        private int duplicates;
        private int skipped;
        private final List<String> errors = new ArrayList<>();
        /**
         * Lignes ignorees (comptees dans duplicates) parce qu'une operation
         * identique avait ete supprimee a la main depuis un import precedent :
         * listees a part pour que l'utilisateur voie qu'une ligne de son releve
         * n'a pas ete reprise, et puisse la ressaisir si la suppression etait
         * une erreur (sinon l'ecart n'apparaissait qu'au rapprochement).
         */
        private final List<LigneDejaSupprimee> previouslyDeleted = new ArrayList<>();
        /** Ids des transactions creees par cet import : bouton « Annuler l'import » (voir ServiceSuppressionTransaction#undoCreation). */
        private final List<Long> importedIds = new ArrayList<>();
    }

    /** Ligne du fichier ignoree car correspondant a une operation supprimee auparavant (voir ImportResult#previouslyDeleted). */
    public record LigneDejaSupprimee(LocalDate date, BigDecimal amount, String payee) {}

    /**
     * Tiers d'une ligne importee : le tiers deja connu qui correspond au texte
     * brut, ou a defaut le texte lui-meme. Le tiers n'est cree (voir
     * payeeToSave) qu'une fois la ligne confirmee comme non doublon : le
     * creer avant la detection laissait un tiers orphelin a chaque ligne
     * ignoree, par exemple au reimport d'une transaction supprimee depuis
     * (dont le tiers avait ete nettoye avec elle).
     */
    private record TiersLigne(Tiers existing, String text) {
        /** Nom compare aux doublons : celui du tiers existant, ou celui que portera le tiers cree. */
        String canonicalName() {
            if (existing != null) return existing.getName();
            return text != null && !text.isBlank() ? MotifsTiers.suggestFromBankLabel(text).name() : text;
        }
    }

    /**
     * `knownPayees` est charge UNE FOIS par import (voir importCsv/importQif)
     * et reutilise pour toutes les lignes du fichier, plutot que de recharger
     * tous les tiers en base a chaque ligne (un import peut contenir des
     * centaines/milliers de lignes).
     */
    private TiersLigne matchPayee(String payeeText, BigDecimal amount, List<Tiers> knownPayees) {
        return new TiersLigne(payeeService.findExisting(payeeText, amount, knownPayees).orElse(null), payeeText);
    }

    /** Tiers a enregistrer sur la transaction : l'existant, sinon cree a partir du texte (null si texte vide). */
    private Tiers payeeToSave(TiersLigne payeeLine, List<Tiers> knownPayees) {
        if (payeeLine.existing() != null) return payeeLine.existing();
        String text = payeeLine.text();
        return text != null && !text.isBlank() ? payeeService.createFromBankLabel(text, knownPayees) : null;
    }

    /**
     * Categorie cherchee une seule fois par nom et par import : chaque ligne
     * coutait sinon jusqu'a deux requetes ("Parent:Enfant"), pour quelques
     * dizaines de categories differentes dans tout le fichier.
     */
    private Categorie resolveCategory(ImportContext ctx, Tiers payee, String categoryNameFromFile, BigDecimal amount) {
        if (categoryNameFromFile != null && !categoryNameFromFile.isBlank()) {
            String key = categoryNameFromFile.trim().toLowerCase(Locale.ROOT);
            Categorie known = ctx.categories().get(key);
            if (known != null) return known;
            Categorie category = categoryService.findOrCreateByName(categoryNameFromFile, amount);
            if (category != null) ctx.categories().put(key, category);
            return category;
        }
        return payee != null ? payee.getCategory() : null;
    }

    /**
     * Remplace un memo litteralement "(NULL)"/"NULL" ou vide par null, pour
     * eviter d'afficher cette valeur telle quelle dans l'interface.
     */
    private String sanitizeMemo(String memo) {
        // Memo d'un releve : tronque plutot que refuse (voir LongueurTexte#tronquer).
        return ServiceTransaction.sanitizeMemo(LongueurTexte.tronquer(memo));
    }

    /**
     * Detection de doublons d'un import, par COMPTAGE des occurrences de
     * chaque cle (date+montant+tiers canonique, insensible a la casse, voir
     * CleDoublon) : la n-ieme ligne du fichier portant une cle donnee est un
     * doublon seulement si cette cle existait deja au moins n fois sur le
     * compte avant l'import. Deux operations reellement identiques d'un meme
     * releve (ex : deux cafes au meme prix le meme jour) sont ainsi toutes
     * deux importees, alors qu'un simple test "cle deja vue" ecartait la
     * seconde comme doublon ; reimporter le meme releve n'importe toujours
     * rien, et un releve qui chevauche le precedent n'importe que les
     * occurrences manquantes.
     */
    static final class CompteurDoublons {
        /** Ce que represente une ligne du fichier par rapport a l'existant. */
        enum Occurrence { NOUVELLE, EXISTANTE, SUPPRIMEE }

        private final Map<CleDoublon, Integer> existing = new HashMap<>();
        private final Map<CleDoublon, Integer> deleted = new HashMap<>();
        private final Map<CleDoublon, Integer> seenInFile = new HashMap<>();
        private final Map<CleLigneImportee, Integer> importedLines = new HashMap<>();
        private final Map<CleLigneImportee, Integer> linesSeenInFile = new HashMap<>();

        void addExisting(CleDoublon key) {
            existing.merge(key, 1, Integer::sum);
        }

        void addDeleted(CleDoublon key) {
            deleted.merge(key, 1, Integer::sum);
        }

        /**
         * Enregistre une occurrence de la cle dans le fichier. Les premieres
         * occurrences correspondent aux transactions encore en base, les
         * suivantes aux transactions supprimees, les autres sont nouvelles.
         */
        Occurrence classify(CleDoublon key) {
            int occurrenceInFile = seenInFile.merge(key, 1, Integer::sum);
            int existingCount = existing.getOrDefault(key, 0);
            if (occurrenceInFile <= existingCount) return Occurrence.EXISTANTE;
            if (occurrenceInFile <= existingCount + deleted.getOrDefault(key, 0)) return Occurrence.SUPPRIMEE;
            return Occurrence.NOUVELLE;
        }

        void addImportedLine(CleLigneImportee key) {
            if (key != null) importedLines.merge(key, 1, Integer::sum);
        }

        /**
         * Vrai si cette occurrence de la ligne figure deja au registre des
         * lignes importees, avec le meme comptage que classify : deux
         * operations identiques d'un meme releve, dont une seule deja
         * importee, laissent passer la seconde.
         */
        boolean alreadyImported(CleLigneImportee key) {
            if (key == null) return false;
            int occurrenceInFile = linesSeenInFile.merge(key, 1, Integer::sum);
            return occurrenceInFile <= importedLines.getOrDefault(key, 0);
        }

        /** Occurrences deja vues dans le fichier, pour y revenir si un lot est annule. */
        record Instantane(Map<CleDoublon, Integer> seenInFile, Map<CleLigneImportee, Integer> linesSeenInFile) {}

        Instantane snapshot() {
            return new Instantane(new HashMap<>(seenInFile), new HashMap<>(linesSeenInFile));
        }

        void restore(Instantane snapshot) {
            seenInFile.clear();
            seenInFile.putAll(snapshot.seenInFile());
            linesSeenInFile.clear();
            linesSeenInFile.putAll(snapshot.linesSeenInFile());
        }
    }

    /**
     * Precharge, en une seule requete, les cles (date+montant+tiers) de
     * toutes les transactions deja enregistrees sur ce compte, ET des
     * transactions supprimees manuellement depuis (voir TransactionSupprimee /
     * ServiceSuppressionTransaction#delete) : un import peut contenir des centaines/
     * milliers de lignes, et interroger la base une fois par ligne pour
     * verifier les doublons rendait l'import lineairement plus lent avec la
     * taille du fichier ET de l'historique. La detection se fait ensuite en
     * memoire, en O(1) par ligne. Inclure les transactions supprimees evite
     * qu'une transaction volontairement effacee (ex : paire "depense
     * remboursee" nettoyee depuis l'onglet Doublons) ne revienne au prochain
     * import simplement parce qu'elle figure encore dans le releve source.
     * Charge aussi le registre des lignes deja importees (voir LigneImportee).
     */
    private CompteurDoublons loadDuplicateCounter(Long accountId, List<Tiers> knownPayees) {
        CompteurDoublons counter = new CompteurDoublons();
        for (Transaction t : transactionRepository.findByAccount_IdOrderByDateDescIdDesc(accountId)) {
            String payee = knownPayeeName(t.getBankLabel(), t.importKeyAmount(), t.getPayee() != null ? t.getPayee().getName() : null, knownPayees);
            counter.addExisting(CleDoublon.of(t.getDate(), t.importKeyAmount(), payee));
            if (exportDiffersFromImportKey(t)) {
                String exportedPayee = matchPayee(MappeurTransaction.payeeLabel(t), t.getAmount(), knownPayees).canonicalName();
                counter.addExisting(CleDoublon.of(t.getDate(), t.getAmount(), exportedPayee));
            }
        }
        for (TransactionSupprimee deleted : deletedTransactionRepository.findByAccountId(accountId)) {
            String payee = knownPayeeName(deleted.getBankLabel(), deleted.getAmount(), deleted.getPayeeName(), knownPayees);
            counter.addDeleted(CleDoublon.of(deleted.getDate(), deleted.getAmount(), payee));
        }
        for (LigneImportee line : importedLineRepository.findByAccountId(accountId)) {
            counter.addImportedLine(CleLigneImportee.of(line.getDate(), line.getAmount(), line.getLabel()));
        }
        return counter;
    }

    /**
     * Vrai si l'export CSV de t (voir csvExport.js cote frontend) ne porte pas
     * sa cle d'import : partie d'une division (montant du moment, pas celui du
     * releve), virement ou ajustement (libelle affiche, sans vrai tiers). Elle
     * est alors reconnue sous les deux formes : reimporter son propre export
     * recreait sinon ces lignes en double.
     */
    private static boolean exportDiffersFromImportKey(Transaction t) {
        return t.getSplitFromAmount() != null
                || (t.getPayee() == null && MappeurTransaction.payeeLabel(t) != null);
    }

    /**
     * Tiers a comparer pour une transaction deja connue. Importee (libelle
     * bancaire conserve) : le tiers que l'import reconnaitrait AUJOURD'HUI pour
     * ce libelle, exactement comme pour les lignes du fichier — la ligne reste
     * reconnue meme si l'utilisateur a change le tiers de la transaction
     * depuis (ex : « PAYPAL » reaffecte a « Vinted »), ce qui la faisait
     * reimporter. Saisie manuelle : son tiers actuel.
     */
    private String knownPayeeName(String bankLabel, BigDecimal amount, String currentPayeeName, List<Tiers> knownPayees) {
        if (bankLabel == null || bankLabel.isBlank()) return currentPayeeName;
        return matchPayee(bankLabel, amount, knownPayees).canonicalName();
    }

    /**
     * Regroupe l'etat partage entre toutes les lignes d'un meme import (voir
     * processCsvLine/finalizeQifRecord), pour eviter de le faire transiter en
     * une longue liste de parametres individuels.
     */
    private record ImportContext(Compte account, List<Tiers> knownPayees, CompteurDoublons duplicates,
                                 ImportResult result, Map<String, Categorie> categories) {
        ImportContext(Compte account, List<Tiers> knownPayees, CompteurDoublons duplicates, ImportResult result) {
            this(account, knownPayees, duplicates, result, new HashMap<>());
        }
    }

    /**
     * Etat de l'import avant un lot ou une ligne, pour l'y ramener si son
     * enregistrement est annule : resultat, tiers crees et occurrences deja
     * vues dans le fichier.
     */
    private record EtatImport(int imported, int duplicates, int skipped, int errors, int previouslyDeleted,
                              int importedIds, int knownPayees, CompteurDoublons.Instantane seenInFile) {
        static EtatImport de(ImportContext ctx) {
            ImportResult r = ctx.result();
            return new EtatImport(r.imported, r.duplicates, r.skipped, r.errors.size(), r.previouslyDeleted.size(),
                    r.importedIds.size(), ctx.knownPayees().size(), ctx.duplicates().snapshot());
        }

        void restaurer(ImportContext ctx) {
            ImportResult r = ctx.result();
            r.imported = imported;
            r.duplicates = duplicates;
            r.skipped = skipped;
            truncate(r.errors, errors);
            truncate(r.previouslyDeleted, previouslyDeleted);
            truncate(r.importedIds, importedIds);
            // Tiers et categories crees ont ete annules avec le rollback : les
            // garder en cache les ferait reutiliser par les lignes suivantes.
            truncate(ctx.knownPayees(), knownPayees);
            ctx.categories().clear();
            ctx.duplicates().restore(seenInFile);
        }

        private static void truncate(List<?> list, int size) {
            list.subList(size, list.size()).clear();
        }
    }

    /**
     * Traite les operations par lots, chacun dans une transaction : valider
     * chaque ligne imposait une ecriture disque par ligne, l'essentiel de la
     * duree d'un gros import sous PostgreSQL. Si un lot echoue, il est annule
     * puis rejoue ligne par ligne, chacune dans SA PROPRE transaction : la
     * ligne fautive est seule ignoree, et le tiers ou la categorie crees pour
     * elle sont annules avec elle au lieu de rester en base, orphelins.
     */
    private void processOperations(List<OperationLue> operations, ImportContext ctx) {
        for (int start = 0; start < operations.size(); start += IMPORT_BATCH_SIZE) {
            List<OperationLue> batch = operations.subList(start, Math.min(start + IMPORT_BATCH_SIZE, operations.size()));
            EtatImport before = EtatImport.de(ctx);
            try {
                transactionTemplate.executeWithoutResult(status -> batch.forEach(operation -> processOperation(operation, ctx)));
            } catch (RuntimeException e) {
                log.warn("Lot d'import annule, reprise ligne par ligne : {}", ValeursReleve.describeError(e));
                before.restaurer(ctx);
                releasePersistenceContext();
                batch.forEach(operation -> processAlone(operation, ctx));
            } finally {
                releasePersistenceContext();
            }
        }
    }

    /** Reprise d'une seule ligne apres l'echec de son lot : une erreur n'affecte qu'elle. */
    private void processAlone(OperationLue operation, ImportContext ctx) {
        EtatImport before = EtatImport.de(ctx);
        try {
            transactionTemplate.executeWithoutResult(status -> processOperation(operation, ctx));
        } catch (RuntimeException e) {
            before.restaurer(ctx);
            ctx.result().skipped++;
            ctx.result().errors.add(operation.origin() + " : " + ValeursReleve.describeError(e));
        } finally {
            releasePersistenceContext();
        }
    }

    /**
     * Ligne illisible comptee en erreur ; ligne deja importee (registre des
     * lignes importees) ou correspondant a une transaction en base ou
     * supprimee depuis : comptee comme doublon ; sinon transaction creee.
     * Toute ligne reconnue ou creee est ajoutee au registre : elle ne sera
     * plus jamais reimportee, quoi que devienne sa transaction.
     */
    private void processOperation(OperationLue operation, ImportContext ctx) {
        if (operation.error() != null) {
            ctx.result().skipped++;
            ctx.result().errors.add(operation.origin() + " : " + operation.error());
            return;
        }
        TiersLigne payeeLine = matchPayee(operation.payeeText(), operation.amount(), ctx.knownPayees());
        String canonicalPayee = payeeLine.canonicalName();
        // Les deux comptages avancent a chaque ligne, pour rester alignes sur les occurrences du fichier.
        CompteurDoublons.Occurrence occurrence = ctx.duplicates().classify(CleDoublon.of(operation.date(), operation.amount(), canonicalPayee));
        boolean alreadyImported = ctx.duplicates().alreadyImported(lineKey(operation));

        if (occurrence == CompteurDoublons.Occurrence.NOUVELLE && !alreadyImported) {
            Transaction created = saveOperation(ctx, operation, payeeLine);
            rememberImportedLine(ctx, operation, created.getId());
            return;
        }
        ctx.result().duplicates++;
        if (occurrence == CompteurDoublons.Occurrence.SUPPRIMEE) {
            ctx.result().previouslyDeleted.add(new LigneDejaSupprimee(operation.date(), operation.amount(), canonicalPayee));
        }
        // Reconnue par sa transaction mais absente du registre (import anterieur au registre) : enregistree desormais.
        if (!alreadyImported) rememberImportedLine(ctx, operation, null);
    }

    private static CleLigneImportee lineKey(OperationLue operation) {
        return CleLigneImportee.of(operation.date(), operation.amount(), LongueurTexte.libelleBancaire(operation.payeeText()));
    }

    private void rememberImportedLine(ImportContext ctx, OperationLue operation, Long transactionId) {
        String label = LongueurTexte.libelleBancaire(operation.payeeText());
        if (label == null) return;
        importedLineRepository.save(new LigneImportee(ctx.account().getId(), operation.date(), operation.amount(), label, transactionId));
    }

    private Transaction saveOperation(ImportContext ctx, OperationLue operation, TiersLigne payeeLine) {
        Tiers payee = payeeToSave(payeeLine, ctx.knownPayees());
        Transaction t = new Transaction();
        t.setAccount(ctx.account());
        t.setDate(operation.date());
        t.setAmount(operation.amount());
        t.setMemo(sanitizeMemo(operation.memo()));
        t.setPayee(payee);
        t.setCategory(resolveCategory(ctx, payee, operation.categoryName(), operation.amount()));
        t.setValidated(false); // toute transaction importee demarre "en attente"
        t.setBankLabel(LongueurTexte.libelleBancaire(payeeLine.text()));
        transactionRepository.save(t);
        ctx.result().imported++;
        if (t.getId() != null) ctx.result().importedIds.add(t.getId());
        return t;
    }

    /**
     * Vide le contexte de persistance apres chaque lot. Avec
     * spring.jpa.open-in-view, toute la requete HTTP partage un seul
     * EntityManager : sans cela, chaque transaction enregistree (et chaque
     * transaction chargee par loadDuplicateCounter) y restait, et chaque
     * requete suivante re-verifiait toutes ces entites avant de s'executer :
     * un cout quadratique sur un gros import. Le compte, les tiers et les
     * categories connus, detaches, restent utilisables : une nouvelle
     * transaction n'en reprend que l'identifiant (aucune cascade sur Transaction).
     * <p>
     * Jamais au sein d'une transaction englobante (ex : test d'integration
     * {@code @Transactional}) : chaque lot y rejoint cette transaction sans la
     * valider, et clear() effacerait des enregistrements pas encore ecrits.
     */
    private void releasePersistenceContext() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.clear();
        }
    }

    /**
     * Import d'un fichier CSV. Reconnait deux formats :
     * - le format HomeBank : date;paymode;info;payee;memo;amount;category;tags
     *   (colonnes paymode, info et tags ignorees) ;
     * - un export bancaire brut (ex : "Date;Libelle;Montant(EUROS)" ou
     *   "Date;Date de valeur;Debit;Credit;Libelle;Solde"), tel que telecharge
     *   directement depuis l'espace client d'une banque : peut etre precede de
     *   quelques lignes de metadonnees ("Numero Compte;...", "Solde;...") avant
     *   la vraie ligne d'en-tete, ignorees automatiquement. Ses colonnes sont
     *   reconnues par leur nom (voir layoutFromHeader). Le libelle complet
     *   (guillemets retires) sert de texte de reconnaissance du tiers,
     *   exactement comme le ferait un texte de payee brut a l'import.
     * Le separateur ';' est celui utilise par defaut par HomeBank comme par
     * la plupart des banques francaises.
     */
    public ImportResult importCsv(MultipartFile file, Long accountId) {
        Compte account = accountService.findById(accountId);
        ServiceCompte.ensureNotArchived(account);
        ImportResult result = new ImportResult();
        List<Tiers> knownPayees = payeeService.findAllForMatching();
        CompteurDoublons duplicates = loadDuplicateCounter(accountId, knownPayees);

        List<String> lines;
        try {
            lines = ValeursReleve.decodeImportedText(file.getBytes()).lines().toList();
        } catch (IOException e) {
            result.errors.add("Erreur de lecture du fichier : " + e.getMessage());
            return result;
        }

        processOperations(LecteurCsv.lire(lines), new ImportContext(account, knownPayees, duplicates, result));

        reportFileWithoutOperation(result);
        recordImportIfDataRecognized(result);
        logResult("CSV", accountId, result);
        return result;
    }

    private static void logResult(String format, Long accountId, ImportResult result) {
        log.info("Import {} sur le compte {} : {} transaction(s) importee(s), {} deja presente(s), {} ligne(s) en erreur",
                format, accountId, result.imported, result.duplicates, result.skipped);
    }

    static final String NO_OPERATION_FOUND = "Aucune opération trouvée dans ce fichier : vérifiez qu'il n'est pas vide"
            + " et que le format choisi (CSV ou QIF) correspond bien au fichier.";

    /**
     * Fichier lu sans aucune ligne d'operation, ni importee, ni deja presente,
     * ni en erreur : fichier vide, en-tete seule, ou mauvais format choisi
     * (un CSV importe comme QIF n'a aucune section "!Type:Bank"). Sans ce
     * message, l'ecran n'affichait que trois compteurs a zero, sans dire
     * pourquoi rien n'avait ete importe.
     */
    private static void reportFileWithoutOperation(ImportResult result) {
        if (result.imported + result.duplicates + result.skipped == 0) {
            result.errors.add(NO_OPERATION_FOUND);
        }
    }

    /**
     * Memorise la date du dernier import (voir ServiceDernierImport) seulement
     * si le fichier a reellement ete lu : au moins une operation reconnue,
     * nouvelle ou deja presente (un releve deja importe confirme que les
     * donnees sont a jour). Un fichier illisible, vide ou dont toutes les
     * lignes sont en erreur ne doit pas faire croire a un import recent.
     */
    private void recordImportIfDataRecognized(ImportResult result) {
        if (result.imported + result.duplicates > 0) {
            dernierImportService.enregistrerImport();
        }
    }

    /**
     * Import d'un fichier QIF (Quicken Interchange Format).
     * Champs geres : D (date), T (montant), P (payee), M (memo), L (categorie).
     */
    public ImportResult importQif(MultipartFile file, Long accountId) {
        Compte account = accountService.findById(accountId);
        ServiceCompte.ensureNotArchived(account);
        ImportResult result = new ImportResult();
        List<Tiers> knownPayees = payeeService.findAllForMatching();
        CompteurDoublons duplicates = loadDuplicateCounter(accountId, knownPayees);

        List<String> lines;
        try {
            lines = ValeursReleve.decodeImportedText(file.getBytes()).lines().toList();
        } catch (IOException e) {
            result.errors.add("Erreur de lecture du fichier : " + e.getMessage());
            return result;
        }

        processOperations(LecteurQif.lire(lines), new ImportContext(account, knownPayees, duplicates, result));

        reportFileWithoutOperation(result);
        recordImportIfDataRecognized(result);
        logResult("QIF", accountId, result);
        return result;
    }
}

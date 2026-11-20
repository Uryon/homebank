package com.homebank.service;

import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import com.homebank.utils.LongueurTexte;
import com.homebank.utils.Montant;
import com.homebank.utils.Periode;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
public class ServiceImport {

    private static final Logger log = LoggerFactory.getLogger(ServiceImport.class);

    private final ServiceCompte accountService;
    private final ServiceCategorie categoryService;
    private final DepotTransaction transactionRepository;
    private final DepotTransactionSupprimee deletedTransactionRepository;
    private final ServiceTiers payeeService;
    private final ServiceDernierImport dernierImportService;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    // "d"/"M" (et non "dd"/"MM") acceptent un ou deux chiffres : "5/1/2026"
    // (courant en QIF) comme "05/01/2026". Jour avant mois en priorite
    // (releves francais) ; le format US n'est tente qu'en dernier recours.
    // Lecture stricte : un 31/02 est refuse au lieu d'etre ramene au 28 ou 29.
    private static final List<DateTimeFormatter> DATE_FORMATS = Stream.of(
                    "d-M-uu", "d-M-uuuu", "d/M/uu", "d/M/uuuu", "d.M.uu", "d.M.uuuu",
                    "uuuu-M-d", "uuuu/M/d", "uuuu.M.d", "uuuuMMdd", "M/d/uuuu", "M/d/uu")
            .map(pattern -> DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT))
            .toList();

    /** Heure qui suit la date dans certains exports : "01/05/2026 14:32", "2026-05-01T14:32:00". */
    private static final Pattern TIME_SUFFIX = Pattern.compile("[T ]+\\d{1,2}:\\d{2}.*$");
    /** Espaces, apostrophe de milliers, symbole ou code monetaire autour d'un montant. */
    private static final Pattern AMOUNT_DECORATION =
            Pattern.compile("[\\s\\u00A0\\u2007\\u2009\\u202F'\\u2019€$£]|(?i:EUR|USD|GBP|CHF)");
    /** Nombre entier groupe par milliers, avec un seul type de separateur : "1,234", "1.234.567". */
    private static final Pattern THOUSANDS_ONLY = Pattern.compile("[+-]?[1-9]\\d{0,2}([.,])\\d{3}(?:\\1\\d{3})*");
    /** Colonne Debit/Credit sans montant, ecrite par certaines banques avec un simple tiret. */
    private static final Pattern NO_AMOUNT_PLACEHOLDER = Pattern.compile("[-\\u2013\\u2014]?");

    /** Lignes enregistrees par transaction lors d'un import (voir processOperations). */
    private static final int IMPORT_BATCH_SIZE = 200;

    // Mots recherches dans les en-tetes CSV normalises, par ordre de priorite (voir layoutFromHeader).
    private static final List<String> PAYEE_HEADERS =
            List.of("payee", "tiers", "beneficiaire", "libelle", "label", "intitule", "description");
    private static final List<String> MEMO_HEADERS = List.of("memo", "comment", "note", "detail");
    private static final List<String> CATEGORY_HEADERS = List.of("categorie", "category");
    private static final List<String> AMOUNT_HEADERS = List.of("montant", "amount");

    public ServiceImport(ServiceCompte accountService, ServiceCategorie categoryService,
                          DepotTransaction transactionRepository,
                          DepotTransactionSupprimee deletedTransactionRepository,
                          ServiceTiers payeeService,
                          ServiceDernierImport dernierImportService,
                          PlatformTransactionManager transactionManager,
                          EntityManager entityManager) {
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionRepository = transactionRepository;
        this.deletedTransactionRepository = deletedTransactionRepository;
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
        /** Ids des transactions creees par cet import : bouton « Annuler l'import » (voir ServiceTransaction#undoCreation). */
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
            return text != null && !text.isBlank() ? ServiceTiers.suggestFromBankLabel(text).name() : text;
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

        Map<CleDoublon, Integer> seenInFileSnapshot() {
            return new HashMap<>(seenInFile);
        }

        void restoreSeenInFile(Map<CleDoublon, Integer> snapshot) {
            seenInFile.clear();
            seenInFile.putAll(snapshot);
        }
    }

    /**
     * Precharge, en une seule requete, les cles (date+montant+tiers) de
     * toutes les transactions deja enregistrees sur ce compte, ET des
     * transactions supprimees manuellement depuis (voir TransactionSupprimee /
     * ServiceTransaction#delete) : un import peut contenir des centaines/
     * milliers de lignes, et interroger la base une fois par ligne pour
     * verifier les doublons rendait l'import lineairement plus lent avec la
     * taille du fichier ET de l'historique. La detection se fait ensuite en
     * memoire, en O(1) par ligne. Inclure les transactions supprimees evite
     * qu'une transaction volontairement effacee (ex : paire "depense
     * remboursee" nettoyee depuis l'onglet Doublons) ne revienne au prochain
     * import simplement parce qu'elle figure encore dans le releve source.
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
     * Vrai (et compte comme doublon) si la ligne correspond a une transaction
     * deja en base ou supprimee depuis ; une ligne supprimee est aussi listee
     * dans previouslyDeleted.
     */
    private static boolean isAlreadyKnown(ImportContext ctx, LocalDate date, BigDecimal amount, String canonicalPayee) {
        CompteurDoublons.Occurrence occurrence = ctx.duplicates().classify(CleDoublon.of(date, amount, canonicalPayee));
        if (occurrence == CompteurDoublons.Occurrence.NOUVELLE) return false;
        ctx.result().duplicates++;
        if (occurrence == CompteurDoublons.Occurrence.SUPPRIMEE) {
            ctx.result().previouslyDeleted.add(new LigneDejaSupprimee(date, amount, canonicalPayee));
        }
        return true;
    }

    /**
     * Operation lue dans le fichier, pas encore comparee a l'existant.
     * `origin` situe la ligne dans les messages ; `error` est renseigne si
     * elle est illisible.
     */
    private record OperationLue(String origin, LocalDate date, BigDecimal amount, String payeeText,
                                String memo, String categoryName, String error) {
        static OperationLue enErreur(String origin, String error) {
            return new OperationLue(origin, null, null, null, null, null, error);
        }
    }

    /**
     * Etat de l'import avant un lot ou une ligne, pour l'y ramener si son
     * enregistrement est annule : resultat, tiers crees et occurrences deja
     * vues dans le fichier.
     */
    private record EtatImport(int imported, int duplicates, int skipped, int errors, int previouslyDeleted,
                              int importedIds, int knownPayees, Map<CleDoublon, Integer> seenInFile) {
        static EtatImport de(ImportContext ctx) {
            ImportResult r = ctx.result();
            return new EtatImport(r.imported, r.duplicates, r.skipped, r.errors.size(), r.previouslyDeleted.size(),
                    r.importedIds.size(), ctx.knownPayees().size(), ctx.duplicates().seenInFileSnapshot());
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
            ctx.duplicates().restoreSeenInFile(seenInFile);
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
                log.warn("Lot d'import annule, reprise ligne par ligne : {}", describeError(e));
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
            ctx.result().errors.add(operation.origin() + " : " + describeError(e));
        } finally {
            releasePersistenceContext();
        }
    }

    /** Ligne illisible comptee en erreur, doublon compte a part, sinon transaction creee. */
    private void processOperation(OperationLue operation, ImportContext ctx) {
        if (operation.error() != null) {
            ctx.result().skipped++;
            ctx.result().errors.add(operation.origin() + " : " + operation.error());
            return;
        }
        TiersLigne payeeLine = matchPayee(operation.payeeText(), operation.amount(), ctx.knownPayees());
        if (!isAlreadyKnown(ctx, operation.date(), operation.amount(), payeeLine.canonicalName())) {
            saveOperation(ctx, operation, payeeLine);
        }
    }

    private void saveOperation(ImportContext ctx, OperationLue operation, TiersLigne payeeLine) {
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
     *
     * Jamais au sein d'une transaction englobante (ex : test d'integration
     * @Transactional) : chaque lot y rejoint cette transaction sans la
     * valider, et clear() effacerait des enregistrements pas encore ecrits.
     */
    private void releasePersistenceContext() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.clear();
        }
    }

    /**
     * Positions des colonnes utiles dans un CSV, deduites de sa ligne
     * d'en-tete (voir detectLayout ci-dessous). -1 pour une colonne absente
     * de ce format (ex : pas de memo/categorie separes dans un export
     * bancaire brut). Le montant vient soit d'une colonne unique (amountIdx),
     * soit de deux colonnes Debit/Credit (debitIdx, creditIdx).
     */
    private record CsvLayout(int dateIdx, int payeeIdx, int memoIdx, int amountIdx, int categoryIdx,
                             int debitIdx, int creditIdx) {

        /** Repli si aucune ligne d'en-tete n'est reconnue : format HomeBank classique, positions fixes. */
        static final CsvLayout HOMEBANK_DEFAULT = new CsvLayout(0, 3, 4, 5, 6, -1, -1);

        /** Export bancaire brut (une seule colonne "Libelle" texte + un montant) : pas de memo/categorie separes. */
        static final CsvLayout BANK_STATEMENT = new CsvLayout(0, 1, -1, 2, -1, -1, -1);

        boolean hasDebitCreditColumns() {
            return debitIdx >= 0;
        }

        /** Une colonne finale vide peut manquer : seule la premiere colonne de montant est exigee. */
        int minRequiredFields() {
            int firstAmountIdx = hasDebitCreditColumns() ? Math.min(debitIdx, creditIdx) : amountIdx;
            return Math.max(dateIdx, firstAmountIdx) + 1;
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
            lines = decodeImportedText(file.getBytes()).lines().toList();
        } catch (IOException e) {
            result.errors.add("Erreur de lecture du fichier : " + e.getMessage());
            return result;
        }

        char separator = detectSeparator(lines);
        int headerLineIndex = findHeaderLineIndex(lines, separator);
        CsvLayout layout = headerLineIndex >= 0
                ? layoutFromHeader(splitCsvLine(lines.get(headerLineIndex), separator))
                : CsvLayout.HOMEBANK_DEFAULT;
        int firstDataLine = headerLineIndex >= 0 ? headerLineIndex + 1 : 0;
        CsvFormat format = new CsvFormat(layout, separator, layout.minRequiredFields());

        List<OperationLue> operations = new ArrayList<>();
        for (int i = firstDataLine; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            int lastLine = endOfCsvRecord(lines, i, separator);
            String record = String.join("\n", lines.subList(i, lastLine + 1));
            operations.add(readCsvLine(record, i + 1, format));
            i = lastLine;
        }
        processOperations(operations, new ImportContext(account, knownPayees, duplicates, result));

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

    /** Lignes suivantes examinees pour fermer un champ entre guillemets : un memo reste court. */
    private static final int MAX_CSV_CONTINUATION_LINES = 10;

    /**
     * Derniere ligne de l'enregistrement commence a `start` : un champ entre
     * guillemets peut contenir un retour a la ligne (RFC 4180, memo exporte).
     * Un guillemet isole dans un libelle ne doit pas avaler les operations
     * suivantes : si une ligne commencant par une date arrive avant le
     * guillemet fermant, ou s'il n'y en a pas, la ligne reste seule.
     */
    static int endOfCsvRecord(List<String> lines, int start, char separator) {
        if (!hasOddQuoteCount(lines.get(start))) return start;
        int lastCandidate = Math.min(start + MAX_CSV_CONTINUATION_LINES, lines.size() - 1);
        for (int i = start + 1; i <= lastCandidate; i++) {
            String line = lines.get(i);
            if (isDate(splitCsvLine(line, separator)[0])) return start;
            if (hasOddQuoteCount(line)) return i;
        }
        return start;
    }

    private static boolean hasOddQuoteCount(String line) {
        return line.chars().filter(c -> c == '"').count() % 2 == 1;
    }

    /** Lit une ligne de donnees du CSV (voir importCsv), sans rien enregistrer. */
    private static OperationLue readCsvLine(String line, int lineNumber, CsvFormat format) {
        String origin = "Ligne " + lineNumber;
        CsvLayout layout = format.layout();

        String[] fields = splitCsvLine(line, format.separator());
        if (fields.length < format.minRequiredFields()) {
            return OperationLue.enErreur(origin, "nombre de colonnes insuffisant");
        }
        try {
            return new OperationLue(origin, parseDate(csvField(fields, layout.dateIdx())), csvAmount(fields, layout),
                    csvText(fields, layout.payeeIdx()), csvText(fields, layout.memoIdx()),
                    csvText(fields, layout.categoryIdx()), null);
        } catch (RuntimeException e) {
            return OperationLue.enErreur(origin, describeError(e));
        }
    }

    private static String csvText(String[] fields, int idx) {
        return withoutSpreadsheetGuard(csvField(fields, idx));
    }

    /**
     * Retire l'apostrophe ajoutee par l'export (voir csvExport.js cote
     * frontend) devant un texte qu'un tableur prendrait pour une formule :
     * reimporter son propre export doit retrouver le texte d'origine.
     */
    static String withoutSpreadsheetGuard(String text) {
        if (text.length() < 2 || text.charAt(0) != '\'') return text;
        String guarded = text.substring(1);
        String visible = guarded.stripLeading();
        boolean looksLikeFormula = !visible.isEmpty() && "=+-@".indexOf(visible.charAt(0)) >= 0;
        return looksLikeFormula ? guarded : text;
    }

    /**
     * Texte d'erreur affiche a l'utilisateur pour une ligne ignoree : le
     * message de l'exception, ou a defaut son type (une NullPointerException
     * sans message s'affichait sinon "Ligne 2 : null").
     */
    private static String describeError(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    /**
     * Cherche la ligne d'en-tete (au moins 3 colonnes, la premiere commencant
     * par "date" : "Date", "Date de l'operation", "dateOp"...), en ignorant
     * les lignes de metadonnees d'un export bancaire qui la precedent
     * ("Numero Compte;...", "Compte;FR76...;EUR"). La recherche s'arrete a la
     * premiere ligne qui commence par une date valide : le fichier est alors
     * sans en-tete (repli sur CsvLayout.HOMEBANK_DEFAULT).
     */
    private int findHeaderLineIndex(List<String> lines, char separator) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;

            String[] fields = splitCsvLine(line, separator);
            if (fields.length < 3) continue;
            if (normalizeHeader(fields[0]).startsWith("date")) return i;
            if (isDate(fields[0])) return -1;
        }
        return -1;
    }

    private static boolean isDate(String raw) {
        try {
            parseDate(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Deduit les colonnes utiles de leur NOM : leur seul nombre ne suffit pas,
     * un export "Date;Date de valeur;Debit;Credit;Libelle;Solde" etait pris
     * pour le format HomeBank et son solde importe comme montant. Des colonnes
     * Debit et Credit separees priment sur un "Montant" (ex : "Montant debit",
     * "Montant credit"). Sans colonne de montant reconnue, repli sur les
     * positions connues selon le nombre de colonnes.
     */
    private static CsvLayout layoutFromHeader(String[] header) {
        List<String> columns = Arrays.stream(header).map(ServiceImport::normalizeHeader).toList();
        int debitIdx = amountColumn(columns, List.of("debit"));
        int creditIdx = amountColumn(columns, List.of("credit"));
        boolean separateColumns = debitIdx >= 0 && creditIdx >= 0 && debitIdx != creditIdx;
        // Colonne unique "Debit/Credit" : un montant signe comme un autre.
        int singleColumnIdx = debitIdx >= 0 && debitIdx == creditIdx ? debitIdx : -1;
        int namedAmountIdx = amountColumn(columns, AMOUNT_HEADERS);
        int amountIdx = namedAmountIdx >= 0 ? namedAmountIdx : singleColumnIdx;
        if (!separateColumns && amountIdx < 0) {
            return header.length >= 6 ? CsvLayout.HOMEBANK_DEFAULT : CsvLayout.BANK_STATEMENT;
        }

        List<Integer> amountColumns = separateColumns ? List.of(debitIdx, creditIdx) : List.of(amountIdx);
        int payeeIdx = column(columns, PAYEE_HEADERS);
        if (payeeIdx < 0) payeeIdx = firstTextColumn(columns, amountColumns);
        int memoIdx = column(columns, MEMO_HEADERS);
        return new CsvLayout(0, payeeIdx, memoIdx == payeeIdx ? -1 : memoIdx,
                separateColumns ? -1 : amountIdx, column(columns, CATEGORY_HEADERS),
                separateColumns ? debitIdx : -1, separateColumns ? creditIdx : -1);
    }

    /** En-tete sans guillemets, accents ni majuscules : "Libellé" et "LIBELLE" se comparent a l'identique. */
    private static String normalizeHeader(String raw) {
        return Normalizer.normalize(stripQuotes(raw), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Premiere colonne contenant l'un des mots, le premier mot de la liste etant prioritaire ; -1 sinon. */
    private static int column(List<String> columns, List<String> keywords) {
        for (String keyword : keywords) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).contains(keyword)) return i;
            }
        }
        return -1;
    }

    /** Comme column, sans les dates ("Date de debit") ni les soldes ("Solde credit"), qui ne sont pas des montants d'operation. */
    private static int amountColumn(List<String> columns, List<String> keywords) {
        List<String> candidates = columns.stream()
                .map(name -> isDateOrBalanceColumn(name) ? "" : name)
                .toList();
        return column(candidates, keywords);
    }

    private static boolean isDateOrBalanceColumn(String name) {
        return name.startsWith("date") || name.contains("solde") || name.contains("balance");
    }

    /** Colonne de libelle d'un en-tete au nom inconnu ("Date;Operation;Montant") : la premiere qui n'est ni date, ni solde, ni montant. */
    private static int firstTextColumn(List<String> columns, List<Integer> amountColumns) {
        return IntStream.range(0, columns.size())
                .filter(i -> !amountColumns.contains(i) && !isDateOrBalanceColumn(columns.get(i)))
                .findFirst()
                .orElse(-1);
    }

    /**
     * Montant d'une ligne CSV. Avec des colonnes Debit/Credit, le debit est
     * toujours une depense, qu'il soit ecrit "45,50" ou "-45,50" selon la banque.
     */
    private static BigDecimal csvAmount(String[] fields, CsvLayout layout) {
        if (!layout.hasDebitCreditColumns()) {
            return parseAmount(csvField(fields, layout.amountIdx()));
        }
        String debit = csvField(fields, layout.debitIdx());
        String credit = csvField(fields, layout.creditIdx());
        if (hasNoAmount(debit) && hasNoAmount(credit)) {
            throw new IllegalArgumentException("montant absent (colonnes Débit et Crédit vides)");
        }
        return absoluteAmount(credit).subtract(absoluteAmount(debit));
    }

    private static boolean hasNoAmount(String raw) {
        return NO_AMOUNT_PLACEHOLDER.matcher(raw).matches();
    }

    private static BigDecimal absoluteAmount(String raw) {
        return hasNoAmount(raw) ? BigDecimal.ZERO : parseAmount(raw).abs();
    }

    /**
     * Decoupe une ligne CSV sur ';' en respectant les guillemets : un libelle
     * bancaire entre guillemets peut contenir lui-meme un ';' ("VIR SEPA ACME;
     * REF 2024-03"), qu'un simple split decoupait en deux, decalant toutes les
     * colonnes suivantes (montant lu dans la mauvaise colonne). Les guillemets
     * entourant un champ sont retires, et "" a l'interieur d'un champ entre
     * guillemets vaut un guillemet (RFC 4180). Le contenu n'est pas rogne :
     * voir csvField/stripQuotes.
     */
    static String[] splitCsvLine(String line) {
        return splitCsvLine(line, ';');
    }

    /** Format d'un CSV en cours d'import : colonnes, separateur et nombre minimal de colonnes d'une ligne de donnees. */
    private record CsvFormat(CsvLayout layout, char separator, int minRequiredFields) {}

    /**
     * Separateur du fichier, deduit de sa premiere ligne non vide (hors
     * texte entre guillemets) : ';' (HomeBank et la plupart des banques
     * francaises) s'il y en a un, sinon tabulation, sinon ',' (export de
     * certaines banques en ligne ou d'un tableur anglophone). Sans cette
     * detection, un CSV a virgules etait lu comme une seule colonne et
     * toutes ses lignes partaient en erreur « nombre de colonnes insuffisant ».
     */
    static char detectSeparator(List<String> lines) {
        String first = lines.stream().filter(l -> !l.isBlank()).findFirst().orElse("");
        boolean inQuotes = false;
        boolean hasTab = false;
        boolean hasComma = false;
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                if (c == ';') return ';';
                hasTab |= c == '\t';
                hasComma |= c == ',';
            }
        }
        if (hasTab) return '\t';
        return hasComma ? ',' : ';';
    }

    static String[] splitCsvLine(String line, char separator) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    current.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == separator) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(String[]::new);
    }

    /** Valeur d'un champ CSV a une position donnee, guillemets et espaces retires ; "" si la colonne n'existe pas pour ce format. */
    private static String csvField(String[] fields, int idx) {
        return (idx >= 0 && idx < fields.length) ? stripQuotes(fields[idx]) : "";
    }

    /** Retire un encadrement par des guillemets doubles (export bancaire), sinon se contente de trim(). */
    private static String stripQuotes(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
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
            lines = decodeImportedText(file.getBytes()).lines().toList();
        } catch (IOException e) {
            result.errors.add("Erreur de lecture du fichier : " + e.getMessage());
            return result;
        }

        QifRecordState state = new QifRecordState();
        List<OperationLue> operations = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            readQifLine(lines.get(i), i + 1, operations, state);
        }
        finalizeUnterminatedQifRecord(lines.size(), operations, state);
        processOperations(operations, new ImportContext(account, knownPayees, duplicates, result));

        reportFileWithoutOperation(result);
        recordImportIfDataRecognized(result);
        logResult("QIF", accountId, result);
        return result;
    }

    /**
     * Champs d'un enregistrement QIF en cours d'accumulation (entre deux "^"),
     * et section courante ("!Type:Bank" ou non) : etat mutable qui persiste
     * d'une ligne a l'autre du fichier tant que l'enregistrement n'est pas
     * termine (voir readQifLine/finalizeQifRecord).
     */
    private static class QifRecordState {
        LocalDate date;
        BigDecimal amount;
        String payeeText;
        String memo;
        String categoryName;
        // Premiere erreur de lecture d'un champ (date/montant illisible) : l'enregistrement
        // entier sera ignore a sa fin ("^"), sans interrompre l'import des suivants.
        String invalidReason;
        // On ne traite les transactions que dans une section d'operations (voir isQifTransactionSection).
        boolean inBankSection;

        void markInvalid(String reason) {
            if (invalidReason == null) invalidReason = reason;
        }

        boolean hasContent() {
            return date != null || amount != null || payeeText != null || memo != null
                    || categoryName != null || invalidReason != null;
        }

        void reset() {
            date = null;
            amount = null;
            payeeText = null;
            memo = null;
            categoryName = null;
            invalidReason = null;
        }
    }

    /**
     * Sections QIF qui contiennent des operations au format D/T/P/M/L : compte
     * bancaire, especes, carte de credit, autre actif ou passif. Une carte
     * bancaire est exportee en "!Type:CCard" par la plupart des banques :
     * seul "!Type:Bank" etait reconnu, et l'import de ce fichier ne trouvait
     * aucune operation. Les sections de placements (!Type:Invst, champs
     * differents), de categories ou d'operations memorisees restent ignorees.
     */
    static boolean isQifTransactionSection(String header) {
        String type = header.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return type.equals("!type:bank") || type.equals("!type:cash") || type.equals("!type:ccard")
                || type.equals("!type:oth a") || type.equals("!type:oth l");
    }

    /** Lit une ligne du fichier QIF (voir importQif) : accumule un champ de l'enregistrement en cours, ou le termine sur "^". */
    private static void readQifLine(String rawLine, int lineNumber, List<OperationLue> operations, QifRecordState state) {
        if (rawLine.isBlank()) return;
        // Certains logiciels indentent les champs : le code est le premier caractere utile.
        String line = rawLine.stripLeading();

        // Gestion des sections QIF.
        if (line.startsWith("!")) {
            finalizeUnterminatedQifRecord(lineNumber, operations, state);
            state.inBankSection = isQifTransactionSection(line);
            return;
        }

        // On ignore tout ce qui precede une section d'operations (et les
        // sections de categories, classes, operations memorisees...).
        if (!state.inBankSection) return;

        char code = line.charAt(0);
        String value = line.length() > 1 ? line.substring(1).trim() : "";

        try {
            switch (code) {
                case 'D' -> state.date = parseDate(value);
                case 'T', 'U' -> state.amount = parseAmount(value);
                case 'P' -> state.payeeText = value;
                case 'M' -> state.memo = value;
                case 'L' -> state.categoryName = qifCategory(value);
                case '^' -> finalizeQifRecord(lineNumber, operations, state);
                default -> {
                    // Champs QIF non geres : N, C, A, etc.
                }
            }
        } catch (IllegalArgumentException e) {
            // Date/montant illisible : seul cet enregistrement sera ignore (voir finalizeQifRecord).
            state.markInvalid(e.getMessage());
        }
    }

    /** "[Livret A]" designe en QIF le compte d'un virement, pas une categorie : il en aurait cree une a ce nom. */
    private static String qifCategory(String value) {
        return value.startsWith("[") ? null : value;
    }

    /**
     * Enregistrement sans "^" final, en fin de fichier ou avant une nouvelle
     * section : certains logiciels omettent le dernier, et l'operation etait
     * alors perdue sans aucun message.
     */
    private static void finalizeUnterminatedQifRecord(int lineNumber, List<OperationLue> operations, QifRecordState state) {
        if (state.inBankSection && state.hasContent()) {
            finalizeQifRecord(lineNumber, operations, state);
        }
    }

    /** Fin d'un enregistrement QIF ("^") : ajoute l'operation lue (ou son erreur), puis reinitialise l'etat pour le suivant. */
    private static void finalizeQifRecord(int lineNumber, List<OperationLue> operations, QifRecordState state) {
        String origin = "Enregistrement termine ligne " + lineNumber;
        if (state.invalidReason != null) {
            operations.add(OperationLue.enErreur(origin, state.invalidReason));
        } else if (state.date == null || state.amount == null) {
            operations.add(OperationLue.enErreur(origin, "date ou montant manquant"));
        } else {
            operations.add(new OperationLue(origin, state.date, state.amount, state.payeeText,
                    state.memo, state.categoryName, null));
        }
        state.reset();
    }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Texte d'un fichier importe. UTF-8 si le contenu est de l'UTF-8 valide
     * (marque BOM d'Excel "CSV UTF-8" retiree : sinon le premier en-tete
     * commencait par U+FEFF ("Date" non reconnu) et toutes les lignes d'un export
     * bancaire etaient rejetees), sinon Windows-1252 : encodage de beaucoup
     * d'exports de banques francaises et d'Excel "CSV (separateur : point-virgule)",
     * dont les accents devenaient sinon des caracteres de remplacement
     * U+FFFD (tiers "Pr?l?vement" non reconnus, crees en double). UTF-16 quand
     * sa marque BOM l'annonce ("Texte Unicode" d'Excel) : lu autrement, chaque
     * caractere etait separe par un octet nul et aucune ligne n'etait reconnue.
     */
    static String decodeImportedText(byte[] bytes) {
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            return new String(bytes, WINDOWS_1252);
        }
    }

    private static boolean startsWith(byte[] bytes, int first, int second) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == first && (bytes[1] & 0xFF) == second;
    }

    /**
     * Date d'une ligne importee. L'apostrophe que Quicken place avant l'annee
     * dans ses QIF ("15/01'2024", "1/15'24") est lue comme un "/" : sans ca,
     * tout l'enregistrement etait rejete en "Format de date non reconnu".
     */
    static LocalDate parseDate(String raw) {
        String withoutTime = raw == null ? "" : TIME_SUFFIX.matcher(raw.trim()).replaceFirst("");
        String trimmed = withoutTime.replace('\'', '/');
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            LocalDate date;
            try {
                date = LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
                // essai du format suivant
                continue;
            }
            Periode.verifierAnnee(date, "La date « " + raw.trim() + " »");
            return date;
        }
        throw new IllegalArgumentException("Format de date non reconnu : " + raw);
    }

    /**
     * Montant tel qu'ecrit dans un releve : separateurs de milliers (espace,
     * espace insecable U+00A0/U+202F des exports Excel francais, apostrophe
     * suisse), symbole/code monetaire et signe moins typographique (U+2212)
     * sont toleres. Message explicite si le montant reste illisible, plutot
     * que celui de NumberFormatException.
     */
    static BigDecimal parseAmount(String raw) {
        String cleaned = AMOUNT_DECORATION.matcher(raw == null ? "" : raw).replaceAll("")
                .replace('−', '-')
                .replace('–', '-');
        // Negatif comptable : "(12,50)" ou "12,50-".
        boolean negated = false;
        if (cleaned.length() > 2 && cleaned.startsWith("(") && cleaned.endsWith(")")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
            negated = true;
        } else if (cleaned.length() > 1 && cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
            negated = true;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(withDecimalPoint(cleaned));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Montant non reconnu : " + raw);
        }
        return Montant.auCentime(negated ? amount.negate() : amount, "Le montant « " + raw.trim() + " »");
    }

    /**
     * Separateurs ramenes a un point decimal : "1.234,56" (europeen),
     * "1,234.56" (US). "1,234" ou "1.234" sont des milliers : un releve porte
     * deux decimales, et les lire comme 1,23 changeait le montant sans erreur.
     */
    private static String withDecimalPoint(String amount) {
        boolean hasComma = amount.contains(",");
        boolean hasPoint = amount.contains(".");
        if (hasComma && hasPoint) {
            return amount.indexOf(',') > amount.indexOf('.')
                    ? amount.replace(".", "").replace(",", ".")
                    : amount.replace(",", "");
        }
        if (THOUSANDS_ONLY.matcher(amount).matches()) {
            return amount.replace(",", "").replace(".", "");
        }
        return amount.replace(",", ".");
    }
}

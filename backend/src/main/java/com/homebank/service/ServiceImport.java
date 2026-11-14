package com.homebank.service;

import com.homebank.model.Compte;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import lombok.Getter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ServiceImport {

    private final ServiceCompte accountService;
    private final ServiceCategorie categoryService;
    private final DepotTransaction transactionRepository;
    private final DepotTransactionSupprimee deletedTransactionRepository;
    private final ServiceTiers payeeService;
    private final ServiceDernierImport dernierImportService;
    private final TransactionTemplate transactionTemplate;

    // "d"/"M" (et non "dd"/"MM") acceptent un ou deux chiffres : "5/1/2026"
    // (courant en QIF) comme "05/01/2026". Jour avant mois en priorite
    // (releves francais) ; le format US n'est tente qu'en dernier recours.
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d-M-yy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yy")
    );

    public ServiceImport(ServiceCompte accountService, ServiceCategorie categoryService,
                          DepotTransaction transactionRepository,
                          DepotTransactionSupprimee deletedTransactionRepository,
                          ServiceTiers payeeService,
                          ServiceDernierImport dernierImportService,
                          PlatformTransactionManager transactionManager) {
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionRepository = transactionRepository;
        this.deletedTransactionRepository = deletedTransactionRepository;
        this.payeeService = payeeService;
        this.dernierImportService = dernierImportService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

    /** Libelle bancaire conserve sur la transaction (voir Transaction#bankLabel), tronque a la taille de la colonne. */
    private static String bankLabelOf(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    /** Tiers a enregistrer sur la transaction : l'existant, sinon cree a partir du texte (null si texte vide). */
    private Tiers payeeToSave(TiersLigne payeeLine, List<Tiers> knownPayees) {
        if (payeeLine.existing() != null) return payeeLine.existing();
        String text = payeeLine.text();
        return text != null && !text.isBlank() ? payeeService.createFromBankLabel(text, knownPayees) : null;
    }

    private Categorie resolveCategory(Tiers payee, String categoryNameFromFile, BigDecimal amount) {
        if (categoryNameFromFile != null && !categoryNameFromFile.isBlank()) {
            return categoryService.findOrCreateByName(categoryNameFromFile, amount);
        }
        return payee != null ? payee.getCategory() : null;
    }

    /**
     * Remplace un memo litteralement "(NULL)"/"NULL" ou vide par null, pour
     * eviter d'afficher cette valeur telle quelle dans l'interface.
     */
    private String sanitizeMemo(String memo) {
        return ServiceTransaction.sanitizeMemo(memo);
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
        }
        for (TransactionSupprimee deleted : deletedTransactionRepository.findByAccountId(accountId)) {
            String payee = knownPayeeName(deleted.getBankLabel(), deleted.getAmount(), deleted.getPayeeName(), knownPayees);
            counter.addDeleted(CleDoublon.of(deleted.getDate(), deleted.getAmount(), payee));
        }
        return counter;
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
    private record ImportContext(Compte account, List<Tiers> knownPayees, CompteurDoublons duplicates, ImportResult result) {}

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

    /** Donnees d'une ligne CSV / d'un enregistrement QIF, deja lues et reconnues comme non doublon. */
    private record LigneImportee(LocalDate date, BigDecimal amount, String memo,
                                 String categoryName, TiersLigne payeeLine) {}

    /**
     * Enregistre une ligne importee dans SA PROPRE transaction : le tiers et
     * la categorie eventuellement crees pour elle sont annules avec elle si
     * son enregistrement echoue, au lieu de rester en base, orphelins. Les
     * autres lignes du fichier ne sont pas affectees (l'import reste "ligne
     * par ligne", une ligne en erreur est seulement comptee comme ignoree).
     */
    private void saveLine(ImportContext ctx, LigneImportee line) {
        List<Tiers> knownPayees = ctx.knownPayees();
        int knownPayeesBefore = knownPayees.size();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Tiers payee = payeeToSave(line.payeeLine(), knownPayees);
                Transaction t = new Transaction();
                t.setAccount(ctx.account());
                t.setDate(line.date());
                t.setAmount(line.amount());
                t.setMemo(sanitizeMemo(line.memo()));
                t.setPayee(payee);
                t.setCategory(resolveCategory(payee, line.categoryName(), line.amount()));
                t.setValidated(false); // toute transaction importee demarre "en attente"
                t.setBankLabel(bankLabelOf(line.payeeLine().text()));
                transactionRepository.save(t);
            });
        } catch (RuntimeException e) {
            // Tiers crees pour cette ligne annules avec elle (rollback) : les
            // retirer aussi du cache, sinon une ligne suivante les reutiliserait.
            knownPayees.subList(knownPayeesBefore, knownPayees.size()).clear();
            throw e;
        }
        ctx.result().imported++;
    }

    /**
     * Positions des colonnes utiles dans un CSV, deduites de sa ligne
     * d'en-tete (voir detectLayout ci-dessous). -1 pour une colonne absente
     * de ce format (ex : pas de memo/categorie separes dans un export
     * bancaire brut).
     */
    private record CsvLayout(int dateIdx, int payeeIdx, int memoIdx, int amountIdx, int categoryIdx) {

        /** Repli si aucune ligne d'en-tete n'est reconnue : format HomeBank classique, positions fixes. */
        static final CsvLayout HOMEBANK_DEFAULT = new CsvLayout(0, 3, 4, 5, 6);

        /** Export bancaire brut (une seule colonne "Libelle" texte + un montant) : pas de memo/categorie separes. */
        static final CsvLayout BANK_STATEMENT = new CsvLayout(0, 1, -1, 2, -1);
    }

    /**
     * Import d'un fichier CSV. Reconnait deux formats :
     * - le format HomeBank : date;paymode;info;payee;memo;amount;category;tags
     *   (colonnes paymode, info et tags ignorees) ;
     * - un export bancaire brut (ex : "Date;Libelle;Montant(EUROS)"), tel que
     *   telecharge directement depuis l'espace client d'une banque : peut etre
     *   precede de quelques lignes de metadonnees ("Numero Compte;...", "Solde;...")
     *   avant la vraie ligne d'en-tete, ignorees automatiquement. Le libelle
     *   complet (guillemets retires) sert alors de texte de reconnaissance du
     *   tiers, exactement comme le ferait un texte de payee brut a l'import.
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

        int headerLineIndex = findHeaderLineIndex(lines);
        CsvLayout layout = headerLineIndex >= 0
                ? layoutFromHeader(splitCsvLine(lines.get(headerLineIndex)))
                : CsvLayout.HOMEBANK_DEFAULT;
        int firstDataLine = headerLineIndex >= 0 ? headerLineIndex + 1 : 0;
        int minRequiredFields = Math.max(layout.dateIdx(), layout.amountIdx()) + 1;

        ImportContext ctx = new ImportContext(account, knownPayees, duplicates, result);
        for (int i = firstDataLine; i < lines.size(); i++) {
            processCsvLine(lines.get(i), i + 1, layout, minRequiredFields, ctx);
        }

        recordImportIfDataRecognized(result);
        return result;
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

    /** Traite une seule ligne de donnees du CSV (voir importCsv) : ligne vide ou insuffisante ignoree, doublon compte a part, sinon transaction creee. */
    private void processCsvLine(String line, int lineNumber, CsvLayout layout, int minRequiredFields, ImportContext ctx) {
        if (line.isBlank()) return;
        ImportResult result = ctx.result();

        String[] fields = splitCsvLine(line);
        if (fields.length < minRequiredFields) {
            result.skipped++;
            result.errors.add("Ligne " + lineNumber + " : nombre de colonnes insuffisant");
            return;
        }

        try {
            LocalDate date = parseDate(csvField(fields, layout.dateIdx()));
            String payeeText = csvField(fields, layout.payeeIdx());
            String memo = csvField(fields, layout.memoIdx());
            BigDecimal amount = parseAmount(csvField(fields, layout.amountIdx()));
            String categoryName = csvField(fields, layout.categoryIdx());

            TiersLigne payeeLine = matchPayee(payeeText, amount, ctx.knownPayees());
            String canonicalPayee = payeeLine.canonicalName();

            if (isAlreadyKnown(ctx, date, amount, canonicalPayee)) return;

            saveLine(ctx, new LigneImportee(date, amount, memo, categoryName, payeeLine));
        } catch (Exception e) {
            result.skipped++;
            result.errors.add("Ligne " + lineNumber + " : " + describeError(e));
        }
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
     * Cherche la ligne d'en-tete ("Date;..." avec au moins 3 colonnes) parmi
     * les premieres lignes non vides du fichier, en ignorant les eventuelles
     * lignes de metadonnees d'un export bancaire ("Numero Compte;...",
     * "Solde;...") qui les precedent : celles-ci n'ont que 2 colonnes
     * ("Cle;Valeur"), jamais 3 ou plus. Si aucune ligne d'en-tete n'est
     * trouvee avant la premiere ligne qui ressemble deja a une transaction
     * (3 colonnes ou plus sans commencer par "date"), le fichier est
     * considere sans en-tete (repli sur CsvLayout.HOMEBANK_DEFAULT).
     */
    private int findHeaderLineIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;

            String[] fields = splitCsvLine(line);
            if (fields.length >= 3) {
                // 3+ colonnes : soit la vraie ligne d'en-tete ("Date;..."), soit deja
                // de la donnee (pas d'en-tete du tout) — dans les deux cas la
                // recherche s'arrete ici, il ne reste qu'a distinguer les deux cas.
                return stripQuotes(fields[0]).equalsIgnoreCase("date") ? i : -1;
            }
            // sinon (< 3 colonnes) : ligne de metadonnees ("Cle;Valeur"), on continue a chercher (fin du corps de boucle)
        }
        return -1;
    }

    /**
     * Deduit les positions des colonnes utiles a partir du nombre de colonnes
     * de la ligne d'en-tete : 6 colonnes ou plus -> format HomeBank complet,
     * moins -> export bancaire brut (une seule colonne "libelle" texte).
     */
    private CsvLayout layoutFromHeader(String[] header) {
        return header.length >= 6 ? CsvLayout.HOMEBANK_DEFAULT : CsvLayout.BANK_STATEMENT;
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
            } else if (c == ';') {
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
        ImportContext ctx = new ImportContext(account, knownPayees, duplicates, result);

        List<String> lines;
        try {
            lines = decodeImportedText(file.getBytes()).lines().toList();
        } catch (IOException e) {
            result.errors.add("Erreur de lecture du fichier : " + e.getMessage());
            return result;
        }

        QifRecordState state = new QifRecordState();
        for (int i = 0; i < lines.size(); i++) {
            processQifLine(lines.get(i), i + 1, ctx, state);
        }

        recordImportIfDataRecognized(result);
        return result;
    }

    /**
     * Champs d'un enregistrement QIF en cours d'accumulation (entre deux "^"),
     * et section courante ("!Type:Bank" ou non) : etat mutable qui persiste
     * d'une ligne a l'autre du fichier tant que l'enregistrement n'est pas
     * termine (voir processQifLine/finalizeQifRecord).
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
        // On ne traite les transactions qu'apres !Type:Bank.
        boolean inBankSection;

        void markInvalid(String reason) {
            if (invalidReason == null) invalidReason = reason;
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

    /** Traite une seule ligne du fichier QIF (voir importQif) : accumule un champ de l'enregistrement en cours, ou le finalise sur "^". */
    private void processQifLine(String line, int lineNumber, ImportContext ctx, QifRecordState state) {
        if (line.isBlank()) return;

        // Gestion des sections QIF.
        if (line.startsWith("!")) {
            state.inBankSection = line.equalsIgnoreCase("!Type:Bank");
            return;
        }

        // On ignore tout ce qui precede !Type:Bank.
        if (!state.inBankSection) return;

        char code = line.charAt(0);
        String value = line.length() > 1 ? line.substring(1).trim() : "";

        try {
            switch (code) {
                case 'D' -> state.date = parseDate(value);
                case 'T', 'U' -> state.amount = parseAmount(value);
                case 'P' -> state.payeeText = value;
                case 'M' -> state.memo = value;
                case 'L' -> state.categoryName = value;
                case '^' -> finalizeQifRecord(lineNumber, ctx, state);
                default -> {
                    // Champs QIF non geres : N, C, A, etc.
                }
            }
        } catch (IllegalArgumentException e) {
            // Date/montant illisible : seul cet enregistrement sera ignore (voir finalizeQifRecord).
            state.markInvalid(e.getMessage());
        }
    }

    /** Fin d'un enregistrement QIF ("^") : cree la transaction correspondante (ou compte un doublon/une erreur), puis reinitialise l'etat pour le suivant. */
    private void finalizeQifRecord(int lineNumber, ImportContext ctx, QifRecordState state) {
        ImportResult result = ctx.result();
        try {
            if (state.invalidReason != null) {
                throw new IllegalArgumentException(state.invalidReason);
            }
            if (state.date == null || state.amount == null) {
                throw new IllegalStateException("date ou montant manquant");
            }

            TiersLigne payeeLine = matchPayee(state.payeeText, state.amount, ctx.knownPayees());
            String canonicalPayee = payeeLine.canonicalName();

            if (!isAlreadyKnown(ctx, state.date, state.amount, canonicalPayee)) {
                saveLine(ctx, new LigneImportee(state.date, state.amount, state.memo, state.categoryName, payeeLine));
            }
        } catch (Exception e) {
            result.skipped++;
            result.errors.add("Enregistrement termine ligne " + lineNumber + " : " + describeError(e));
        } finally {
            state.reset();
        }
    }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Texte d'un fichier importe. UTF-8 si le contenu est de l'UTF-8 valide
     * (marque BOM d'Excel "CSV UTF-8" retiree : sinon le premier en-tete
     * commencait par U+FEFF ("Date" non reconnu) et toutes les lignes d'un export
     * bancaire etaient rejetees), sinon Windows-1252 : encodage de beaucoup
     * d'exports de banques francaises et d'Excel "CSV (separateur : point-virgule)",
     * dont les accents devenaient sinon des caracteres de remplacement
     * U+FFFD (tiers "Pr?l?vement" non reconnus, crees en double).
     */
    static String decodeImportedText(byte[] bytes) {
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

    /**
     * Date d'une ligne importee. L'apostrophe que Quicken place avant l'annee
     * dans ses QIF ("15/01'2024", "1/15'24") est lue comme un "/" : sans ca,
     * tout l'enregistrement etait rejete en "Format de date non reconnu".
     */
    static LocalDate parseDate(String raw) {
        String trimmed = raw == null ? "" : raw.trim().replace('\'', '/');
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
                // essai du format suivant
            }
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
        String cleaned = (raw == null ? "" : raw)
                .replaceAll("[\\s\\u00A0\\u202F'€]", "")
                .replace("EUR", "")
                .replace('\u2212', '-');
        // gestion des formats "1.234,56" (europeen) et "1,234.56" (US)
        if (cleaned.contains(",") && cleaned.contains(".")) {
            if (cleaned.indexOf(',') > cleaned.indexOf('.')) {
                cleaned = cleaned.replace(".", "").replace(",", ".");
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (cleaned.contains(",")) {
            cleaned = cleaned.replace(",", ".");
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Montant non reconnu : " + raw);
        }
    }
}

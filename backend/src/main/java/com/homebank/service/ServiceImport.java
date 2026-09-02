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
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ServiceImport {

    private final ServiceCompte accountService;
    private final ServiceCategorie categoryService;
    private final DepotTransaction transactionRepository;
    private final DepotTransactionSupprimee deletedTransactionRepository;
    private final ServiceTiers payeeService;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yy")
    );

    public ServiceImport(ServiceCompte accountService, ServiceCategorie categoryService,
                          DepotTransaction transactionRepository,
                          DepotTransactionSupprimee deletedTransactionRepository,
                          ServiceTiers payeeService) {
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionRepository = transactionRepository;
        this.deletedTransactionRepository = deletedTransactionRepository;
        this.payeeService = payeeService;
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
    }

    /**
     * Determine le tiers canonique (cree automatiquement si inconnu) et la
     * categorie a appliquer, sans encore creer/sauvegarder la transaction.
     * Renvoie le tiers resolu (peut etre null si le texte brut est vide).
     *
     * `knownPayees` est charge UNE FOIS par import (voir importCsv/importQif)
     * et reutilise pour toutes les lignes du fichier, plutot que de recharger
     * tous les tiers en base a chaque ligne (un import peut contenir des
     * centaines/milliers de lignes).
     */
    private Tiers resolvePayee(String payeeText, BigDecimal amount, List<Tiers> knownPayees) {
        return payeeService.resolveOrCreate(payeeText, amount, knownPayees);
    }

    private Categorie resolveCategory(Tiers payee, String categoryNameFromFile) {
        if (categoryNameFromFile != null && !categoryNameFromFile.isBlank()) {
            return categoryService.findOrCreateByName(categoryNameFromFile);
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
     * Precharge, en une seule requete, les cles (date+montant+tiers) de
     * toutes les transactions deja enregistrees sur ce compte, ET des
     * transactions supprimees manuellement depuis (voir TransactionSupprimee /
     * ServiceTransaction#delete) : un import peut contenir des centaines/
     * milliers de lignes, et interroger la base une fois par ligne pour
     * verifier les doublons (comme avant) rendait l'import lineairement plus
     * lent avec la taille du fichier ET la taille de l'historique deja
     * importe. La detection se fait ensuite en memoire, en O(1) par ligne.
     * Inclure les transactions supprimees evite qu'une transaction volontairement
     * effacee (ex : paire "depense remboursee" nettoyee depuis l'onglet
     * Doublons) ne revienne au prochain import simplement parce qu'elle
     * figure encore dans le releve bancaire source.
     */
    private Set<CleDoublon> loadDuplicateKeys(Long accountId) {
        Set<CleDoublon> keys = new HashSet<>();
        for (Transaction t : transactionRepository.findByAccount_IdOrderByDateDescIdDesc(accountId)) {
            keys.add(CleDoublon.of(t.getDate(), t.getAmount(),
                    t.getPayee() != null ? t.getPayee().getName() : null));
        }
        for (TransactionSupprimee deleted : deletedTransactionRepository.findByAccountId(accountId)) {
            keys.add(CleDoublon.of(deleted.getDate(), deleted.getAmount(), deleted.getPayeeName()));
        }
        return keys;
    }

    /**
     * Detection de doublons, commune aux imports CSV/QIF :
     * - si un identifiant externe (fourni par la banque/le fichier) est
     *   disponible et qu'une transaction du meme compte le porte deja (encore
     *   presente, ou supprimee depuis, voir TransactionSupprimee), c'est un
     *   doublon certain ;
     * - sinon, on considere qu'il y a doublon si une transaction du meme
     *   compte, a la meme date, pour le meme montant et le meme tiers
     *   (canonique, insensible a la casse) existe deja, ou a existe puis a ete
     *   supprimee (voir CleDoublon / loadDuplicateKeys). Cette combinaison
     *   large (date+montant+tiers) evite de rejeter deux operations distinctes
     *   qui se ressembleraient partiellement.
     */
    private boolean isDuplicate(Long accountId, LocalDate date, BigDecimal amount,
                                 String canonicalPayee, String externalId, Set<CleDoublon> existingKeys) {
        if (externalId != null && !externalId.isBlank()) {
            if (transactionRepository.findByAccountIdAndExternalId(accountId, externalId).isPresent()) {
                return true;
            }
            if (deletedTransactionRepository.findByAccountIdAndExternalId(accountId, externalId).isPresent()) {
                return true;
            }
        }
        return existingKeys.contains(CleDoublon.of(date, amount, canonicalPayee));
    }

    /**
     * Positions des colonnes utiles dans un CSV, deduites de sa ligne
     * d'en-tete (voir detectLayout ci-dessous). -1 pour une colonne absente
     * de ce format (ex : pas de memo/categorie/tags separes dans un export
     * bancaire brut).
     */
    private record CsvLayout(int dateIdx, int payeeIdx, int memoIdx, int amountIdx, int categoryIdx, int tagsIdx) {

        /** Repli si aucune ligne d'en-tete n'est reconnue : format HomeBank classique, positions fixes. */
        static final CsvLayout HOMEBANK_DEFAULT = new CsvLayout(0, 3, 4, 5, 6, 7);

        /** Export bancaire brut (une seule colonne "Libelle" texte + un montant) : pas de memo/categorie/tags separes. */
        static final CsvLayout BANK_STATEMENT = new CsvLayout(0, 1, -1, 2, -1, -1);
    }

    /**
     * Import d'un fichier CSV. Reconnait deux formats :
     * - le format HomeBank : date;paymode;info;payee;memo;amount;category;tags ;
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
        ImportResult result = new ImportResult();
        List<Tiers> knownPayees = payeeService.findAllForMatching();
        Set<CleDoublon> existingKeys = loadDuplicateKeys(accountId);

        List<String> lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        } catch (IOException e) {
            result.errors.add("Erreur de lecture du fichier : " + e.getMessage());
            return result;
        }

        int headerLineIndex = findHeaderLineIndex(lines);
        CsvLayout layout = headerLineIndex >= 0
                ? layoutFromHeader(lines.get(headerLineIndex).split(";", -1))
                : CsvLayout.HOMEBANK_DEFAULT;
        int firstDataLine = headerLineIndex >= 0 ? headerLineIndex + 1 : 0;
        int minRequiredFields = Math.max(layout.dateIdx(), layout.amountIdx()) + 1;

        for (int i = firstDataLine; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i);
            if (line.isBlank()) continue;

            String[] fields = line.split(";", -1);
            if (fields.length < minRequiredFields) {
                result.skipped++;
                result.errors.add("Ligne " + lineNumber + " : nombre de colonnes insuffisant");
                continue;
            }

            try {
                LocalDate date = parseDate(csvField(fields, layout.dateIdx()));
                String payeeText = csvField(fields, layout.payeeIdx());
                String memo = csvField(fields, layout.memoIdx());
                BigDecimal amount = parseAmount(csvField(fields, layout.amountIdx()));
                String categoryName = csvField(fields, layout.categoryIdx());
                String tags = csvField(fields, layout.tagsIdx());

                Tiers payee = resolvePayee(payeeText, amount, knownPayees);
                String canonicalPayee = payee != null ? payee.getName() : payeeText;

                if (isDuplicate(accountId, date, amount, canonicalPayee, null, existingKeys)) {
                    result.duplicates++;
                    continue;
                }

                Transaction t = new Transaction();
                t.setAccount(account);
                t.setDate(date);
                t.setAmount(amount);
                t.setMemo(sanitizeMemo(memo));
                t.setTags(tags);
                t.setPayee(payee);
                t.setCategory(resolveCategory(payee, categoryName));
                t.setValidated(false); // toute transaction importee demarre "en attente"
                transactionRepository.save(t);
                existingKeys.add(CleDoublon.of(date, amount, canonicalPayee));
                result.imported++;
            } catch (Exception e) {
                result.skipped++;
                result.errors.add("Ligne " + lineNumber + " : " + e.getMessage());
            }
        }

        return result;
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

            String[] fields = line.split(";", -1);
            if (fields.length >= 3 && stripQuotes(fields[0]).equalsIgnoreCase("date")) {
                return i;
            }
            if (fields.length < 3) continue; // ligne de metadonnees ("Cle;Valeur"), on continue a chercher
            return -1; // deja de la donnee (3+ colonnes, ne commence pas par "date") : pas d'en-tete du tout
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
        ImportResult result = new ImportResult();
        List<Tiers> knownPayees = payeeService.findAllForMatching();
        Set<CleDoublon> existingKeys = loadDuplicateKeys(accountId);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            LocalDate date = null;
            BigDecimal amount = null;
            String payeeText = null;
            String memo = null;
            String categoryName = null;
            int lineNumber = 0;

            // On ne traite les transactions qu'après !Type:Bank
            boolean inBankSection = false;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) {
                    continue;
                }

                // Gestion des sections QIF
                if (line.startsWith("!")) {
                    inBankSection = line.equalsIgnoreCase("!Type:Bank");
                    continue;
                }

                // On ignore tout ce qui précède !Type:Bank
                if (!inBankSection) {
                    continue;
                }

                char code = line.charAt(0);
                String value = line.length() > 1
                        ? line.substring(1).trim()
                        : "";

                switch (code) {
                    case 'D' -> date = parseDate(value);

                    case 'T', 'U' -> amount = parseAmount(value);

                    case 'P' -> payeeText = value;

                    case 'M' -> memo = value;

                    case 'L' -> categoryName = value;

                    case '^' -> {
                        // Fin d'enregistrement -> création de la transaction
                        try {
                            if (date == null || amount == null) {
                                throw new IllegalStateException(
                                        "date ou montant manquant"
                                );
                            }

                            Tiers payee = resolvePayee(payeeText, amount, knownPayees);
                            String canonicalPayee = payee != null ? payee.getName() : payeeText;

                            if (isDuplicate(accountId, date, amount, canonicalPayee, null, existingKeys)) {
                                result.duplicates++;
                            } else {
                                Transaction t = new Transaction();
                                t.setAccount(account);
                                t.setDate(date);
                                t.setAmount(amount);
                                t.setMemo(sanitizeMemo(memo));
                                t.setPayee(payee);
                                t.setCategory(resolveCategory(payee, categoryName));
                                t.setValidated(false);

                                transactionRepository.save(t);
                                existingKeys.add(CleDoublon.of(date, amount, canonicalPayee));
                                result.imported++;
                            }

                        } catch (Exception e) {
                            result.skipped++;
                            result.errors.add(
                                    "Enregistrement terminé ligne "
                                            + lineNumber
                                            + " : "
                                            + e.getMessage()
                            );
                        } finally {
                            // Réinitialisation pour la prochaine transaction
                            date = null;
                            amount = null;
                            payeeText = null;
                            memo = null;
                            categoryName = null;
                        }
                    }

                    default -> {
                        // Champs QIF non gérés : N, C, A, etc.
                    }
                }
            }

        } catch (IOException e) {
            result.errors.add(
                    "Erreur de lecture du fichier : " + e.getMessage()
            );
        }

        return result;
    }

    private LocalDate parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
                // essai du format suivant
            }
        }
        throw new IllegalArgumentException("Format de date non reconnu : " + raw);
    }

    private BigDecimal parseAmount(String raw) {
        String cleaned = raw.replace(" ", "").replace("'", "");
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
        return new BigDecimal(cleaned);
    }
}

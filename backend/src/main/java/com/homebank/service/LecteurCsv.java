package com.homebank.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Lecture d'un releve CSV en operations (voir OperationLue), sans rien
 * enregistrer : separateur, ligne d'en-tete, colonnes reconnues par leur nom,
 * champs entre guillemets. L'enregistrement est fait par ServiceImport.
 */
final class LecteurCsv {

    /** Colonne Debit/Credit sans montant, ecrite par certaines banques avec un simple tiret. */
    private static final Pattern NO_AMOUNT_PLACEHOLDER = Pattern.compile("[-\\u2013\\u2014]?");

    // Mots recherches dans les en-tetes CSV normalises, par ordre de priorite (voir layoutFromHeader).
    private static final List<String> PAYEE_HEADERS =
            List.of("payee", "tiers", "beneficiaire", "libelle", "label", "intitule", "description");
    private static final List<String> MEMO_HEADERS = List.of("memo", "comment", "note", "detail");
    private static final List<String> CATEGORY_HEADERS = List.of("categorie", "category");
    private static final List<String> AMOUNT_HEADERS = List.of("montant", "amount");

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
     * Operations d'un fichier CSV deja decode en lignes. Reconnait deux formats :
     * - le format HomeBank : date;paymode;info;payee;memo;amount;category;tags
     *   (colonnes paymode, info et tags ignorees) ;
     * - un export bancaire brut (ex : "Date;Libelle;Montant(EUROS)" ou
     *   "Date;Date de valeur;Debit;Credit;Libelle;Solde"), eventuellement
     *   precede de lignes de metadonnees ("Numero Compte;...", "Solde;..."),
     *   ignorees. Ses colonnes sont reconnues par leur nom (voir layoutFromHeader).
     * Le separateur est deduit du fichier (voir detectSeparator).
     */
    static List<OperationLue> lire(List<String> lines) {
        char separator = detectSeparator(lines);
        int headerLineIndex = findHeaderLineIndex(lines, separator);
        CsvLayout layout = headerLineIndex >= 0
                ? layoutFromHeader(splitCsvLine(lines.get(headerLineIndex), separator))
                : CsvLayout.HOMEBANK_DEFAULT;
        int firstDataLine = headerLineIndex >= 0 ? headerLineIndex + 1 : 0;
        CsvFormat format = new CsvFormat(layout, separator, layout.minRequiredFields());

        List<OperationLue> operations = new ArrayList<>();
        int i = firstDataLine;
        while (i < lines.size()) {
            // Un enregistrement peut s'etendre sur plusieurs lignes (champ entre guillemets).
            int lastLine = lines.get(i).isBlank() ? i : endOfCsvRecord(lines, i, separator);
            if (!lines.get(i).isBlank()) {
                String csvRecord = String.join("\n", lines.subList(i, lastLine + 1));
                operations.add(readCsvLine(csvRecord, i + 1, format));
            }
            i = lastLine + 1;
        }
        return operations;
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
            return new OperationLue(origin, ValeursReleve.parseDate(csvField(fields, layout.dateIdx())), csvAmount(fields, layout),
                    csvText(fields, layout.payeeIdx()), csvText(fields, layout.memoIdx()),
                    csvText(fields, layout.categoryIdx()), null);
        } catch (RuntimeException e) {
            return OperationLue.enErreur(origin, ValeursReleve.describeError(e));
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
     * Cherche la ligne d'en-tete (au moins 3 colonnes, la premiere commencant
     * par "date" : "Date", "Date de l'operation", "dateOp"...), en ignorant
     * les lignes de metadonnees d'un export bancaire qui la precedent
     * ("Numero Compte;...", "Compte;FR76...;EUR"). La recherche s'arrete a la
     * premiere ligne qui commence par une date valide : le fichier est alors
     * sans en-tete (repli sur CsvLayout.HOMEBANK_DEFAULT).
     */
    private static int findHeaderLineIndex(List<String> lines, char separator) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] fields = line.isBlank() ? new String[0] : splitCsvLine(line, separator);
            if (fields.length >= 3) {
                if (normalizeHeader(fields[0]).startsWith("date")) return i;
                if (isDate(fields[0])) return -1;
            }
        }
        return -1;
    }

    private static boolean isDate(String raw) {
        try {
            ValeursReleve.parseDate(raw);
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
        List<String> columns = Arrays.stream(header).map(LecteurCsv::normalizeHeader).toList();
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
            return ValeursReleve.parseAmount(csvField(fields, layout.amountIdx()));
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
        return hasNoAmount(raw) ? BigDecimal.ZERO : ValeursReleve.parseAmount(raw).abs();
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
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    current.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++; // guillemet double "" : un seul guillemet dans la valeur
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
            i++;
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

    private LecteurCsv() {
    }
}

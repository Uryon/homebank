package com.homebank.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lecture d'un releve QIF (Quicken Interchange Format) en operations (voir
 * OperationLue), sans rien enregistrer. Champs geres : D (date), T (montant),
 * P (tiers), M (memo), L (categorie). L'enregistrement est fait par ServiceImport.
 */
final class LecteurQif {

    /** Operations d'un fichier QIF deja decode en lignes. */
    static List<OperationLue> lire(List<String> lines) {
        QifRecordState state = new QifRecordState();
        List<OperationLue> operations = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            readQifLine(lines.get(i), i + 1, operations, state);
        }
        finalizeUnterminatedQifRecord(lines.size(), operations, state);
        return operations;
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
                case 'D' -> state.date = ValeursReleve.parseDate(value);
                case 'T', 'U' -> state.amount = ValeursReleve.parseAmount(value);
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

    private LecteurQif() {
    }
}

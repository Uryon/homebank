package com.homebank.service;

import com.homebank.utils.Montant;
import com.homebank.utils.Periode;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Valeurs lues dans un releve importe (CSV ou QIF, voir LecteurCsv et
 * LecteurQif) : texte du fichier selon son encodage, dates et montants dans
 * les differentes ecritures des banques et logiciels.
 */
final class ValeursReleve {

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
    /**
     * Texte d'erreur affiche a l'utilisateur pour une ligne ignoree : le
     * message de l'exception, ou a defaut son type (une NullPointerException
     * sans message s'affichait sinon "Ligne 2 : null").
     */
    static String describeError(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
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
        String rawText = raw == null ? "" : raw.trim();
        String withoutTime = TIME_SUFFIX.matcher(rawText).replaceFirst("");
        String trimmed = withoutTime.replace('\'', '/');
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            LocalDate date;
            try {
                date = LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
                // essai du format suivant
                continue;
            }
            Periode.verifierAnnee(date, "La date « " + rawText + " »");
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
        String rawText = raw == null ? "" : raw.trim();
        String cleaned = AMOUNT_DECORATION.matcher(rawText).replaceAll("")
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
        return Montant.auCentime(negated ? amount.negate() : amount, "Le montant « " + rawText + " »");
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

    private ValeursReleve() {
    }
}

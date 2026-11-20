package com.homebank.service;

import com.homebank.model.Tiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Motifs de reconnaissance des tiers : normalisation, motifs simples et a
 * jokers (*), motifs prepares une fois pour toutes, et nom/motif suggeres
 * pour un tiers cree a partir d'un libelle bancaire importe. Sans etat :
 * partage par ServiceTiers, l'import, la detection des doublons et le
 * nettoyage des faux tiers.
 */
final class MotifsTiers {

    private static final Logger log = LoggerFactory.getLogger(MotifsTiers.class);

    /**
     * Suite d'espaces au sens large : \s (ASCII) plus l'espace insecable
     * (U+00A0, frequente dans les exports bancaires Windows-1252) et les
     * espaces fines (U+2007, U+202F). Meme definition que \s cote frontend
     * (JavaScript), pour que la page Doublons et l'import reconnaissent les
     * memes libelles. Compilee une seule fois : normalizeWhitespace est
     * appelee pour chaque tiers et chaque ligne d'un import.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    // Le champ "pattern" d'un tiers, tel que stocke et affiche, est du texte
    // simple SANS etoile ("Carrefour"). Les etoiles ne sont ajoutees que lors
    // du TRAITEMENT (matching), jamais dans les donnees ni dans l'interface :
    //   - "Carrefour"        -> traite comme "*Carrefour*" au moment de la
    //                           comparaison : reconnait tout texte CONTENANT
    //                           "Carrefour" (insensible a la casse)
    //   - "Carrefour*Paris"  -> motif avance : l'utilisateur a lui-meme mis
    //                           une etoile au milieu, donc pas d'etoiles
    //                           ajoutees en plus ; reconnait "Carrefour" suivi
    //                           n'importe ou plus loin de "Paris"
    // Plusieurs motifs peuvent etre combines en les separant par un point-virgule
    // ";" (utilise notamment lors d'une fusion de tiers) : le tiers matche si
    // AU MOINS UN des motifs correspond.

    /**
     * Motif par defaut a partir d'un nom ou d'un libelle : le texte lui-meme,
     * sans etoile ajoutee. Un ";" y devient "*" : garde tel quel, il separait
     * le texte en motifs independants, et "VIR ACME; REF 2025" reconnaissait
     * alors tout libelle contenant "REF 2025", attribue au mauvais tiers.
     */
    static String defaultPattern(String text) {
        return text != null ? text.trim().replace(';', '*') : "";
    }

    /**
     * Normalise un motif saisi par l'utilisateur : se contente de nettoyer les
     * espaces autour de chaque morceau separe par ";", sans jamais ajouter
     * d'etoile (le champ reste du texte simple, l'ajout des etoiles se fait
     * uniquement au traitement, voir matchesPatternField).
     */
    static String normalizePatternSegments(String rawField) {
        String[] parts = rawField.split(";", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result.append(";");
            result.append(parts[i].trim());
        }
        return result.toString();
    }

    /**
     * Motif saisi, normalise. Un morceau fait uniquement d'etoiles reconnaitrait
     * n'importe quel texte : ce tiers absorberait alors toutes les operations
     * importees et tous les tiers saisis (voir matchPayee), d'ou le refus.
     */
    static String validatedPattern(String rawField) {
        String pattern = normalizePatternSegments(rawField);
        // Ex : ";" ou un caractere de controle seul, retire par le nettoyage.
        if (pattern.replace(";", "").isBlank()) {
            throw new IllegalArgumentException("Le motif doit contenir un texte à reconnaître.");
        }
        for (String part : pattern.split(";")) {
            if (!part.isEmpty() && part.replace("*", "").isBlank()) {
                throw new IllegalArgumentException(
                        "Le motif « " + part + " » reconnaîtrait toutes les opérations : indiquez un texte à rechercher.");
            }
        }
        return pattern;
    }

    /** Retire les etoiles en tete et en fin de motif ("*Carrefour*Paris*" -> "Carrefour*Paris"). */
    static String stripOuterStars(String wildcardPattern) {
        int start = 0;
        int end = wildcardPattern.length();
        while (start < end && wildcardPattern.charAt(start) == '*') start++;
        while (end > start && wildcardPattern.charAt(end - 1) == '*') end--;
        return wildcardPattern.substring(start, end);
    }

    /** Convertit un seul motif "joker" (avec *) en expression reguliere Java equivalente. */
    static String wildcardToRegex(String wildcardPattern) {
        String[] parts = wildcardPattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) regex.append(".*");
            if (!parts[i].isEmpty()) regex.append(Pattern.quote(parts[i]));
        }
        return regex.toString();
    }

    /**
     * Reduit toute suite d'espaces/tabulations a un seul espace (et trim).
     * Les releves bancaires inserent souvent un nombre d'espaces variable entre
     * les mots (ex : "SGC  TOURCOING" avec deux espaces) : sans cette
     * normalisation, un motif stocke avec un espace simple ("SGC TOURCOING")
     * ne matcherait jamais ce genre de libelle, alors que visuellement le
     * texte "correspond" bien pour l'utilisateur.
     */
    static String normalizeWhitespace(String s) {
        if (s == null || isWhitespaceNormalized(s)) return s;
        return WHITESPACE_RUN.matcher(s).replaceAll(" ").trim();
    }

    /**
     * Vrai si normalizeWhitespace laisserait le texte inchange : ni espace en
     * bord, ni espaces consecutifs, ni autre caractere d'espacement (tabulation,
     * insecable...) ou de controle. Cas de presque tous les motifs de tiers :
     * evite l'expression reguliere, appelee pour chaque tiers et chaque ligne
     * d'un import (5000 lignes x 600 tiers : ~6 s sur ~8 s d'un reimport).
     * Dans le doute (caractere de controle...), renvoie false : le chemin
     * complet donne alors le resultat exact.
     */
    static boolean isWhitespaceNormalized(String s) {
        int length = s.length();
        if (length == 0) return true;
        if (s.charAt(0) <= ' ' || s.charAt(length - 1) <= ' ') return false;
        char previous = 0;
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i);
            boolean extraSpace = c == ' ' && previous == ' ';
            boolean otherWhitespace = c < ' ' || c == '\u00A0' || c == '\u2007' || c == '\u202F';
            if (extraSpace || otherWhitespace) return false;
            previous = c;
        }
        return true;
    }

    /**
     * Teste si le champ "pattern" (un ou plusieurs motifs separes par ";")
     * correspond au texte donne. Aussi utilise par
     * ServiceTransaction#payeesMayBeSameMerchant (detection des doublons probables).
     */
    static boolean matchesPatternField(String patternField, String text) {
        if (text == null || text.isBlank()) return false;
        return motifPrepare(patternField).reconnait(LibelleCompare.de(text));
    }

    /** Libelle prepare une seule fois pour etre compare a tous les tiers. */
    record LibelleCompare(String normalized, String lower) {
        static LibelleCompare de(String text) {
            String normalized = text == null ? "" : normalizeWhitespace(text);
            return new LibelleCompare(normalized, normalized.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Champ "pattern" analyse une fois pour toutes : la comparaison est appelee
     * pour chaque tiers et chaque ligne d'un import (des millions de fois), et
     * redecoupait, normalisait et recompilait le meme motif a chaque appel.
     */
    record MotifPrepare(List<String> texts, List<Pattern> wildcards, boolean matchesEverything) {
        private static final MotifPrepare VIDE = new MotifPrepare(List.of(), List.of(), false);

        boolean reconnait(LibelleCompare label) {
            if (matchesEverything) return true;
            for (String text : texts) {
                if (label.lower().contains(text)) return true;
            }
            for (Pattern wildcard : wildcards) {
                if (wildcard.matcher(label.normalized()).find()) return true;
            }
            return false;
        }
    }

    /** Borne de securite du cache : largement au-dessus du nombre de tiers d'un utilisateur. */
    private static final int MAX_MOTIFS_PREPARES = 20_000;
    private static final Map<String, MotifPrepare> MOTIFS_PREPARES = new ConcurrentHashMap<>();

    static MotifPrepare motifPrepare(String patternField) {
        if (patternField == null || patternField.isBlank()) return MotifPrepare.VIDE;
        MotifPrepare known = MOTIFS_PREPARES.get(patternField);
        if (known != null) return known;
        if (MOTIFS_PREPARES.size() >= MAX_MOTIFS_PREPARES) MOTIFS_PREPARES.clear();
        MotifPrepare prepared = preparerMotif(patternField);
        MOTIFS_PREPARES.put(patternField, prepared);
        return prepared;
    }

    /**
     * Ajoute l'expression d'un motif a etoile(s). Motif invalide (ne devrait
     * plus arriver avec le systeme "joker") : segment ignore, mais l'anomalie
     * est tracee plutot que masquee.
     */
    static void addWildcard(List<Pattern> wildcards, String core, String rawPart) {
        try {
            wildcards.add(Pattern.compile(wildcardToRegex(core), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        } catch (PatternSyntaxException e) {
            log.warn("Motif de tiers invalide ignore : \"{}\" ({})", rawPart, e.getMessage());
        }
    }

    static MotifPrepare preparerMotif(String patternField) {
        List<String> texts = new ArrayList<>();
        List<Pattern> wildcards = new ArrayList<>();
        for (String rawPart : patternField.split(";")) {
            String part = normalizeWhitespace(rawPart);
            if (part == null || part.isEmpty()) continue;
            if (part.indexOf('*') < 0) {
                // Texte simple, sans etoile ("Carrefour"), reconnu partout ou il
                // apparait dans le libelle : simple test "contient", insensible a
                // la casse, bien plus rapide qu'une expression ".*Carrefour.*".
                texts.add(part.toLowerCase(Locale.ROOT));
            } else {
                // Motif avance avec etoile(s) (ex : "Carrefour*Paris") : expression
                // reguliere. Les etoiles de debut/fin sont inutiles (find() cherche
                // deja dans tout le libelle) et coutaient cher : retirees.
                String core = stripOuterStars(part);
                if (core.isEmpty()) return new MotifPrepare(List.of(), List.of(), true); // motif "*" : correspond a tout
                addWildcard(wildcards, core, rawPart);
            }
        }
        return new MotifPrepare(List.copyOf(texts), List.copyOf(wildcards), false);
    }

    /**
     * Libelle bancaire d'operation par carte, prelevement ou virement : prefixe
     * de la banque (groupe 1), commercant (groupe 2), puis suffixe variable a
     * chaque operation (date "jj.mm.aa", n° de carte, montant en devise,
     * reference de prelevement...). Ex :
     * "ACHAT CB SumUp DOMOUS 09.08.26 CARTE NUMERO 697".
     */
    private static final List<String> BANK_PREFIXES =
            List.of("ACHAT CB", "CREDIT CARTE BANCAIRE", "PRELEVEMENT DE", "VIREMENT DE");
    private static final Pattern BANK_LABEL = Pattern.compile(
            "^(" + String.join("|", BANK_PREFIXES) + ")\\s+(.+?)"
                    + "(?:,?\\s+\\d{2}\\.\\d{2}\\.\\d{2}\\b.*|\\s+REF\\s*:.*)$",
            Pattern.CASE_INSENSITIVE);

    /** Nom et motif d'un tiers cree automatiquement a l'import. */
    record TiersSuggere(String name, String pattern) {}

    /**
     * Nom et motif d'un tiers a creer pour un libelle bancaire importe. Le
     * libelle brut contient la date et le n° de carte : utilise tel quel comme
     * motif, il ne reconnaissait que cette operation-la, et chaque achat
     * suivant chez le meme commercant creait un nouveau tiers. On garde donc :
     * - comme nom, le commercant seul ("SumUp DOMOUS") ;
     * - comme motif, prefixe + commercant ("ACHAT CB SumUp DOMOUS") : le
     *   prefixe evite qu'un nom court ("Action") reconnaisse n'importe quel
     *   libelle le contenant ("TRANSACTION...").
     * Libelle d'une autre forme : nom et motif = libelle, comme avant.
     */
    static TiersSuggere suggestFromBankLabel(String rawText) {
        String label = normalizeWhitespace(rawText);
        Matcher matcher = BANK_LABEL.matcher(label);
        if (!matcher.matches()) return new TiersSuggere(label, defaultPattern(label));
        String merchant = matcher.group(2).trim();
        return new TiersSuggere(merchant, defaultPattern(matcher.group(1) + " " + merchant));
    }

    /**
     * Le tiers est-il encore tel que cree automatiquement (sans categorie ni
     * montant, motif = nom ou prefixe bancaire + nom, voir suggestFromBankLabel) ?
     * Un tiers renomme, complete ou dont le motif a ete retouche ne l'est plus.
     */
    static boolean isAutoCreatedState(Tiers payee) {
        if (payee.getCategory() != null || payee.getAmount() != null) return false;
        String pattern = payee.getPattern();
        String name = payee.getName();
        return pattern.equals(defaultPattern(name))
                || BANK_PREFIXES.stream().anyMatch(prefix -> pattern.equalsIgnoreCase(defaultPattern(prefix + " " + name)));
    }

    private MotifsTiers() {
    }
}

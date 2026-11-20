package com.homebank.service;

import com.homebank.dto.CategorisationAutoResultatDTO;
import com.homebank.dto.TiersDTO;
import com.homebank.mapper.MappeurTiers;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.LongueurTexte;
import com.homebank.utils.Montant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Service
public class ServiceTiers {

    private static final Logger log = LoggerFactory.getLogger(ServiceTiers.class);

    /**
     * Suite d'espaces au sens large : \s (ASCII) plus l'espace insecable
     * (U+00A0, frequente dans les exports bancaires Windows-1252) et les
     * espaces fines (U+2007, U+202F). Meme definition que \s cote frontend
     * (JavaScript), pour que la page Doublons et l'import reconnaissent les
     * memes libelles. Compilee une seule fois : normalizeWhitespace est
     * appelee pour chaque tiers et chaque ligne d'un import.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");

    private final DepotTiers payeeRepository;
    private final ServiceCategorie categoryService;
    private final MappeurTiers payeeMapper;
    private final DepotTransaction transactionRepository;

    public ServiceTiers(DepotTiers payeeRepository, ServiceCategorie categoryService,
                         MappeurTiers payeeMapper, DepotTransaction transactionRepository) {
        this.payeeRepository = payeeRepository;
        this.categoryService = categoryService;
        this.payeeMapper = payeeMapper;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public List<TiersDTO> findAll() {
        return payeeRepository.findAll()
                .stream()
                .map(payeeMapper::toDTO)
                .toList();
    }

    /** Entite tiers par id (404 si absente), pour les services qui l'affectent directement (voir ServiceTransaction#bulkUpdate). */
    public Tiers findEntityById(Long id) {
        return payeeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tiers introuvable : " + id));
    }

    public TiersDTO create(TiersDTO payload) {
        Tiers p = new Tiers();
        applyPayload(p, payload);
        Tiers saved = payeeRepository.save(p);
        log.info("Tiers cree : {} ({})", saved.getName(), saved.getId());
        return payeeMapper.toDTO(saved);
    }

    /**
     * Renomme un tiers : comme les transactions liees pointent vers ce tiers
     * par une vraie relation (Transaction#payee), et non plus par une copie
     * de son nom, elles affichent deja le nouveau nom sans etape a part.
     */
    public TiersDTO update(Long id, TiersDTO payload) {
        Tiers existing = findEntityById(id);
        applyPayload(existing, payload);
        log.info("Tiers modifie : {} ({})", existing.getName(), id);
        return payeeMapper.toDTO(payeeRepository.save(existing));
    }

    /**
     * Supprime un tiers inutilise. Un tiers encore reference par des
     * transactions est refuse (409) avec un message explicite, plutot que de
     * laisser la cle etrangere echouer avec un message generique : la
     * fusion vers un autre tiers est alors la bonne operation.
     */
    public void delete(Long id) {
        Tiers payee = findEntityById(id);
        long transactionCount = transactionRepository.countByPayee(payee);
        if (transactionCount > 0) {
            throw new IllegalStateException("Ce tiers est utilisé par " + transactionCount
                    + " transaction(s). Fusionnez-le avec un autre tiers plutôt que de le supprimer.");
        }
        payeeRepository.delete(payee);
        log.info("Tiers supprime : {} ({})", payee.getName(), id);
    }

    private void applyPayload(Tiers p, TiersDTO payload) {
        String name = payload.getName() != null ? payload.getName().trim() : "";
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Le nom du tiers est obligatoire.");
        }
        LongueurTexte.verifier(name, "Le nom du tiers");
        p.setName(name);

        // Le champ "pattern" est du texte simple, stocke et affiche TEL QUEL
        // (sans etoile) : "Carrefour" suffit. Les etoiles ne sont ajoutees
        // qu'au moment du traitement (voir matchesPatternField), jamais dans
        // les donnees stockees ni dans l'interface.
        String pattern = payload.getPattern();
        if (pattern == null || pattern.isBlank()) {
            pattern = (p.getPattern() != null && !p.getPattern().isBlank())
                    ? p.getPattern()
                    : defaultPattern(name);
        } else {
            pattern = validatedPattern(pattern);
        }
        p.setPattern(pattern);

        BigDecimal amount = Montant.auCentime(payload.getAmount(), "Le montant du tiers");
        p.setAmount(amount != null ? amount.abs() : null);
        if (payload.getCategoryId() != null) {
            Categorie category = categoryService.findById(payload.getCategoryId());
            p.setCategory(category);
        } else {
            p.setCategory(null);
        }
    }

    // =========================================================================
    // Reconnaissance des tiers par "motif joker" (pas de vraie regex a connaitre)
    // =========================================================================
    //
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
    private static String defaultPattern(String text) {
        return text != null ? text.trim().replace(';', '*') : "";
    }

    /**
     * Normalise un motif saisi par l'utilisateur : se contente de nettoyer les
     * espaces autour de chaque morceau separe par ";", sans jamais ajouter
     * d'etoile (le champ reste du texte simple, l'ajout des etoiles se fait
     * uniquement au traitement, voir matchesPatternField).
     */
    private static String normalizePatternSegments(String rawField) {
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
    private static String validatedPattern(String rawField) {
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
    private static String stripOuterStars(String wildcardPattern) {
        int start = 0;
        int end = wildcardPattern.length();
        while (start < end && wildcardPattern.charAt(start) == '*') start++;
        while (end > start && wildcardPattern.charAt(end - 1) == '*') end--;
        return wildcardPattern.substring(start, end);
    }

    /** Convertit un seul motif "joker" (avec *) en expression reguliere Java equivalente. */
    private static String wildcardToRegex(String wildcardPattern) {
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
    private static boolean isWhitespaceNormalized(String s) {
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
    private record LibelleCompare(String normalized, String lower) {
        static LibelleCompare de(String text) {
            String normalized = normalizeWhitespace(text);
            return new LibelleCompare(normalized, normalized.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Champ "pattern" analyse une fois pour toutes : la comparaison est appelee
     * pour chaque tiers et chaque ligne d'un import (des millions de fois), et
     * redecoupait, normalisait et recompilait le meme motif a chaque appel.
     */
    private record MotifPrepare(List<String> texts, List<Pattern> wildcards, boolean matchesEverything) {
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

    private static MotifPrepare motifPrepare(String patternField) {
        if (patternField == null || patternField.isBlank()) return MotifPrepare.VIDE;
        MotifPrepare known = MOTIFS_PREPARES.get(patternField);
        if (known != null) return known;
        if (MOTIFS_PREPARES.size() >= MAX_MOTIFS_PREPARES) MOTIFS_PREPARES.clear();
        MotifPrepare prepared = preparerMotif(patternField);
        MOTIFS_PREPARES.put(patternField, prepared);
        return prepared;
    }

    private static MotifPrepare preparerMotif(String patternField) {
        List<String> texts = new ArrayList<>();
        List<Pattern> wildcards = new ArrayList<>();
        for (String rawPart : patternField.split(";")) {
            String part = normalizeWhitespace(rawPart);
            if (part == null || part.isEmpty()) continue;
            // Texte simple, sans etoile ("Carrefour"), reconnu partout ou il
            // apparait dans le libelle : simple test "contient", insensible a
            // la casse, bien plus rapide qu'une expression ".*Carrefour.*".
            if (part.indexOf('*') < 0) {
                texts.add(part.toLowerCase(Locale.ROOT));
                continue;
            }
            // Motif avance avec etoile(s) (ex : "Carrefour*Paris") : expression
            // reguliere. Les etoiles de debut/fin sont inutiles (find() cherche
            // deja dans tout le libelle) et coutaient cher : retirees.
            String core = stripOuterStars(part);
            if (core.isEmpty()) return new MotifPrepare(List.of(), List.of(), true); // motif "*" : correspond a tout
            try {
                wildcards.add(Pattern.compile(wildcardToRegex(core), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                // motif invalide (ne devrait plus arriver avec le systeme "joker") : ignore ce
                // segment mais trace l'anomalie plutot que de la masquer silencieusement
                log.warn("Motif de tiers invalide ignore : \"{}\" ({})", rawPart, e.getMessage());
            }
        }
        return new MotifPrepare(List.copyOf(texts), List.copyOf(wildcards), false);
    }

    /**
     * Cherche, parmi tous les tiers connus, celui qui correspond le mieux au
     * texte et au montant donnes (ex : le champ "payee" et le montant d'une
     * transaction importee).
     *
     * Deux tiers peuvent porter le meme texte reconnu mais correspondre a des
     * montants differents (ex : "Bouygues" a 10E vs 20E) : on les considere
     * comme des tiers DIFFERENTS. Un tiers sans montant renseigne (amount == null)
     * est un tiers "generique" qui matche quel que soit le montant, et sert de
     * repli si aucun tiers avec le montant exact n'est trouve.
     *
     * @param amount montant en valeur absolue (le signe depense/revenu n'entre
     *               pas en compte dans la distinction des tiers)
     */
    public Optional<Tiers> matchPayee(String text, BigDecimal amount) {
        return matchPayee(text, amount, payeeRepository.findAll());
    }

    /**
     * Variante de matchPayee qui reutilise une liste de tiers deja chargee en
     * memoire au lieu de refaire payeeRepository.findAll() a chaque appel.
     * Utilisee pour les traitements en boucle (import CSV/QIF, initialisation
     * depuis les transactions existantes) afin de ne charger tous les tiers
     * qu'une seule fois pour tout le lot, plutot qu'une fois par ligne.
     */
    Optional<Tiers> matchPayee(String text, BigDecimal amount, List<Tiers> candidates) {
        if (text == null || text.isBlank()) return Optional.empty();
        Tiers genericMatch = null;
        LibelleCompare label = LibelleCompare.de(text);

        for (Tiers payee : candidates) {
            if (!motifPrepare(payee.getPattern()).reconnait(label)) continue;

            if (payee.getAmount() == null) {
                if (genericMatch == null) genericMatch = payee;
            } else if (amount != null && payee.getAmount().compareTo(amount) == 0) {
                return Optional.of(payee); // correspondance exacte nom + montant : priorite absolue
            }
        }
        return Optional.ofNullable(genericMatch);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    /**
     * Reconnait un tiers a partir d'un texte brut importe et d'un montant :
     * - si un tiers existant correspond (texte + montant exact, ou texte +
     *   tiers generique sans montant), on le renvoie tel quel (son nom peut
     *   avoir ete renomme manuellement par l'utilisateur : c'est ce nom
     *   "canonique" qui sera utilise sur la transaction) ;
     * - sinon, un nouveau tiers "generique" (SANS montant, voir Payee#amount)
     *   est cree automatiquement avec pour nom ET pour motif le texte brut
     *   rencontre (tel quel, sans etoile ajoutee : voir matchesPatternField
     *   pour le traitement). Le montant n'est jamais renseigne automatiquement
     *   a la creation : sinon, un meme texte brut revenant a un montant
     *   different a chaque fois (ex : plusieurs achats Vinted a des prix
     *   variables) creerait un nouveau tiers par montant distinct au lieu de
     *   les regrouper sous un seul tiers generique. Associer un montant
     *   precis a un tiers, pour distinguer deux tiers reels qui partagent le
     *   meme texte brut (ex : "Bouygues Mel" a 10E vs "Bouygues Val" a 20E),
     *   reste une action manuelle de l'utilisateur depuis la page Tiers.
     */
    public Tiers resolveOrCreate(String rawText, BigDecimal amount) {
        return resolveOrCreate(rawText, amount, payeeRepository.findAll());
    }

    /**
     * Variante de resolveOrCreate qui reutilise une liste de tiers deja
     * chargee en memoire (voir matchPayee(String, BigDecimal, List)) : le
     * tiers eventuellement cree est ajoute a cette liste, pour que les lignes
     * suivantes du meme lot le retrouvent sans requete supplementaire.
     */
    Tiers resolveOrCreate(String rawText, BigDecimal amount, List<Tiers> knownPayees) {
        if (hasNoText(rawText)) return null;
        return findExisting(rawText, amount, knownPayees)
                .orElseGet(() -> createFromText(rawText, knownPayees));
    }

    /** trim() retire aussi les caracteres de controle, que isBlank() laisse passer. */
    private static boolean hasNoText(String text) {
        return text == null || text.trim().isEmpty() || text.isBlank();
    }

    /**
     * Tiers deja connu correspondant au texte brut (par motif, sinon par nom
     * exact), SANS jamais en creer : permet a l'import de verifier qu'une
     * ligne n'est pas un doublon avant de creer un tiers pour elle (voir
     * ServiceImport). Vide si le texte est vide ou ne correspond a aucun tiers.
     */
    Optional<Tiers> findExisting(String rawText, BigDecimal amount, List<Tiers> knownPayees) {
        if (hasNoText(rawText)) return Optional.empty();
        String trimmed = rawText.trim();
        BigDecimal normalizedAmount = amount != null ? amount.abs() : null;

        // Motif + montant exact : c'est ce qui distingue deux tiers au meme libelle.
        Optional<Tiers> matched = matchPayee(trimmed, normalizedAmount, knownPayees);
        if (matched.isPresent() && matched.get().getAmount() != null) return matched;

        // Le nom exact passe avant un motif generique : choisir « Carrefour
        // Market » ne doit pas donner « Carrefour », dont le motif est contenu
        // dans ce nom. Il evite aussi un doublon si le motif du tiers ne
        // reconnait pas son propre nom. Un tiers generique (amount == null)
        // convient quel que soit le montant, comme dans matchPayee.
        Optional<Tiers> sameName = knownPayees.stream()
                .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(trimmed))
                .filter(p -> p.getAmount() == null || sameAmount(p.getAmount(), normalizedAmount))
                .findFirst();
        return sameName.isPresent() ? sameName : matched;
    }

    /**
     * Cree un tiers generique nomme d'apres le texte brut, et l'ajoute a
     * `knownPayees` pour que les lignes suivantes du meme lot le retrouvent
     * sans requete supplementaire.
     */
    Tiers createFromText(String rawText, List<Tiers> knownPayees) {
        String trimmed = rawText.trim();
        Tiers created = new Tiers();
        // Texte issu d'un releve importe : tronque plutot que refuse.
        created.setName(LongueurTexte.tronquer(trimmed));
        created.setPattern(defaultPattern(trimmed));
        // Toujours generique a la creation automatique (voir javadoc ci-dessus) :
        // le montant n'est jamais deduit de la transaction importee.
        created.setAmount(null);
        Tiers saved = payeeRepository.save(created);
        knownPayees.add(saved);
        return saved;
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
     * Comme createFromText, mais pour une ligne de releve importe : nom et
     * motif nettoyes des parties variables du libelle (voir suggestFromBankLabel).
     * Reserve a l'import : un tiers saisi a la main garde le texte tape.
     */
    Tiers createFromBankLabel(String rawText, List<Tiers> knownPayees) {
        TiersSuggere suggested = suggestFromBankLabel(rawText);
        Tiers created = new Tiers();
        created.setName(LongueurTexte.tronquer(suggested.name()));
        created.setPattern(suggested.pattern());
        created.setAmount(null);
        Tiers saved = payeeRepository.save(created);
        knownPayees.add(saved);
        return saved;
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

    /** Charge tous les tiers, pour etre reutilise a travers tout un lot (import, migration...). */
    List<Tiers> findAllForMatching() {
        return payeeRepository.findAll();
    }

    /**
     * A appeler apres la modification (ou suppression) d'une transaction
     * dont le tiers a change : si l'ANCIEN tiers est devenu orphelin (plus
     * aucune transaction ne pointe vers lui), il est supprime. Evite
     * d'accumuler des tiers "fantomes" a chaque fois qu'on modifie le tiers
     * d'une transaction a la main.
     *
     * Par securite, ne supprime que les tiers encore dans leur etat
     * "auto-cree" (voir isAutoCreatedState) : un tiers renomme, dote d'une
     * categorie ou d'un montant, ou dont le motif a ete modifie a la main
     * (page Tiers, fusion...) n'est jamais supprime automatiquement.
     *
     * Toujours dans sa propre transaction (REQUIRES_NEW), et appele APRES le
     * commit de la modification (voir ServiceTransaction#prunePayeesAfterCommit) :
     * un echec de ce nettoyage annexe ne peut ainsi jamais annuler la
     * modification ou la suppression de transaction qui l'a declenche.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteIfOrphan(Long payeeId) {
        payeeRepository.findById(payeeId).ifPresent(this::pruneIfOrphan);
    }

    /**
     * Meme nettoyage que deleteIfOrphan pour plusieurs tiers, en une seule
     * transaction et quelques requetes : supprimer ou annuler un gros import
     * touche des centaines de tiers. Tout ou rien : en cas d'echec, l'appelant
     * reprend tiers par tiers (voir ServiceTransaction#prunePayees).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteOrphans(Collection<Long> payeeIds) {
        List<Long> candidates = payeeRepository.findAllById(payeeIds).stream()
                .filter(ServiceTiers::isAutoCreatedState)
                .map(Tiers::getId)
                .toList();
        if (candidates.isEmpty()) return;

        Set<Long> inUse = new HashSet<>(transactionRepository.findPayeeIdsInUse(candidates));
        List<Long> orphans = candidates.stream().filter(id -> !inUse.contains(id)).toList();
        if (!orphans.isEmpty()) {
            payeeRepository.deleteAllByIdInBatch(orphans);
        }
    }

    private void pruneIfOrphan(Tiers payee) {
        if (payee == null || payee.getId() == null) return;
        // Tiers personnalise (renomme, motif modifie, fusion...) : on ne
        // touche jamais a un tiers dans lequel l'utilisateur a investi du travail.
        if (!isAutoCreatedState(payee)) return;

        // La relation Transaction#payee est une vraie cle etrangere : une
        // simple verification d'existence suffit, plus besoin de rescanner
        // les transactions par texte/montant.
        if (!transactionRepository.existsByPayee(payee)) {
            payeeRepository.delete(payee);
        }
    }

    /**
     * Fusionne plusieurs tiers (sourceIds) dans un tiers cible (targetId) :
     * - toutes les transactions liees a une source sont reaffectees au tiers
     *   cible (vraie cle etrangere, voir Transaction#payee) ;
     * - le motif de la cible n'est PAS modifie automatiquement (les motifs
     *   des tiers source ne sont plus concatenes) : l'utilisateur choisit
     *   lui-meme le motif final via le parametre pattern, optionnel ;
     * - la categorie de la cible est completee avec celle d'une source si
     *   la cible n'en avait pas encore ;
     * - les tiers source sont supprimes.
     * Operation atomique : tout est annule en cas d'erreur.
     */
    @Transactional
    public TiersDTO merge(List<Long> sourceIds, Long targetId, String pattern) {
        if (targetId == null) {
            throw new IllegalArgumentException("Le tiers à conserver est obligatoire.");
        }
        if (sourceIds == null) {
            throw new IllegalArgumentException("La liste des tiers à fusionner est obligatoire.");
        }
        // Controle avant toute reaffectation : un refus ne laisse rien a moitie fait.
        String mergedPattern = pattern != null && !pattern.isBlank() ? validatedPattern(pattern) : null;
        Tiers target = findEntityById(targetId);

        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId.equals(targetId)) continue;
            Tiers source = findEntityById(sourceId);

            transactionRepository.reassignPayee(source, target);

            if (target.getCategory() == null && source.getCategory() != null) {
                target.setCategory(source.getCategory());
            }

            payeeRepository.delete(source);
            log.info("Tiers {} ({}) fusionne dans {} ({})", source.getName(), sourceId, target.getName(), targetId);
        }

        if (mergedPattern != null) {
            target.setPattern(mergedPattern);
        }

        return payeeMapper.toDTO(payeeRepository.save(target));
    }

    /**
     * Categorisation automatique en masse : pour chaque tiers sans categorie,
     * si TOUTES ses transactions deja categorisees s'accordent sur UNE seule
     * categorie (les transactions encore sans categorie n'entrent pas en
     * compte, ni pour ni contre — voir suggestedCategoryId cote frontend,
     * meme logique), cette categorie est affectee au tiers. Dans la foulee,
     * la categorie ainsi determinee pour le tiers (deduite a l'instant, ou
     * deja presente auparavant) est propagee a TOUTES les transactions de ce
     * tiers encore sans categorie : ne modifie jamais une categorie deja
     * choisie, ni sur un tiers ni sur une transaction — ne fait que combler
     * les vides a partir d'un signal deja consistant.
     */
    @Transactional
    public CategorisationAutoResultatDTO autoCategorize() {
        List<Tiers> allPayees = payeeRepository.findAll();
        List<Transaction> allTransactions = transactionRepository.findAllWithPayeeAndCategory();
        Map<Long, List<Transaction>> transactionsByPayeeId = groupTransactionsByPayeeId(allTransactions);

        List<Tiers> payeesToSave = new ArrayList<>();
        List<Transaction> transactionsToSave = new ArrayList<>();

        for (Tiers payee : allPayees) {
            List<Transaction> transactions = transactionsByPayeeId.getOrDefault(payee.getId(), List.of());
            Categorie targetCategory = resolveTargetCategory(payee, transactions, payeesToSave);
            if (targetCategory == null) continue;
            applyCategoryToUncategorized(transactions, targetCategory, transactionsToSave);
        }

        payeeRepository.saveAll(payeesToSave);
        transactionRepository.saveAll(transactionsToSave);

        log.info("Categorisation automatique : {} tiers categorise(s), {} transaction(s) mise(s) a jour",
                payeesToSave.size(), transactionsToSave.size());

        return new CategorisationAutoResultatDTO(payeesToSave.size(), transactionsToSave.size(),
                payeesToSave.stream().map(Tiers::getId).toList(),
                transactionsToSave.stream().map(Transaction::getId).toList());
    }

    /** Regroupe les transactions par id de tiers ; les transactions sans tiers sont ignorees. */
    private static Map<Long, List<Transaction>> groupTransactionsByPayeeId(List<Transaction> allTransactions) {
        Map<Long, List<Transaction>> result = new HashMap<>();
        for (Transaction t : allTransactions) {
            if (t.getPayee() == null) continue;
            result.computeIfAbsent(t.getPayee().getId(), id -> new ArrayList<>()).add(t);
        }
        return result;
    }

    /**
     * Categorie deja affectee au tiers, ou deduite si toutes ses transactions
     * deja categorisees s'accordent sur une seule (voir javadoc de
     * autoCategorize) — si deduite, le tiers est ajoute a payeesToSave. Null
     * si le tiers n'a pas de categorie et qu'aucun signal consistant n'existe,
     * ou si la seule categorie trouvee est masquee.
     */
    private static Categorie resolveTargetCategory(Tiers payee, List<Transaction> transactions, List<Tiers> payeesToSave) {
        if (payee.getCategory() != null) return payee.getCategory();

        Set<Categorie> distinctCategories = new HashSet<>();
        for (Transaction t : transactions) {
            if (t.getCategory() != null) distinctCategories.add(t.getCategory());
        }
        if (distinctCategories.size() != 1) return null;

        Categorie deduced = distinctCategories.iterator().next();
        // Categorie masquee = plus utilisee (ex : abonnement resilie) : la
        // proposer pour les nouvelles transactions contredirait la saisie, qui
        // ne la propose plus (CategoryAutocomplete).
        if (deduced.isHidden()) return null;
        payee.setCategory(deduced);
        payeesToSave.add(payee);
        return deduced;
    }

    /** Affecte targetCategory a chaque transaction encore sans categorie, en l'ajoutant a transactionsToSave. */
    private static void applyCategoryToUncategorized(List<Transaction> transactions, Categorie targetCategory, List<Transaction> transactionsToSave) {
        for (Transaction t : transactions) {
            if (t.getCategory() == null) {
                t.setCategory(targetCategory);
                transactionsToSave.add(t);
            }
        }
    }
}

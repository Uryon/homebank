package com.homebank.service;

import com.homebank.dto.CategorisationAutoResultatDTO;
import com.homebank.dto.TiersDTO;
import com.homebank.mapper.MappeurTiers;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
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

    public List<TiersDTO> findAll() {
        return payeeRepository.findAll()
                .stream()
                .map(payeeMapper::toDTO)
                .toList();
    }

    private Tiers findEntityById(Long id) {
        return payeeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tiers introuvable : " + id));
    }

    public TiersDTO create(TiersDTO payload) {
        Tiers p = new Tiers();
        applyPayload(p, payload);
        return payeeMapper.toDTO(payeeRepository.save(p));
    }

    /**
     * Renomme un tiers : comme les transactions liees pointent vers ce tiers
     * par une vraie relation (Transaction#payee), et non plus par une copie
     * de son nom, elles affichent deja le nouveau nom sans etape a part.
     */
    public TiersDTO update(Long id, TiersDTO payload) {
        Tiers existing = findEntityById(id);
        applyPayload(existing, payload);
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
            pattern = normalizePatternSegments(pattern);
        }
        p.setPattern(pattern);

        p.setAmount(payload.getAmount() != null ? payload.getAmount().abs() : null);
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

    /** Motif par defaut a partir du nom d'un tiers : le nom lui-meme, tel quel (sans etoile). */
    private static String defaultPattern(String name) {
        return name != null ? name.trim() : "";
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
    private static String normalizeWhitespace(String s) {
        return s == null ? null : WHITESPACE_RUN.matcher(s).replaceAll(" ").trim();
    }

    /** Teste si le champ "pattern" (un ou plusieurs motifs separes par ";") correspond au texte donne. */
    private static boolean matchesPatternField(String patternField, String text) {
        if (patternField == null || patternField.isBlank() || text == null || text.isBlank()) return false;
        String normalizedText = normalizeWhitespace(text);
        for (String rawPart : patternField.split(";")) {
            String part = normalizeWhitespace(rawPart);
            if (part == null || part.isEmpty()) continue;
            try {
                // Le champ stocke/affiche est du texte simple, sans etoile
                // ("Carrefour"). C'est ICI, au moment du traitement seulement,
                // qu'on l'entoure d'etoiles ("*Carrefour*") pour qu'il soit
                // reconnu partout ou il apparait dans le libelle. Si l'utilisateur
                // a lui-meme mis une etoile (motif avance, ex : "Carrefour*Paris"),
                // on ne touche a rien de plus : il sait ce qu'il fait.
                String wildcarded = part.indexOf('*') < 0 ? "*" + part + "*" : part;
                Pattern compiled = Pattern.compile(wildcardToRegex(wildcarded), Pattern.CASE_INSENSITIVE);
                if (compiled.matcher(normalizedText).find()) return true;
            } catch (PatternSyntaxException e) {
                // motif invalide (ne devrait plus arriver avec le systeme "joker") : ignore ce
                // segment mais trace l'anomalie plutot que de la masquer silencieusement
                log.warn("Motif de tiers invalide ignore : \"{}\" ({})", rawPart, e.getMessage());
            }
        }
        return false;
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

        for (Tiers payee : candidates) {
            if (!matchesPatternField(payee.getPattern(), text)) continue;

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
        if (rawText == null || rawText.isBlank()) return null;
        return findExisting(rawText, amount, knownPayees)
                .orElseGet(() -> createFromText(rawText, knownPayees));
    }

    /**
     * Tiers deja connu correspondant au texte brut (par motif, sinon par nom
     * exact), SANS jamais en creer : permet a l'import de verifier qu'une
     * ligne n'est pas un doublon avant de creer un tiers pour elle (voir
     * ServiceImport). Vide si le texte est vide ou ne correspond a aucun tiers.
     */
    Optional<Tiers> findExisting(String rawText, BigDecimal amount, List<Tiers> knownPayees) {
        if (rawText == null || rawText.isBlank()) return Optional.empty();
        String trimmed = rawText.trim();
        BigDecimal normalizedAmount = amount != null ? amount.abs() : null;

        Optional<Tiers> matched = matchPayee(trimmed, normalizedAmount, knownPayees);
        if (matched.isPresent()) return matched;

        // Filet de securite : evite un doublon si un tiers de meme nom existe
        // deja mais que son motif ne matchait pas ce texte precis (ex :
        // l'utilisateur retape a la main le nom exact d'un tiers existant
        // dans le champ "Tiers" d'une transaction). Un tiers generique
        // (amount == null) matche quel que soit le montant, comme partout
        // ailleurs dans ce fichier (voir matchPayee) : sans ce cas, retaper
        // le nom d'un tiers generique sur une transaction d'un montant
        // different creait un second tiers generique portant le meme nom.
        return knownPayees.stream()
                .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(trimmed))
                .filter(p -> p.getAmount() == null || sameAmount(p.getAmount(), normalizedAmount))
                .findFirst();
    }

    /**
     * Cree un tiers generique nomme d'apres le texte brut, et l'ajoute a
     * `knownPayees` pour que les lignes suivantes du meme lot le retrouvent
     * sans requete supplementaire.
     */
    Tiers createFromText(String rawText, List<Tiers> knownPayees) {
        String trimmed = rawText.trim();
        Tiers created = new Tiers();
        created.setName(trimmed);
        created.setPattern(defaultPattern(trimmed));
        // Toujours generique a la creation automatique (voir javadoc ci-dessus) :
        // le montant n'est jamais deduit de la transaction importee.
        created.setAmount(null);
        Tiers saved = payeeRepository.save(created);
        knownPayees.add(saved);
        return saved;
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
     * "auto-cree" (motif = nom exact, jamais personnalise depuis) :
     * un tiers renomme ou dont le motif a ete modifie a la main par l'utilisateur
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

    private void pruneIfOrphan(Tiers payee) {
        if (payee == null || payee.getId() == null) return;
        // Tiers personnalise (renomme, motif modifie, fusion...) : on ne
        // touche jamais a un tiers dans lequel l'utilisateur a investi du travail.
        if (!payee.getPattern().equals(defaultPattern(payee.getName()))) return;

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
            throw new IllegalArgumentException("Le tiers a conserver est obligatoire");
        }
        if (sourceIds == null) {
            throw new IllegalArgumentException("La liste des elements a fusionner est obligatoire");
        }
        Tiers target = findEntityById(targetId);

        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId.equals(targetId)) continue;
            Tiers source = findEntityById(sourceId);

            transactionRepository.reassignPayee(source, target);

            if (target.getCategory() == null && source.getCategory() != null) {
                target.setCategory(source.getCategory());
            }

            payeeRepository.delete(source);
        }

        if (pattern != null && !pattern.isBlank()) {
            target.setPattern(pattern.trim());
        }

        return payeeMapper.toDTO(payeeRepository.save(target));
    }

    /**
     * Migration unique : relie chaque transaction pas encore rattachee a un
     * tiers (payee_id) au Tiers correspondant a son ANCIEN texte libre
     * (colonne "payee" desormais orpheline, lue en SQL natif — voir
     * DepotTransaction#findLegacyPayeeTextForMigration), en reutilisant
     * exactement la meme reconnaissance par motif qu'a l'import (matchPayee),
     * pour que le passage a la relation ne perde aucune information et
     * "repare" au passage les tiers restes orphelins (texte ne correspondant
     * qu'a une fusion/suppression passee mal repercutee).
     * Appelee au demarrage par config.PayeeRelationMigration ; idempotente
     * (ne touche que les transactions dont payee_id est encore NULL).
     */
    @Transactional
    public int migrateLegacyTransactionPayees() {
        if (transactionRepository.countLegacyPayeeColumn() == 0) return 0;

        List<Object[]> legacyRows = transactionRepository.findLegacyPayeeTextForMigration();
        if (legacyRows.isEmpty()) return 0;

        List<Tiers> knownPayees = findAllForMatching();

        // Un seul findAllById groupé plutôt qu'un findById par ligne dans la
        // boucle : evite un aller-retour SQL par transaction a migrer.
        List<Long> transactionIds = legacyRows.stream().map(row -> ((Number) row[0]).longValue()).toList();
        Map<Long, Transaction> transactionsById = transactionRepository.findAllById(transactionIds).stream()
                .collect(Collectors.toMap(Transaction::getId, t -> t));

        List<Transaction> transactionsToSave = new ArrayList<>();
        int migrated = 0;
        for (Object[] row : legacyRows) {
            Long transactionId = ((Number) row[0]).longValue();
            String rawText = (String) row[1];

            Transaction t = transactionsById.get(transactionId);
            if (t != null) {
                Tiers resolved = resolveOrCreate(rawText, t.getAmount(), knownPayees);
                if (resolved != null) {
                    t.setPayee(resolved);
                    transactionsToSave.add(t);
                }
            }
            migrated++;
        }
        transactionRepository.saveAll(transactionsToSave);
        return migrated;
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

        return new CategorisationAutoResultatDTO(payeesToSave.size(), transactionsToSave.size());
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
     * si le tiers n'a pas de categorie et qu'aucun signal consistant n'existe.
     */
    private static Categorie resolveTargetCategory(Tiers payee, List<Transaction> transactions, List<Tiers> payeesToSave) {
        if (payee.getCategory() != null) return payee.getCategory();

        Set<Categorie> distinctCategories = new HashSet<>();
        for (Transaction t : transactions) {
            if (t.getCategory() != null) distinctCategories.add(t.getCategory());
        }
        if (distinctCategories.size() != 1) return null;

        Categorie deduced = distinctCategories.iterator().next();
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

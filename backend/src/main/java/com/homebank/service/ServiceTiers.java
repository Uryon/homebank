package com.homebank.service;

import com.homebank.dto.TiersDTO;
import com.homebank.mapper.MappeurTiers;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class ServiceTiers {

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

    public void delete(Long id) {
        payeeRepository.deleteById(id);
    }

    private void applyPayload(Tiers p, TiersDTO payload) {
        p.setName(payload.getName());

        // Le champ "pattern" est du texte simple, stocke et affiche TEL QUEL
        // (sans etoile) : "Carrefour" suffit. Les etoiles ne sont ajoutees
        // qu'au moment du traitement (voir matchesPatternField), jamais dans
        // les donnees stockees ni dans l'interface.
        String pattern = payload.getPattern();
        if (pattern == null || pattern.isBlank()) {
            pattern = (p.getPattern() != null && !p.getPattern().isBlank())
                    ? p.getPattern()
                    : defaultPattern(payload.getName());
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

    /** Teste si le champ "pattern" (un ou plusieurs motifs separes par ";") correspond au texte donne. */
    private static boolean matchesPatternField(String patternField, String text) {
        if (patternField == null || patternField.isBlank() || text == null || text.isBlank()) return false;
        for (String rawPart : patternField.split(";")) {
            String part = rawPart.trim();
            if (part.isEmpty()) continue;
            try {
                // Le champ stocke/affiche est du texte simple, sans etoile
                // ("Carrefour"). C'est ICI, au moment du traitement seulement,
                // qu'on l'entoure d'etoiles ("*Carrefour*") pour qu'il soit
                // reconnu partout ou il apparait dans le libelle. Si l'utilisateur
                // a lui-meme mis une etoile (motif avance, ex : "Carrefour*Paris"),
                // on ne touche a rien de plus : il sait ce qu'il fait.
                String wildcarded = part.indexOf('*') < 0 ? "*" + part + "*" : part;
                Pattern compiled = Pattern.compile(wildcardToRegex(wildcarded), Pattern.CASE_INSENSITIVE);
                if (compiled.matcher(text).find()) return true;
            } catch (PatternSyntaxException e) {
                // motif invalide (ne devrait plus arriver avec le systeme "joker") : on l'ignore
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
        String trimmed = rawText.trim();
        BigDecimal normalizedAmount = amount != null ? amount.abs() : null;

        Optional<Tiers> matched = matchPayee(trimmed, normalizedAmount, knownPayees);
        if (matched.isPresent()) return matched.get();

        // Filet de securite : evite un doublon si un tiers de meme nom ET meme
        // montant existe deja mais que son motif ne matchait pas ce texte precis.
        Optional<Tiers> byNameAndAmount = knownPayees.stream()
                .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(trimmed))
                .filter(p -> sameAmount(p.getAmount(), normalizedAmount))
                .findFirst();
        if (byNameAndAmount.isPresent()) return byNameAndAmount.get();

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
     * A appeler apres la modification (ou suppression) d'une transaction :
     * si le tiers lie a change, verifie si l'ANCIEN tiers devient orphelin
     * (plus aucune transaction ne pointe vers lui) et le supprime le cas
     * echeant. Evite d'accumuler des tiers "fantomes" a chaque fois qu'on
     * modifie le tiers d'une transaction a la main.
     *
     * Par securite, ne supprime que les tiers encore dans leur etat
     * "auto-cree" (motif = nom exact, jamais personnalise depuis) :
     * un tiers renomme ou dont le motif a ete modifie a la main par l'utilisateur
     * (page Tiers, fusion...) n'est jamais supprime automatiquement.
     */
    @org.springframework.transaction.annotation.Transactional
    public void cleanupIfPayeeChanged(Tiers previousPayee, Tiers newPayee) {
        if (previousPayee == null || previousPayee.equals(newPayee)) return;
        pruneIfOrphan(previousPayee);
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
    @org.springframework.transaction.annotation.Transactional
    public TiersDTO merge(List<Long> sourceIds, Long targetId, String pattern) {
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
    @org.springframework.transaction.annotation.Transactional
    public int migrateLegacyTransactionPayees() {
        if (transactionRepository.countLegacyPayeeColumn() == 0) return 0;

        List<Object[]> legacyRows = transactionRepository.findLegacyPayeeTextForMigration();
        if (legacyRows.isEmpty()) return 0;

        List<Tiers> knownPayees = findAllForMatching();
        int migrated = 0;
        for (Object[] row : legacyRows) {
            Long transactionId = ((Number) row[0]).longValue();
            String rawText = (String) row[1];

            transactionRepository.findById(transactionId).ifPresent(t -> {
                Tiers resolved = resolveOrCreate(rawText, t.getAmount(), knownPayees);
                if (resolved != null) {
                    t.setPayee(resolved);
                    transactionRepository.save(t);
                }
            });
            migrated++;
        }
        return migrated;
    }
}

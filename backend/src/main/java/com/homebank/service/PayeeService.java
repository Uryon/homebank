package com.homebank.service;

import com.homebank.dto.PayeeDTO;
import com.homebank.mapper.PayeeMapper;
import com.homebank.model.Category;
import com.homebank.model.Payee;
import com.homebank.repository.PayeeRepository;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class PayeeService {

    private final PayeeRepository payeeRepository;
    private final CategoryService categoryService;
    private final PayeeMapper payeeMapper;
    private final TransactionRepository transactionRepository;

    public PayeeService(PayeeRepository payeeRepository, CategoryService categoryService,
                         PayeeMapper payeeMapper, TransactionRepository transactionRepository) {
        this.payeeRepository = payeeRepository;
        this.categoryService = categoryService;
        this.payeeMapper = payeeMapper;
        this.transactionRepository = transactionRepository;
    }

    public List<PayeeDTO> findAll() {
        return payeeRepository.findAll()
                .stream()
                .map(payeeMapper::toDTO)
                .toList();
    }

    public PayeeDTO findById(Long id) {
        return payeeMapper.toDTO(findEntityById(id));
    }

    private Payee findEntityById(Long id) {
        return payeeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tiers introuvable : " + id));
    }

    public PayeeDTO create(PayeeDTO payload) {
        Payee p = new Payee();
        applyPayload(p, payload);
        return payeeMapper.toDTO(payeeRepository.save(p));
    }

    public PayeeDTO update(Long id, PayeeDTO payload) {
        Payee existing = findEntityById(id);
        applyPayload(existing, payload);
        return payeeMapper.toDTO(payeeRepository.save(existing));
    }

    public void delete(Long id) {
        payeeRepository.deleteById(id);
    }

    private void applyPayload(Payee p, PayeeDTO payload) {
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
            Category category = categoryService.findById(payload.getCategoryId());
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
    public Optional<Payee> matchPayee(String text, BigDecimal amount) {
        if (text == null || text.isBlank()) return Optional.empty();
        Payee genericMatch = null;

        for (Payee payee : payeeRepository.findAll()) {
            if (!matchesPatternField(payee.getPattern(), text)) continue;

            if (payee.getAmount() == null) {
                if (genericMatch == null) genericMatch = payee;
            } else if (amount != null && payee.getAmount().compareTo(amount) == 0) {
                return Optional.of(payee); // correspondance exacte nom + montant : priorite absolue
            }
        }
        return Optional.ofNullable(genericMatch);
    }

    /**
     * Reconnait un tiers a partir d'un texte brut importe et d'un montant :
     * - si un tiers existant correspond (texte + montant, ou texte + tiers
     *   generique sans montant), on le renvoie tel quel (son nom peut avoir
     *   ete renomme manuellement par l'utilisateur : c'est ce nom "canonique"
     *   qui sera utilise sur la transaction) ;
     * - sinon, un nouveau tiers est cree automatiquement avec pour nom ET pour
     *   motif le texte brut rencontre (tel quel, sans etoile ajoutee : voir
     *   matchesPatternField pour le traitement). Si ce meme texte brut revient plus
     *   tard avec un montant different, un AUTRE tiers distinct sera cree,
     *   meme nom au depart mais que l'utilisateur pourra renommer independamment
     *   (ex : "Bouygues Mel" pour 10E, "Bouygues Val" pour 20E).
     */
    public Payee resolveOrCreate(String rawText, BigDecimal amount) {
        if (rawText == null || rawText.isBlank()) return null;
        String trimmed = rawText.trim();
        BigDecimal normalizedAmount = amount != null ? amount.abs() : null;

        Optional<Payee> matched = matchPayee(trimmed, normalizedAmount);
        if (matched.isPresent()) return matched.get();

        // Filet de securite : evite un doublon si un tiers de meme nom ET meme
        // montant existe deja mais que son motif ne matchait pas ce texte precis.
        Optional<Payee> byNameAndAmount = payeeRepository.findByNameIgnoreCaseAndAmount(trimmed, normalizedAmount);
        if (byNameAndAmount.isPresent()) return byNameAndAmount.get();

        Payee created = new Payee();
        created.setName(trimmed);
        created.setPattern(defaultPattern(trimmed));
        created.setAmount(normalizedAmount);
        return payeeRepository.save(created);
    }

    /**
     * Parcourt toutes les paires (tiers, montant) deja presentes dans les
     * transactions existantes et cree automatiquement un tiers (Payee) pour
     * chacune de celles qui ne sont pas encore connues. Utile pour "amorcer"
     * la liste des tiers apres avoir deja importe des transactions avant
     * l'ajout de cette fonctionnalite. Renvoie un resume (nombre crees / deja connus).
     */
    public Map<String, Integer> initializeFromExistingTransactions() {
        int created = 0;
        int alreadyKnown = 0;

        for (Object[] row : transactionRepository.findAllPayeeAmountPairs()) {
            String rawText = (String) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (rawText == null || rawText.isBlank()) continue;

            String trimmed = rawText.trim();
            BigDecimal normalizedAmount = amount != null ? amount.abs() : null;

            boolean existedBefore = matchPayee(trimmed, normalizedAmount).isPresent()
                    || payeeRepository.findByNameIgnoreCaseAndAmount(trimmed, normalizedAmount).isPresent();

            resolveOrCreate(trimmed, normalizedAmount);

            if (existedBefore) {
                alreadyKnown++;
            } else {
                created++;
            }
        }

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("created", created);
        summary.put("alreadyKnown", alreadyKnown);
        return summary;
    }

    /**
     * A appeler apres la modification (ou suppression) d'une transaction :
     * si le tiers ou le montant a change, verifie si l'ANCIEN tiers devient
     * orphelin (plus aucune transaction ne le matche) et le supprime le cas
     * echeant. Evite d'accumuler des tiers "fantomes" a chaque fois qu'on
     * modifie le tiers d'une transaction a la main.
     *
     * Par securite, ne supprime que les tiers encore dans leur etat
     * "auto-cree" (motif = nom exact, jamais personnalise depuis) :
     * un tiers renomme ou dont le motif a ete modifie a la main par l'utilisateur
     * (page Tiers, fusion...) n'est jamais supprime automatiquement.
     */
    @org.springframework.transaction.annotation.Transactional
    public void cleanupIfPayeeChanged(String previousPayeeText, BigDecimal previousAmount,
                                       String newPayeeText, BigDecimal newAmount) {
        if (previousPayeeText == null || previousPayeeText.isBlank()) return;

        BigDecimal prevAbs = previousAmount != null ? previousAmount.abs() : null;
        BigDecimal newAbs = newAmount != null ? newAmount.abs() : null;
        boolean unchanged = previousPayeeText.equalsIgnoreCase(newPayeeText)
                && ((prevAbs == null && newAbs == null) || (prevAbs != null && newAbs != null && prevAbs.compareTo(newAbs) == 0));
        if (unchanged) return;

        matchPayee(previousPayeeText, prevAbs).ifPresent(this::pruneIfOrphan);
    }

    private void pruneIfOrphan(Payee payee) {
        if (payee == null || payee.getId() == null) return;
        // Tiers personnalise (renomme, motif modifie, fusion...) : on ne
        // touche jamais a un tiers dans lequel l'utilisateur a investi du travail.
        if (!payee.getPattern().equals(defaultPattern(payee.getName()))) return;

        boolean stillUsed = transactionRepository.findAllPayeeAmountPairs().stream()
                .anyMatch(row -> matches(payee, (String) row[0], (BigDecimal) row[1]));

        if (!stillUsed) {
            payeeRepository.delete(payee);
        }
    }

    private boolean matches(Payee payee, String text, BigDecimal amount) {
        if (!matchesPatternField(payee.getPattern(), text != null ? text.trim() : null)) return false;
        if (payee.getAmount() == null) return true;
        return amount != null && payee.getAmount().compareTo(amount.abs()) == 0;
    }

    /**
     * Fusionne plusieurs tiers (sourceIds) dans un tiers cible (targetId) :
     * - toutes les transactions dont le tiers correspondait a une source
     *   (texte exact, insensible a la casse) sont renommees vers le nom
     *   du tiers cible ;
     * - les motifs des tiers source sont combines a celui de la cible
     *   (separes par ";") pour que les futurs imports reconnaissant l'un ou
     *   l'autre texte continuent d'aboutir au tiers fusionne ;
     * - la categorie de la cible est completee avec celle d'une source si
     *   la cible n'en avait pas encore ;
     * - les tiers source sont supprimes.
     * Operation atomique : tout est annule en cas d'erreur.
     */
    @org.springframework.transaction.annotation.Transactional
    public PayeeDTO merge(List<Long> sourceIds, Long targetId) {
        Payee target = findEntityById(targetId);

        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId.equals(targetId)) continue;
            Payee source = findEntityById(sourceId);

            transactionRepository.renamePayeeText(source.getName(), target.getName());

            target.setPattern(target.getPattern() + ";" + source.getPattern());

            if (target.getCategory() == null && source.getCategory() != null) {
                target.setCategory(source.getCategory());
            }

            payeeRepository.delete(source);
        }

        return payeeMapper.toDTO(payeeRepository.save(target));
    }
}

package com.homebank.service;

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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

@Service
public class ServiceTiers {

    private static final Logger log = LoggerFactory.getLogger(ServiceTiers.class);

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
                    : MotifsTiers.defaultPattern(name);
        } else {
            pattern = MotifsTiers.validatedPattern(pattern);
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
    /**
     * Cherche, parmi tous les tiers connus, celui qui correspond le mieux au
     * texte et au montant donnes (ex : le champ "payee" et le montant d'une
     * transaction importee).
     * <p>
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
        MotifsTiers.LibelleCompare label = MotifsTiers.LibelleCompare.de(text);

        for (Tiers payee : candidates) {
            if (!MotifsTiers.motifPrepare(payee.getPattern()).reconnait(label)) continue;

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
        created.setPattern(MotifsTiers.defaultPattern(trimmed));
        // Toujours generique a la creation automatique (voir javadoc ci-dessus) :
        // le montant n'est jamais deduit de la transaction importee.
        created.setAmount(null);
        Tiers saved = payeeRepository.save(created);
        knownPayees.add(saved);
        return saved;
    }

    /**
     * Tiers generique (sans montant) portant exactement ce nom, casse ignoree,
     * sinon cree sous ce nom. Sans passer par les motifs : sert a remettre une
     * transaction restauree sous son tiers d'origine, supprime entre-temps
     * parce que devenu orphelin (voir ServiceRestaurationTransaction). Un tiers
     * ainsi nettoye etait toujours generique, de motif egal a son nom (voir
     * MotifsTiers#isAutoCreatedState) : le recreer sous ce nom le retrouve a
     * l'identique, la ou les motifs pouvaient designer un autre tiers dont le
     * motif contient ce nom (« Rose » pour « Fleuriste Rose »).
     */
    Tiers findOrCreateByExactName(String name, List<Tiers> knownPayees) {
        String trimmed = name.trim();
        return knownPayees.stream()
                .filter(p -> p.getAmount() == null && p.getName() != null && p.getName().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElseGet(() -> createFromText(trimmed, knownPayees));
    }

    /**
     * Comme createFromText, mais pour une ligne de releve importe : nom et
     * motif nettoyes des parties variables du libelle (voir suggestFromBankLabel).
     * Reserve a l'import : un tiers saisi a la main garde le texte tape.
     */
    Tiers createFromBankLabel(String rawText, List<Tiers> knownPayees) {
        MotifsTiers.TiersSuggere suggested = MotifsTiers.suggestFromBankLabel(rawText);
        Tiers created = new Tiers();
        created.setName(LongueurTexte.tronquer(suggested.name()));
        created.setPattern(suggested.pattern());
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
     * <p>
     * Par securite, ne supprime que les tiers encore dans leur etat
     * "auto-cree" (voir isAutoCreatedState) : un tiers renomme, dote d'une
     * categorie ou d'un montant, ou dont le motif a ete modifie a la main
     * (page Tiers, fusion...) n'est jamais supprime automatiquement.
     * <p>
     * Toujours dans sa propre transaction (REQUIRES_NEW), et appele APRES le
     * commit de la modification (voir ElagageTiersOrphelins#apresCommit) :
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
                .filter(MotifsTiers::isAutoCreatedState)
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
        if (!MotifsTiers.isAutoCreatedState(payee)) return;

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
        String mergedPattern = pattern != null && !pattern.isBlank() ? MotifsTiers.validatedPattern(pattern) : null;
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


}

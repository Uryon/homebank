package com.homebank.service;

import com.homebank.dto.CategorieDTO;
import com.homebank.model.Categorie;
import com.homebank.model.Categorie.TypeCategorie;
import com.homebank.repository.DepotCategorie;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.LongueurTexte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ServiceCategorie {

    private static final Logger log = LoggerFactory.getLogger(ServiceCategorie.class);

    private final DepotCategorie categoryRepository;
    private final DepotTransaction transactionRepository;
    private final DepotTiers payeeRepository;

    public ServiceCategorie(DepotCategorie categoryRepository,
                             DepotTransaction transactionRepository,
                             DepotTiers payeeRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.payeeRepository = payeeRepository;
    }

    public List<Categorie> findAll() {
        return categoryRepository.findAll();
    }

    public Categorie findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Catégorie introuvable : " + id));
    }

    public Categorie create(CategorieDTO payload) {
        Categorie category = new Categorie();
        category.setName(requireName(payload));
        category.setIcon(requireIcon(payload));
        if (payload.getParentId() != null) {
            Categorie parent = findById(payload.getParentId());
            ensureCanContainChildren(parent);
            category.setParent(parent);
            // Une sous-categorie appartient toujours au meme groupe (revenu/depense/les deux)
            // que sa categorie parente : son propre type n'est pas selectionnable independamment.
            category.setType(parent.getType());
        } else {
            category.setParent(null);
            category.setType(requireRootType(payload));
        }
        ensureUniqueAmongSiblings(category.getName(), category.getParent(), null);
        Categorie saved = categoryRepository.save(category);
        log.info("Categorie creee : {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public Categorie update(Long id, CategorieDTO payload) {
        Categorie existing = findById(id);
        String name = requireName(payload);
        String icon = requireIcon(payload);
        Categorie parent = null;
        TypeCategorie type;

        if (payload.getParentId() != null) {
            if (payload.getParentId().equals(id)) {
                throw new IllegalArgumentException("Une catégorie ne peut pas être sa propre catégorie parente.");
            }
            parent = findById(payload.getParentId());
            if (isSelfOrDescendant(parent, existing)) {
                throw new IllegalArgumentException("Une catégorie ne peut pas être déplacée sous l'une de ses propres sous-catégories.");
            }
            ensureCanMoveUnder(existing, parent);
            // Sous-categorie : le type suit toujours celui du parent, non modifiable independamment.
            type = parent.getType();
        } else {
            type = requireRootType(payload);
        }
        // Avant toute modification : la recherche enverrait sinon le nouveau nom
        // en base, et trouverait deux categories au lieu d'un homonyme.
        ensureUniqueAmongSiblings(name, parent, id);
        existing.setName(name);
        existing.setIcon(icon);
        existing.setParent(parent);
        existing.setType(type);
        // Repercute le type sur toute la descendance (enfants, petits-enfants...)
        // pour qu'elle reste dans le meme groupe (revenu/depense), que le type
        // ait change directement ou suite a un reparentage.
        cascadeTypeToDescendants(existing);

        log.info("Categorie modifiee : {} ({})", existing.getName(), id);
        return categoryRepository.save(existing);
    }

    /**
     * L'application gere deux niveaux : au-dela, une categorie n'apparaissait
     * plus dans les listes de choix et les rapports la rattachaient mal.
     */
    private static void ensureCanContainChildren(Categorie parent) {
        if (parent.getParent() != null) {
            throw new IllegalArgumentException("La sous-catégorie « " + parent.getName()
                    + " » ne peut pas contenir d'autres catégories.");
        }
    }

    /** Une categorie deja rangee sous ce parent reste modifiable, meme creee avant cette regle. */
    private void ensureCanMoveUnder(Categorie category, Categorie parent) {
        boolean sameParent = category.getParent() != null && category.getParent().getId().equals(parent.getId());
        if (sameParent) return;
        ensureCanContainChildren(parent);
        if (!categoryRepository.findByParent(category).isEmpty()) {
            throw new IllegalArgumentException("La catégorie « " + category.getName()
                    + " » contient des sous-catégories : déplacez-les ou fusionnez-les d'abord.");
        }
    }

    /** Nom obligatoire (espaces retires) : refuse cote service plutot qu'a l'insertion SQL (colonne NOT NULL). */
    private static String requireName(CategorieDTO payload) {
        String name = payload.getName() != null ? payload.getName().trim() : "";
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Le nom de la catégorie est obligatoire.");
        }
        LongueurTexte.verifier(name, "Le nom de la catégorie");
        return name;
    }

    /**
     * Refuse deux categories de meme nom (casse ignoree) sous le meme parent :
     * impossibles a distinguer a la saisie, et l'import (findOrCreateByName,
     * qui attend au plus un resultat) echouait alors avec une erreur 500.
     * `selfId` (null en creation) exclut la categorie modifiee elle-meme.
     */
    private void ensureUniqueAmongSiblings(String name, Categorie parent, Long selfId) {
        Optional<Categorie> homonym = parent == null
                ? categoryRepository.findByNameIgnoreCaseAndParentIsNull(name)
                : categoryRepository.findByNameIgnoreCaseAndParent(name, parent);
        if (homonym.isPresent() && !homonym.get().getId().equals(selfId)) {
            throw new IllegalArgumentException(parent == null
                    ? "Une catégorie « " + name + " » existe déjà."
                    : "La catégorie « " + parent.getName() + " » contient déjà une sous-catégorie « " + name + " ».");
        }
    }

    /** Icone optionnelle (emoji), bornee a la taille de la colonne : sinon refus SQL au message trompeur. */
    private static String requireIcon(CategorieDTO payload) {
        String icon = payload.getIcon();
        if (icon == null || icon.isBlank()) return null;
        LongueurTexte.verifier(icon.trim(), "L'icône de la catégorie");
        return icon.trim();
    }

    /** Type obligatoire pour une categorie racine (une sous-categorie herite toujours de son parent). */
    private static TypeCategorie requireRootType(CategorieDTO payload) {
        if (payload.getType() == null) {
            throw new IllegalArgumentException("Le type (revenu, dépense ou les deux) est obligatoire.");
        }
        return payload.getType();
    }

    /** Indique si `candidate` est `ancestor` lui-meme ou l'une de ses descendantes (remonte la chaine des parents). */
    private boolean isSelfOrDescendant(Categorie candidate, Categorie ancestor) {
        Categorie current = candidate;
        while (current != null) {
            if (current.getId().equals(ancestor.getId())) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Propage recursivement le type d'une categorie a toute sa descendance (enfants, petits-enfants...). */
    private void cascadeTypeToDescendants(Categorie category) {
        List<Categorie> children = categoryRepository.findByParent(category);
        for (Categorie child : children) {
            child.setType(category.getType());
        }
        categoryRepository.saveAll(children);
        for (Categorie child : children) {
            cascadeTypeToDescendants(child);
        }
    }

    /**
     * Supprime une categorie. Les tiers qui l'avaient comme categorie par
     * defaut (voir Tiers#category) sont d'abord detaches (categorie remise a
     * null) plutot que de bloquer la suppression via la contrainte de cle
     * etrangere : contrairement a une transaction, une categorie par defaut
     * de tiers n'est qu'un raccourci de saisie, sa perte est sans consequence
     * sur l'historique. Les transactions qui utilisent encore cette
     * categorie (ou une sous-categorie), elles, bloquent la suppression :
     * refus explicite (409) avec leur nombre, il faut les reaffecter via
     * "Fusionner".
     * <p>
     * Les sous-categories sont supprimees avec leur parent (cascade de
     * Categorie#children) : les tiers rattaches a l'une d'elles sont donc
     * detaches eux aussi, sans quoi leur cle etrangere bloquait la
     * suppression avec un message sans rapport.
     */
    @Transactional
    public void delete(Long id) {
        Categorie category = findById(id);
        List<Categorie> subtree = new ArrayList<>();
        collectSubtree(category, subtree);

        long transactionCount = transactionRepository.countByCategoryIn(subtree);
        if (transactionCount > 0) {
            String scope = subtree.size() > 1 ? "Cette catégorie ou l'une de ses sous-catégories est utilisée" : "Cette catégorie est utilisée";
            throw new IllegalStateException(scope + " par " + transactionCount
                    + " transaction(s). Fusionnez-la avec une autre catégorie plutôt que de la supprimer.");
        }

        subtree.forEach(payeeRepository::clearCategory);
        categoryRepository.delete(category);
        log.info("Categorie supprimee : {} ({}), avec {} sous-categorie(s)", category.getName(), id, subtree.size() - 1);
    }

    /** `category` et toute sa descendance, parents avant enfants (voir delete). */
    private void collectSubtree(Categorie category, List<Categorie> subtree) {
        subtree.add(category);
        for (Categorie child : categoryRepository.findByParent(category)) {
            collectSubtree(child, subtree);
        }
    }

    /** Bascule le statut masque/visible d'une categorie (ne touche a rien d'autre) */
    public Categorie toggleHidden(Long id) {
        Categorie existing = findById(id);
        existing.setHidden(!existing.isHidden());
        log.info("Categorie {} ({}) {}", existing.getName(), id, existing.isHidden() ? "masquee" : "reaffichee");
        return categoryRepository.save(existing);
    }

    /**
     * Bascule le statut "annuelle" d'une categorie (voir Categorie#annualized) :
     * toute transaction qui lui est affectee sera desormais (ou ne sera plus)
     * lissee sur 12 mois dans les rapports, sans avoir a le cocher transaction
     * par transaction.
     */
    public Categorie toggleAnnualized(Long id) {
        Categorie existing = findById(id);
        existing.setAnnualized(!existing.isAnnualized());
        log.info("Categorie {} ({}) : lissage annuel {}", existing.getName(), id, existing.isAnnualized() ? "active" : "desactive");
        return categoryRepository.save(existing);
    }

    /**
     * Fusionne plusieurs categories (sourceIds) dans une categorie cible (targetId) :
     * - toutes les transactions des categories source sont reaffectees a la cible ;
     * - tous les tiers dont la categorie par defaut etait une source sont reaffectes a la cible ;
     * - les sous-categories des categories source sont reparentees sous la cible,
     *   ou fusionnees avec sa sous-categorie de meme nom si elle en a une ;
     * - les categories source sont supprimees.
     */
    @Transactional
    public Categorie merge(List<Long> sourceIds, Long targetId) {
        if (targetId == null) {
            throw new IllegalArgumentException("La catégorie à conserver est obligatoire.");
        }
        if (sourceIds == null) {
            throw new IllegalArgumentException("La liste des catégories à fusionner est obligatoire.");
        }
        Categorie target = findById(targetId);

        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId.equals(targetId)) continue;
            Categorie source = findById(sourceId);
            if (isSelfOrDescendant(target, source)) {
                throw new IllegalArgumentException("Une catégorie ne peut pas être fusionnée dans l'une de ses propres sous-catégories.");
            }

            List<Categorie> sourceChildren = categoryRepository.findByParent(source);
            if (target.getParent() != null && !sourceChildren.isEmpty()) {
                throw new IllegalArgumentException("« " + source.getName() + " » contient des sous-catégories : "
                        + "fusionnez-la dans une catégorie principale, pas dans la sous-catégorie « " + target.getName() + " ».");
            }

            transactionRepository.reassignCategory(source, target);
            payeeRepository.reassignCategory(source, target);

            List<Categorie> movedChildren = new ArrayList<>();
            for (Categorie child : sourceChildren) {
                Optional<Categorie> homonym = categoryRepository.findByNameIgnoreCaseAndParent(child.getName(), target);
                if (homonym.isPresent()) {
                    // La cible a deja une sous-categorie de ce nom : on les fusionne
                    // plutot que de creer deux soeurs homonymes (voir ensureUniqueAmongSiblings).
                    merge(List.of(child.getId()), homonym.get().getId());
                } else {
                    child.setParent(target);
                    child.setType(target.getType());
                    movedChildren.add(child);
                }
            }
            categoryRepository.saveAll(movedChildren);
            // Repercute aussi sur les petits-enfants (sous-categories des enfants reparentes).
            for (Categorie child : movedChildren) {
                cascadeTypeToDescendants(child);
            }

            categoryRepository.delete(source);
            log.info("Categorie {} ({}) fusionnee dans {} ({})", source.getName(), sourceId, target.getName(), targetId);
        }

        return target;
    }

    /** Recherche (ou creation) d'une categorie par son nom, utilisee lors de l'import. Type par defaut (EXPENSE) faute de montant pour le deviner. */
    public Categorie findOrCreateByName(String name) {
        return findOrCreateByName(name, null);
    }

    /**
     * Recherche (ou creation) d'une categorie par son nom, utilisee lors de
     * l'import. Si une nouvelle categorie racine doit etre creee, son type
     * (revenu/depense) est devine a partir du signe du montant de la
     * transaction qui la declenche : sans cet indice (montant absent ou nul),
     * on retombe sur le defaut EXPENSE plutot que de deviner au hasard. Une
     * categorie deja existante n'est jamais modifiee : ce n'est qu'une
     * estimation initiale, ajustable ensuite manuellement (page Categories).
     */
    public Categorie findOrCreateByName(String name, BigDecimal amount) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        // Support du format HomeBank "Parent:Enfant", sauf si une categorie
        // principale porte exactement ce nom (son export s'ecrit pareil).
        if (trimmed.contains(":")) {
            Optional<Categorie> sameNameRoot = categoryRepository.findByNameIgnoreCaseAndParentIsNull(trimmed);
            if (sameNameRoot.isPresent()) return sameNameRoot.get();
            String[] parts = trimmed.split(":", 2);
            Categorie parent = findOrCreateByName(parts[0], amount);
            // Nom de l'enfant nettoye AVANT la recherche : "Courses: Alimentation"
            // cherchait " Alimentation" et recreait la sous-categorie a chaque import.
            String childName = parts[1].trim();
            if (parent == null) return findOrCreateByName(childName, amount);
            if (childName.isEmpty()) return parent;
            Optional<Categorie> existingChild = categoryRepository.findByNameIgnoreCaseAndParent(childName, parent);
            if (existingChild.isPresent()) return existingChild.get();
            Categorie child = new Categorie();
            child.setName(childName);
            child.setParent(parent);
            child.setType(parent.getType());
            return categoryRepository.save(child);
        }
        Optional<Categorie> existing = categoryRepository.findByNameIgnoreCaseAndParentIsNull(trimmed);
        if (existing.isPresent()) return existing.get();
        Categorie cat = new Categorie();
        cat.setName(trimmed);
        if (amount != null && amount.signum() > 0) {
            cat.setType(Categorie.TypeCategorie.INCOME);
        }
        return categoryRepository.save(cat);
    }
}

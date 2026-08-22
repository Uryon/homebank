package com.homebank.service;

import com.homebank.dto.CategorieDTO;
import com.homebank.model.Categorie;
import com.homebank.repository.DepotCategorie;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ServiceCategorie {

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
                .orElseThrow(() -> new NoSuchElementException("Categorie introuvable : " + id));
    }

    public Categorie create(CategorieDTO payload) {
        Categorie category = new Categorie();
        category.setName(payload.getName());
        if (payload.getParentId() != null) {
            Categorie parent = findById(payload.getParentId());
            category.setParent(parent);
            // Une sous-categorie appartient toujours au meme groupe (revenu/depense/les deux)
            // que sa categorie parente : son propre type n'est pas selectionnable independamment.
            category.setType(parent.getType());
        } else {
            category.setParent(null);
            category.setType(payload.getType());
        }
        return categoryRepository.save(category);
    }

    public Categorie update(Long id, CategorieDTO payload) {
        Categorie existing = findById(id);
        existing.setName(payload.getName());

        if (payload.getParentId() != null) {
            if (payload.getParentId().equals(id)) {
                throw new IllegalArgumentException("Une categorie ne peut pas etre son propre parent");
            }
            Categorie parent = findById(payload.getParentId());
            existing.setParent(parent);
            // Sous-categorie : le type suit toujours celui du parent, non modifiable independamment.
            existing.setType(parent.getType());
        } else {
            existing.setParent(null);
            existing.setType(payload.getType());
            // Categorie racine : si son type change, on le repercute sur toutes ses
            // sous-categories pour qu'elles restent dans le meme groupe (revenu/depense).
            List<Categorie> children = categoryRepository.findByParent(existing);
            for (Categorie child : children) {
                child.setType(existing.getType());
            }
            categoryRepository.saveAll(children);
        }

        return categoryRepository.save(existing);
    }

    public void delete(Long id) {
        categoryRepository.deleteById(id);
    }

    /**
     * Fusionne plusieurs categories (sourceIds) dans une categorie cible (targetId) :
     * - toutes les transactions des categories source sont reaffectees a la cible ;
     * - tous les tiers dont la categorie par defaut etait une source sont reaffectes a la cible ;
     * - les sous-categories des categories source sont reparentees sous la cible ;
     * - les categories source sont supprimees.
     */
    @Transactional
    public Categorie merge(List<Long> sourceIds, Long targetId) {
        Categorie target = findById(targetId);

        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId.equals(targetId)) continue;
            Categorie source = findById(sourceId);

            transactionRepository.reassignCategory(source, target);
            payeeRepository.reassignCategory(source, target);

            List<Categorie> children = categoryRepository.findByParent(source);
            for (Categorie child : children) {
                child.setParent(target);
                child.setType(target.getType());
            }
            categoryRepository.saveAll(children);

            categoryRepository.delete(source);
        }

        return target;
    }

    /** Recherche (ou creation) d'une categorie par son nom, utilisee lors de l'import */
    public Categorie findOrCreateByName(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        // Support du format HomeBank "Parent:Enfant"
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":", 2);
            Categorie parent = findOrCreateByName(parts[0]);
            Optional<Categorie> existingChild = categoryRepository.findByNameIgnoreCaseAndParent(parts[1], parent);
            if (existingChild.isPresent()) return existingChild.get();
            Categorie child = new Categorie();
            child.setName(parts[1].trim());
            child.setParent(parent);
            child.setType(parent.getType());
            return categoryRepository.save(child);
        }
        Optional<Categorie> existing = categoryRepository.findByNameIgnoreCaseAndParentIsNull(trimmed);
        if (existing.isPresent()) return existing.get();
        Categorie cat = new Categorie();
        cat.setName(trimmed);
        return categoryRepository.save(cat);
    }
}

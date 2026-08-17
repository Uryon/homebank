package com.homebank.service;

import com.homebank.model.Category;
import com.homebank.repository.CategoryRepository;
import com.homebank.repository.PayeeRepository;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final PayeeRepository payeeRepository;

    public CategoryService(CategoryRepository categoryRepository,
                            TransactionRepository transactionRepository,
                            PayeeRepository payeeRepository) {
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.payeeRepository = payeeRepository;
    }

    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    public List<Category> findRoots() {
        return categoryRepository.findByParentIsNull();
    }

    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Categorie introuvable : " + id));
    }

    public Category create(Category category) {
        category.setId(null);
        if (category.getParent() != null && category.getParent().getId() != null) {
            Category parent = findById(category.getParent().getId());
            category.setParent(parent);
            // Une sous-categorie appartient toujours au meme groupe (revenu/depense/les deux)
            // que sa categorie parente : son propre type n'est pas selectionnable independamment.
            category.setType(parent.getType());
        } else {
            category.setParent(null);
        }
        return categoryRepository.save(category);
    }

    public Category update(Long id, Category payload) {
        Category existing = findById(id);
        existing.setName(payload.getName());

        if (payload.getParent() != null && payload.getParent().getId() != null) {
            if (payload.getParent().getId().equals(id)) {
                throw new IllegalArgumentException("Une categorie ne peut pas etre son propre parent");
            }
            Category parent = findById(payload.getParent().getId());
            existing.setParent(parent);
            // Sous-categorie : le type suit toujours celui du parent, non modifiable independamment.
            existing.setType(parent.getType());
        } else {
            existing.setParent(null);
            existing.setType(payload.getType());
            // Categorie racine : si son type change, on le repercute sur toutes ses
            // sous-categories pour qu'elles restent dans le meme groupe (revenu/depense).
            List<Category> children = categoryRepository.findByParent(existing);
            for (Category child : children) {
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
    public Category merge(List<Long> sourceIds, Long targetId) {
        Category target = findById(targetId);

        for (Long sourceId : sourceIds) {
            if (sourceId == null || sourceId.equals(targetId)) continue;
            Category source = findById(sourceId);

            transactionRepository.reassignCategory(source, target);
            payeeRepository.reassignCategory(source, target);

            List<Category> children = categoryRepository.findByParent(source);
            for (Category child : children) {
                child.setParent(target);
                child.setType(target.getType());
            }
            categoryRepository.saveAll(children);

            categoryRepository.delete(source);
        }

        return target;
    }

    /** Recherche (ou creation) d'une categorie par son nom, utilisee lors de l'import */
    public Category findOrCreateByName(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        // Support du format HomeBank "Parent:Enfant"
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":", 2);
            Category parent = findOrCreateByName(parts[0]);
            Optional<Category> existingChild = categoryRepository.findByNameIgnoreCaseAndParent(parts[1], parent);
            if (existingChild.isPresent()) return existingChild.get();
            Category child = new Category();
            child.setName(parts[1].trim());
            child.setParent(parent);
            child.setType(parent.getType());
            return categoryRepository.save(child);
        }
        Optional<Category> existing = categoryRepository.findByNameIgnoreCaseAndParentIsNull(trimmed);
        if (existing.isPresent()) return existing.get();
        Category cat = new Category();
        cat.setName(trimmed);
        return categoryRepository.save(cat);
    }
}

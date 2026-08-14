package com.homebank.service;

import com.homebank.model.Category;
import com.homebank.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
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
            category.setParent(findById(category.getParent().getId()));
        } else {
            category.setParent(null);
        }
        return categoryRepository.save(category);
    }

    public Category update(Long id, Category payload) {
        Category existing = findById(id);
        existing.setName(payload.getName());
        existing.setType(payload.getType());
        if (payload.getParent() != null && payload.getParent().getId() != null) {
            if (payload.getParent().getId().equals(id)) {
                throw new IllegalArgumentException("Une categorie ne peut pas etre son propre parent");
            }
            existing.setParent(findById(payload.getParent().getId()));
        } else {
            existing.setParent(null);
        }
        return categoryRepository.save(existing);
    }

    public void delete(Long id) {
        categoryRepository.deleteById(id);
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
            return categoryRepository.save(child);
        }
        Optional<Category> existing = categoryRepository.findByNameIgnoreCaseAndParentIsNull(trimmed);
        if (existing.isPresent()) return existing.get();
        Category cat = new Category();
        cat.setName(trimmed);
        return categoryRepository.save(cat);
    }
}

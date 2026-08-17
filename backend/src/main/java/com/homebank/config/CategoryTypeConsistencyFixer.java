package com.homebank.config;

import com.homebank.model.Category;
import com.homebank.repository.CategoryRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Corrige au demarrage les sous-categories dont le type (revenu/depense/les
 * deux) ne correspond pas a celui de leur categorie parente. Ceci peut arriver
 * pour des donnees creees avant que la coherence parent/enfant soit imposee
 * par CategoryService (creation, edition, fusion). Operation idempotente et
 * peu couteuse : n'a aucun effet une fois les donnees deja coherentes.
 */
@Component
@Order(10)
public class CategoryTypeConsistencyFixer implements ApplicationRunner {

    private final CategoryRepository categoryRepository;

    public CategoryTypeConsistencyFixer(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Category> roots = categoryRepository.findByParentIsNull();
        List<Category> toFix = new ArrayList<>();

        for (Category root : roots) {
            for (Category child : categoryRepository.findByParent(root)) {
                if (child.getType() != root.getType()) {
                    child.setType(root.getType());
                    toFix.add(child);
                }
            }
        }

        if (!toFix.isEmpty()) {
            categoryRepository.saveAll(toFix);
            System.out.println(">>> Coherence categories : " + toFix.size()
                    + " sous-categorie(s) realignee(s) sur le type de leur parent.");
        }
    }
}

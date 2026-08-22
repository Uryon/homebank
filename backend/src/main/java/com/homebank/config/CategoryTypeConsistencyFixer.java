package com.homebank.config;

import com.homebank.model.Categorie;
import com.homebank.repository.DepotCategorie;
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
 * par ServiceCategorie (creation, edition, fusion). Operation idempotente et
 * peu couteuse : n'a aucun effet une fois les donnees deja coherentes.
 */
@Component
@Order(10)
public class CategoryTypeConsistencyFixer implements ApplicationRunner {

    private final DepotCategorie categoryRepository;

    public CategoryTypeConsistencyFixer(DepotCategorie categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Categorie> roots = categoryRepository.findByParentIsNull();
        List<Categorie> toFix = new ArrayList<>();

        for (Categorie root : roots) {
            for (Categorie child : categoryRepository.findByParent(root)) {
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

package com.homebank.config;

import com.homebank.model.Categorie;
import com.homebank.repository.DepotCategorie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Corrige au demarrage les sous-categories dont le type (revenu/depense/les
 * deux) ne correspond pas a celui de leur categorie parente. Ceci peut arriver
 * pour des donnees creees avant que la coherence parent/enfant soit imposee
 * par ServiceCategorie (creation, edition, fusion). Operation idempotente et
 * peu couteuse : n'a aucun effet une fois les donnees deja coherentes.
 * <p>
 * Sur une chaine a 3 niveaux ou plus (racine -> milieu -> feuille), un seul
 * passage dans l'ordre de findAll() (non garanti "parents avant enfants")
 * peut realigner une feuille sur le type encore incorrect de son parent
 * direct, juste avant que ce parent soit lui-meme corrige plus loin dans la
 * meme iteration. Le passage est donc repete jusqu'a plus aucun changement
 * (point fixe), pour que toute la descendance converge en un seul demarrage
 * plutot que de necessiter un redemarrage supplementaire par niveau.
 */
@Component
@Order(10)
public class CategoryTypeConsistencyFixer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CategoryTypeConsistencyFixer.class);

    private final DepotCategorie categoryRepository;

    public CategoryTypeConsistencyFixer(DepotCategorie categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Categorie> all = categoryRepository.findAll();
        Set<Categorie> toFix = new LinkedHashSet<>();

        // Borne le nombre de passages a la taille de la liste : une chaine de
        // parente ne peut pas etre plus profonde que le nombre de categories,
        // meme dans le pire ordre d'iteration possible.
        boolean changed = true;
        for (int pass = 0; changed && pass <= all.size(); pass++) {
            changed = false;
            for (Categorie child : all) {
                Categorie parent = child.getParent();
                if (parent != null && child.getType() != parent.getType()) {
                    child.setType(parent.getType());
                    toFix.add(child);
                    changed = true;
                }
            }
        }

        if (!toFix.isEmpty()) {
            categoryRepository.saveAll(toFix);
            log.info("Coherence categories : {} sous-categorie(s) realignee(s) sur le type de leur parent.", toFix.size());
        }
    }
}

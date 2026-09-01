package com.homebank.config;

import com.homebank.model.Categorie;
import com.homebank.repository.DepotCategorie;
import com.homebank.repository.DepotTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Migration unique : l'annualisation (lissage sur 12 mois dans les rapports)
 * est passee d'un marquage par transaction (ancienne colonne
 * "transactions.annualized", desormais orpheline) a un marquage par
 * categorie (voir Categorie#annualized). Au demarrage, toute categorie ayant
 * au moins une transaction marquee dans l'ancienne colonne est marquee
 * "annuelle" a son tour, puis l'ancienne colonne est remise a false pour
 * neutraliser la migration : sans ca, elle re-forcerait "annuelle" sur une
 * categorie que l'utilisateur aurait ensuite volontairement decochee, a
 * chaque redemarrage. Ordonnee apres PayeeRelationMigration. Sans effet sur
 * un schema qui n'a jamais connu l'ancienne colonne (nouvelle installation).
 */
@Component
@Order(22)
public class CategoryAnnualizedMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CategoryAnnualizedMigration.class);

    private final DepotTransaction transactionRepository;
    private final DepotCategorie categoryRepository;

    public CategoryAnnualizedMigration(DepotTransaction transactionRepository, DepotCategorie categoryRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (transactionRepository.countLegacyAnnualizedColumn() == 0) return;

        List<Long> categoryIds = transactionRepository.findCategoryIdsWithLegacyAnnualizedTransactions();
        if (!categoryIds.isEmpty()) {
            List<Categorie> categories = categoryRepository.findAllById(categoryIds);
            for (Categorie category : categories) {
                category.setAnnualized(true);
            }
            categoryRepository.saveAll(categories);
            log.info("Categories : {} categorie(s) marquee(s) 'annuelle' depuis l'ancien marquage par transaction.", categories.size());
        }

        transactionRepository.clearLegacyAnnualizedColumn();
    }
}

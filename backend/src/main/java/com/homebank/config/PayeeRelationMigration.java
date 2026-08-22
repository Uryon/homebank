package com.homebank.config;

import com.homebank.service.ServiceTiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Relie au demarrage chaque transaction pas encore rattachee a un tiers
 * (colonne payee_id) au Payee correspondant a son ancien texte libre
 * (ancienne colonne "payee", desormais orpheline depuis le passage de
 * Transaction#payee a une vraie relation vers Tiers — voir
 * ServiceTiers#migrateLegacyTransactionPayees). Ordonnee apres
 * PayeePatternMigration (motifs de tiers nettoyes en premier, pour que la
 * reconnaissance par motif utilisee ici soit deja a jour). Idempotente :
 * ne touche que les transactions dont payee_id est encore NULL.
 */
@Component
@Order(21)
public class PayeeRelationMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PayeeRelationMigration.class);

    private final ServiceTiers payeeService;

    public PayeeRelationMigration(ServiceTiers payeeService) {
        this.payeeService = payeeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migrated = payeeService.migrateLegacyTransactionPayees();
        if (migrated > 0) {
            log.info("Transactions : {} transaction(s) reliee(s) a leur tiers via la nouvelle relation Transaction#payee.", migrated);
        }
    }
}

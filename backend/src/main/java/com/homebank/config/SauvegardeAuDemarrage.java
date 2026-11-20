package com.homebank.config;

import com.homebank.service.ServiceSauvegarde;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Sauvegarde automatique du jour au démarrage de l'appli (voir
 * ServiceSauvegarde#sauvegardeAutomatique), en tâche de fond : le démarrage
 * n'attend pas l'écriture du fichier. Ni en profil test (données de
 * démonstration), ni pendant l'entraînement du démarrage rapide (voir
 * EntrainementDemarrage), ni si sauvegarde.automatique=false.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "sauvegarde.automatique", havingValue = "true", matchIfMissing = true)
public class SauvegardeAuDemarrage implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SauvegardeAuDemarrage.class);

    private final ServiceSauvegarde sauvegardeService;
    private final TaskExecutor executor;
    private final boolean entrainement;

    public SauvegardeAuDemarrage(ServiceSauvegarde sauvegardeService, TaskExecutor executor,
                                 @Value("${homebank.entrainement-demarrage:false}") boolean entrainement) {
        this.sauvegardeService = sauvegardeService;
        this.executor = executor;
        this.entrainement = entrainement;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (entrainement) return;
        executor.execute(() -> {
            try {
                sauvegardeService.sauvegardeAutomatique();
            } catch (RuntimeException e) {
                // Non bloquant : l'appli reste utilisable, la sauvegarde sera retentée au prochain démarrage.
                log.warn("Sauvegarde automatique impossible : {}", e.getMessage());
            }
        });
    }
}

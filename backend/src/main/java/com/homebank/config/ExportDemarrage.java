package com.homebank.config;

import com.homebank.service.ServiceExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Lance un export PDF du tableau de bord au demarrage de l'application (en
 * complement du bouton "Exporter en PDF" manuel, voir ServiceExport /
 * ControleurExport), pour disposer automatiquement d'une version a jour dans
 * le dossier synchronise sans avoir a y penser. Ordonnee en dernier (apres
 * les migrations de donnees ci-dessus) : sans impact ici (l'export tourne en
 * arriere-plan, voir plus bas) mais garde l'intention explicite.
 * <p>
 * Execute dans un thread separe (demon) plutot que dans
 * ApplicationRunner#run lui-meme : l'export peut prendre jusqu'a 30 secondes
 * (voir ServiceExport#DELAI_MAX_SECONDES) et necessite que le frontend soit
 * deja joignable (Playwright y navigue) — bloquer le demarrage de
 * l'application jusque-la retarderait inutilement sa disponibilite, alors
 * que cet export n'est qu'un confort, jamais requis pour que l'application
 * fonctionne. Un echec (frontend pas encore demarre, Node/Playwright absent,
 * export non configure...) est donc journalise en warning sans jamais faire
 * echouer le demarrage — le bouton manuel reste toujours disponible ensuite.
 */
@Component
@Order(90)
public class ExportDemarrage implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportDemarrage.class);

    private final ServiceExport exportService;
    private final String dossierSortie;

    public ExportDemarrage(
            ServiceExport exportService,
            @Value("${export.dossier-sortie:}") String dossierSortie) {
        this.exportService = exportService;
        this.dossierSortie = dossierSortie;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dossierSortie == null || dossierSortie.isBlank()) {
            log.info("Export PDF au demarrage ignore : export.dossier-sortie non configure.");
            return;
        }
        Thread thread = new Thread(this::exporter, "export-pdf-demarrage");
        thread.setDaemon(true);
        thread.start();
    }

    private void exporter() {
        try {
            exportService.exporterTableauDeBord();
        } catch (Exception e) {
            log.warn("Export PDF au demarrage echoue (le frontend n'est peut-etre pas encore accessible) : {}", e.getMessage());
        }
    }
}

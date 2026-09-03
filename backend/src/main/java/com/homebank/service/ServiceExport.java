package com.homebank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Export du tableau de bord en PDF, via un script Node/Playwright (voir
 * export/export-dashboard.mjs) qui capture la page telle qu'elle est
 * reellement rendue (graphiques Chart.js compris) — evite de dupliquer la
 * mise en page du tableau de bord dans un template PDF cote backend. Le PDF
 * est ecrit par defaut dans export/sortie/ a la racine du projet (voir
 * export.dossier-sortie) — dossier dedie ne contenant que les PDF generes,
 * synchronise par Google Drive pour ordinateur, pour consultation a distance
 * (telephone, autre PC) pendant que le PC reste allume.
 */
@Service
public class ServiceExport {

    private static final Logger log = LoggerFactory.getLogger(ServiceExport.class);
    private static final long DELAI_MAX_SECONDES = 30;

    private final String dossierSortie;
    private final String urlTableauDeBord;
    private final String cheminScript;
    private final String cheminNode;

    public ServiceExport(
            @Value("${export.dossier-sortie}") String dossierSortie,
            @Value("${export.url-tableau-de-bord}") String urlTableauDeBord,
            @Value("${export.chemin-script}") String cheminScript,
            // Resout "node" par son nom via le PATH par defaut (comportement
            // historique, portable) ; peut etre epingle a un chemin absolu via
            // EXPORT_CHEMIN_NODE si on veut eliminer toute dependance au PATH
            // (voir le hotspot Sonar "PATH variable" sur cet appel).
            @Value("${export.chemin-node:node}") String cheminNode) {
        this.dossierSortie = dossierSortie;
        this.urlTableauDeBord = urlTableauDeBord;
        this.cheminScript = cheminScript;
        this.cheminNode = cheminNode;
    }

    public void exporterTableauDeBord() {
        if (dossierSortie == null || dossierSortie.isBlank()) {
            throw new IllegalStateException(
                    "Export PDF non configure : renseigner export.dossier-sortie (variable "
                            + "d'environnement EXPORT_DOSSIER_SORTIE) avec le dossier synchronise "
                            + "(Google Drive pour ordinateur...).");
        }

        ProcessBuilder pb = new ProcessBuilder(cheminNode, cheminScript, dossierSortie, urlTableauDeBord)
                .redirectErrorStream(true);
        try {
            Process process = pb.start();
            String sortie = new String(process.getInputStream().readAllBytes());
            boolean termine = process.waitFor(DELAI_MAX_SECONDES, TimeUnit.SECONDS);
            if (!termine) {
                process.destroyForcibly();
                throw new IllegalStateException("Export PDF : delai depasse (" + DELAI_MAX_SECONDES + "s)");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Export PDF echoue : " + sortie.trim());
            }
            log.info("Export PDF du tableau de bord termine : {}", sortie.trim());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Impossible de lancer l'export PDF (node installe et dans le PATH ? "
                            + "'npm install' lance dans export/ ? chemin " + cheminScript + " correct ?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Export PDF interrompu", e);
        }
    }
}

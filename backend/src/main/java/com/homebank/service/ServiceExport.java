package com.homebank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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

    private final String dossierSortie;
    private final String urlTableauDeBord;
    private final String cheminScript;
    private final String cheminNode;
    private final long delaiMaxSecondes;
    private final ServiceDernierImport dernierImportService;

    public ServiceExport(
            @Value("${export.dossier-sortie}") String dossierSortie,
            @Value("${export.url-tableau-de-bord}") String urlTableauDeBord,
            @Value("${export.chemin-script}") String cheminScript,
            // Resout "node" par son nom via le PATH par defaut (comportement
            // historique, portable) ; peut etre epingle a un chemin absolu via
            // EXPORT_CHEMIN_NODE si on veut eliminer toute dependance au PATH
            // (voir le hotspot Sonar "PATH variable" sur cet appel).
            @Value("${export.chemin-node:node}") String cheminNode,
            @Value("${export.delai-max-secondes:30}") long delaiMaxSecondes,
            ServiceDernierImport dernierImportService) {
        this.dossierSortie = dossierSortie;
        this.urlTableauDeBord = urlTableauDeBord;
        this.cheminScript = cheminScript;
        this.cheminNode = cheminNode;
        this.delaiMaxSecondes = delaiMaxSecondes;
        this.dernierImportService = dernierImportService;
    }

    public void exporterTableauDeBord() {
        if (dossierSortie == null || dossierSortie.isBlank()) {
            throw new IllegalStateException(
                    "Export PDF non configure : renseigner export.dossier-sortie (variable "
                            + "d'environnement EXPORT_DOSSIER_SORTIE) avec le dossier synchronise "
                            + "(Google Drive pour ordinateur...).");
        }

        // Transmis au script au lieu de laisser Node calculer sa propre date
        // "maintenant" : la date affichee dans l'export doit refleter la
        // fraicheur reelle des donnees (dernier import de fichier), pas
        // l'instant de generation du PDF, qui peut tourner sans nouvel
        // import (bouton manuel repete, export automatique...). Chaine vide
        // si aucun import n'a encore ete effectue (voir ServiceDernierImport).
        LocalDateTime dateDernierImport = dernierImportService.dateDernierImport();
        String argDateDernierImport = dateDernierImport != null ? dateDernierImport.toString() : "";

        ProcessBuilder pb = new ProcessBuilder(
                cheminNode, cheminScript, dossierSortie, urlTableauDeBord, argDateDernierImport)
                .redirectErrorStream(true);
        Path journal = null;
        try {
            // Sortie du script redirigee vers un fichier, jamais lue en direct :
            // lire le flux du processus bloque jusqu'a ce qu'il se termine, ce
            // qui rendait le delai maximal inoperant (un script Node fige
            // bloquait la requete indefiniment).
            journal = Files.createTempFile("homebank-export-", ".log");
            Process process = pb.redirectOutput(journal.toFile()).start();
            boolean termine = process.waitFor(delaiMaxSecondes, TimeUnit.SECONDS);
            if (!termine) {
                // Le navigateur lance par Playwright est un processus enfant de
                // Node : sans ca, il survivrait a l'arret force du script.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("Export PDF : delai depasse (" + delaiMaxSecondes + "s)");
            }
            String sortie = new String(Files.readAllBytes(journal), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Export PDF echoue : " + sortie);
            }
            log.info("Export PDF du tableau de bord termine : {}", sortie);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Impossible de lancer l'export PDF (node installe et dans le PATH ? "
                            + "'npm install' lance dans export/ ? chemin " + cheminScript + " correct ?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Export PDF interrompu", e);
        } finally {
            supprimerJournal(journal);
        }
    }

    private static void supprimerJournal(Path journal) {
        if (journal == null) return;
        try {
            Files.deleteIfExists(journal);
        } catch (IOException e) {
            log.warn("Fichier temporaire d'export non supprime : {}", journal, e);
        }
    }
}

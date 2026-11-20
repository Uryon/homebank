package com.homebank.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** Une fois le script termine, sa sortie est disponible presque aussitot. */
    private static final long DELAI_LECTURE_SORTIE_SECONDES = 5;

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
                    "Export PDF non configuré : renseigner export.dossier-sortie (variable "
                            + "d'environnement EXPORT_DOSSIER_SORTIE) avec le dossier synchronisé "
                            + "(Google Drive pour ordinateur…).");
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
        try {
            Process process = pb.start();
            // Sortie lue en parallele, jamais sur ce fil : lire le flux attend
            // la fin du processus, ce qui rendait le delai maximal inoperant
            // (un script Node fige bloquait la requete indefiniment).
            CompletableFuture<String> sortie = lireSortieEnParallele(process);
            boolean termine = process.waitFor(delaiMaxSecondes, TimeUnit.SECONDS);
            if (!termine) {
                // Le navigateur lance par Playwright est un processus enfant de
                // Node : sans ca, il survivrait a l'arret force du script.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("Export PDF : délai dépassé (" + delaiMaxSecondes + "s)");
            }
            String texte = sortieTerminee(sortie);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Export PDF échoué : " + texte);
            }
            log.info("Export PDF du tableau de bord termine : {}", texte);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Impossible de lancer l'export PDF (node installé et dans le PATH ? "
                            + "'npm install' lancé dans export/ ? chemin " + cheminScript + " correct ?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Export PDF interrompu", e);
        }
    }

    /** Lit toute la sortie du script sur un fil virtuel ; termine quand le processus ferme sa sortie. */
    private static CompletableFuture<String> lireSortieEnParallele(Process process) {
        CompletableFuture<String> sortie = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try (InputStream flux = process.getInputStream()) {
                sortie.complete(new String(flux.readAllBytes(), StandardCharsets.UTF_8).trim());
            } catch (IOException e) {
                sortie.complete("(sortie du script illisible : " + e.getMessage() + ")");
            }
        });
        return sortie;
    }

    /** Sortie d'un script deja termine : quelques instants au plus pour finir de la lire. */
    private static String sortieTerminee(CompletableFuture<String> sortie) throws InterruptedException {
        try {
            return sortie.get(DELAI_LECTURE_SORTIE_SECONDES, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            return "(sortie du script indisponible)";
        }
    }

}

package com.homebank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homebank.dto.SauvegardeDTO;
import com.homebank.repository.DepotSauvegarde;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifications d'un fichier de sauvegarde avant toute modification, rotation
 * des sauvegardes automatiques et noms de fichiers acceptés à la relecture.
 * La restauration complète sur une vraie base est couverte par
 * SauvegardeIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class ServiceSauvegardeTest {

    @Mock
    private DepotSauvegarde depot;
    @Mock
    private PlatformTransactionManager transactionManager;
    @TempDir
    private Path dossier;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private ServiceSauvegarde service;

    @BeforeEach
    void setUp() {
        service = new ServiceSauvegarde(depot, json, transactionManager, dossier.toString(), 3);
    }

    /** Sauvegarde complète et vide (toutes les tables, aucune ligne). */
    private static Map<String, List<Map<String, Object>>> tablesVides() {
        Map<String, List<Map<String, Object>>> tables = new HashMap<>();
        DepotSauvegarde.TABLES.forEach(t -> tables.put(t.nom(), List.of()));
        return tables;
    }

    private static SauvegardeDTO sauvegarde(String application, int version, Map<String, List<Map<String, Object>>> tables) {
        return new SauvegardeDTO(application, version, LocalDateTime.of(2026, 11, 20, 10, 0), tables);
    }

    // --- verifier ---

    @Test
    void verifier_accepteUneSauvegardeComplete() {
        ServiceSauvegarde.verifier(sauvegarde("homebank", 1, tablesVides()));
    }

    @Test
    void verifier_refuseUnAutreFichierJson() {
        SauvegardeDTO autre = sauvegarde("autre", 1, tablesVides());
        assertThatThrownBy(() -> ServiceSauvegarde.verifier(autre)).hasMessageContaining("pas une sauvegarde HomeBank");
    }

    @Test
    void verifier_refuseUnFormatInconnu() {
        SauvegardeDTO future = sauvegarde("homebank", 2, tablesVides());
        assertThatThrownBy(() -> ServiceSauvegarde.verifier(future)).hasMessageContaining("version 2");
    }

    @Test
    void verifier_refuseUneTableInconnueOuAbsente() {
        Map<String, List<Map<String, Object>>> enTrop = tablesVides();
        enTrop.put("utilisateurs", List.of());
        SauvegardeDTO tableInconnue = sauvegarde("homebank", 1, enTrop);
        assertThatThrownBy(() -> ServiceSauvegarde.verifier(tableInconnue)).hasMessageContaining("utilisateurs");

        Map<String, List<Map<String, Object>>> incomplete = tablesVides();
        incomplete.remove("prets");
        SauvegardeDTO tableAbsente = sauvegarde("homebank", 1, incomplete);
        assertThatThrownBy(() -> ServiceSauvegarde.verifier(tableAbsente)).hasMessageContaining("prets");
    }

    @Test
    void verifier_refuseUneLigneSansIdOuAvecUneColonneInconnue() {
        Map<String, List<Map<String, Object>>> sansId = tablesVides();
        sansId.put("comptes", List.of(Map.of("name", "Courant")));
        SauvegardeDTO ligneSansId = sauvegarde("homebank", 1, sansId);
        assertThatThrownBy(() -> ServiceSauvegarde.verifier(ligneSansId)).hasMessageContaining("comptes");

        Map<String, List<Map<String, Object>>> colonneInconnue = tablesVides();
        colonneInconnue.put("comptes", List.of(Map.of("id", 1, "mot_de_passe", "x")));
        SauvegardeDTO ligneInattendue = sauvegarde("homebank", 1, colonneInconnue);
        assertThatThrownBy(() -> ServiceSauvegarde.verifier(ligneInattendue)).hasMessageContaining("comptes");
    }

    // --- restaurer ---

    @Test
    void restaurer_refuseUnFichierVideOuIlisibleSansToucherALaBase() {
        byte[] vide = new byte[0];
        byte[] illisible = "pas du json".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.restaurer(vide)).hasMessageContaining("vide");
        assertThatThrownBy(() -> service.restaurer(illisible)).hasMessageContaining("JSON illisible");
        verify(depot, never()).remplacerTout(any());
    }

    @Test
    void restaurer_ecritLesDonneesActuellesAvantDeLesRemplacer() throws IOException {
        when(depot.lire(any())).thenReturn(List.of());
        byte[] fichier = json.writeValueAsBytes(sauvegarde("homebank", 1, tablesVides()));

        String avant = service.restaurer(fichier).sauvegardeAvant();

        assertThat(avant).startsWith("homebank-avant-restauration-");
        assertThat(dossier.resolve(avant)).exists();
        verify(depot).remplacerTout(any());
    }

    @Test
    void restaurerFichier_refuseUnNomQuiSortDuDossier() {
        assertThatThrownBy(() -> service.restaurerFichier("../application.properties")).hasMessageContaining("invalide");
        assertThatThrownBy(() -> service.restaurerFichier(null)).hasMessageContaining("invalide");
        assertThatThrownBy(() -> service.restaurerFichier("homebank-absente.json")).hasMessageContaining("introuvable");
    }

    // --- sauvegarde automatique ---

    @Test
    void sauvegardeAutomatique_neGardeQueLesPlusRecentes() throws IOException {
        when(depot.lire(any())).thenReturn(List.of());
        for (String jour : List.of("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04")) {
            Files.writeString(dossier.resolve(ServiceSauvegarde.PREFIXE_AUTO + jour + ".json"), "{}");
        }
        Files.writeString(dossier.resolve("homebank-avant-restauration-2026-10-01_120000.json"), "{}");

        assertThat(service.sauvegardeAutomatique()).isPresent();

        try (Stream<Path> fichiers = Files.list(dossier)) {
            List<String> noms = fichiers.map(f -> f.getFileName().toString()).sorted().toList();
            // 3 automatiques conservées (dont celle du jour) ; les autres sauvegardes ne sont jamais supprimées.
            assertThat(noms).containsExactly(
                    "homebank-auto-2026-11-03.json",
                    "homebank-auto-2026-11-04.json",
                    ServiceSauvegarde.PREFIXE_AUTO + LocalDate.now() + ".json",
                    "homebank-avant-restauration-2026-10-01_120000.json");
        }
        assertThat(service.info().nombre()).isEqualTo(4);
    }

    @Test
    void info_sansDossierNiSauvegarde() {
        ServiceSauvegarde sansDossier = new ServiceSauvegarde(depot, json, transactionManager,
                dossier.resolve("inexistant").toString(), 3);
        assertThat(sansDossier.info().derniere()).isNull();
        assertThat(sansDossier.info().nombre()).isZero();
    }
}

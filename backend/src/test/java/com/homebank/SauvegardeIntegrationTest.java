package com.homebank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homebank.dto.ResultatRestaurationDTO;
import com.homebank.dto.SauvegardeDTO;
import com.homebank.service.ServiceSauvegarde;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sauvegarde et restauration sur la vraie base (H2, données de démonstration) :
 * une restauration remet exactement les données sauvegardées, ids compris, et
 * les créations suivantes reçoivent des ids neufs. Sans transaction de test
 * (la restauration recale les compteurs d'ids par ALTER TABLE, qui valide la
 * transaction sous H2) : contexte reconstruit après la classe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SauvegardeIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ServiceSauvegarde sauvegardeService;

    private byte[] telecharger() throws Exception {
        return mvc.perform(get("/api/sauvegarde")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private Map<String, List<Map<String, Object>>> tables(byte[] fichier) throws Exception {
        return json.readValue(fichier, SauvegardeDTO.class).tables();
    }

    @Test
    void restaurerRemetLesDonneesSauvegardeesEtPermetDAnnuler() throws Exception {
        byte[] avant = telecharger();
        Map<String, List<Map<String, Object>>> donneesAvant = tables(avant);
        assertThat(donneesAvant.get("transactions")).isNotEmpty();

        // Modifications après la sauvegarde : une suppression et une création.
        long supprimee = ((Number) donneesAvant.get("transactions").get(0).get("id")).longValue();
        mvc.perform(delete("/api/transactions/" + supprimee)).andExpect(status().isNoContent());
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": 1, \"date\": \"2026-11-05\", \"amount\": -3.20, \"payee\": \"Apres sauvegarde\"}"))
                .andExpect(status().isOk());
        Map<String, List<Map<String, Object>>> donneesModifiees = tables(telecharger());

        JsonNode resultat = json.readTree(mvc.perform(multipart("/api/sauvegarde/restauration")
                        .file(new MockMultipartFile("file", "sauvegarde.json", "application/json", avant)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(tables(telecharger())).isEqualTo(donneesAvant);
        assertThat(resultat.path("lignes").path("transactions").asInt()).isEqualTo(donneesAvant.get("transactions").size());

        // Une création après restauration reçoit un id au-delà de tous ceux restaurés.
        long maxId = donneesAvant.get("transactions").stream().mapToLong(l -> ((Number) l.get("id")).longValue()).max().orElse(0);
        JsonNode creee = json.readTree(mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": 1, \"date\": \"2026-11-06\", \"amount\": -1.00, \"payee\": \"Apres restauration\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(creee.path("id").asLong()).isGreaterThan(maxId);

        // Annulation : retour aux données remplacées par la restauration.
        String fichierAvant = resultat.path("sauvegardeAvant").asText();
        assertThat(fichierAvant).startsWith("homebank-avant-restauration-");
        mvc.perform(post("/api/sauvegarde/restauration/annulation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fichier\": \"" + fichierAvant + "\"}"))
                .andExpect(status().isOk());
        assertThat(tables(telecharger())).isEqualTo(donneesModifiees);
    }

    @Test
    void unFichierInvalideEstRefuseSansRienModifier() throws Exception {
        byte[] avant = telecharger();
        byte[] autreJson = "{\"application\": \"autre\", \"version\": 1, \"tables\": {}}".getBytes(StandardCharsets.UTF_8);

        mvc.perform(multipart("/api/sauvegarde/restauration")
                        .file(new MockMultipartFile("file", "x.json", "application/json", autreJson)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sauvegarde/restauration/annulation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fichier\": \"../application.properties\"}"))
                .andExpect(status().isBadRequest());

        assertThat(tables(telecharger())).isEqualTo(tables(avant));
    }

    @Test
    void laSauvegardeAutomatiqueNEstEcriteQuUneFoisParJour() {
        sauvegardeService.sauvegardeAutomatique();
        assertThat(sauvegardeService.sauvegardeAutomatique()).isEmpty();
        assertThat(sauvegardeService.info().nombre()).isPositive();
    }

    @Test
    void uneRestaurationQuiEchoueEnBaseNeModifieRien() throws Exception {
        byte[] avant = telecharger();
        SauvegardeDTO sauvegarde = json.readValue(avant, SauvegardeDTO.class);
        // Transaction rattachée à un compte inexistant : refusée par la clé étrangère.
        Map<String, Object> orpheline = new java.util.LinkedHashMap<>(sauvegarde.tables().get("transactions").get(0));
        orpheline.put("id", 999_999);
        orpheline.put("account_id", 424_242);
        sauvegarde.tables().put("transactions", new java.util.ArrayList<>(sauvegarde.tables().get("transactions")));
        sauvegarde.tables().get("transactions").add(orpheline);

        ResultatRestaurationDTO resultat = null;
        try {
            resultat = sauvegardeService.restaurer(json.writeValueAsBytes(sauvegarde));
        } catch (IllegalArgumentException attendu) {
            assertThat(attendu.getMessage()).contains("n'ont pas été modifiées");
        }
        assertThat(resultat).isNull();
        assertThat(tables(telecharger())).isEqualTo(tables(avant));
    }
}

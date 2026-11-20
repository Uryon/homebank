package com.homebank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Avec spring.jpa.open-in-view=false, aucune session Hibernate ne reste
 * ouverte pendant la requête : un DTO construit hors transaction qui lit une
 * relation chargée à la demande (nom du tiers, compte de la jumelle d'un
 * virement) échouerait en 500 (LazyInitializationException). Volontairement
 * SANS @Transactional sur la classe (une transaction de test garderait la
 * session ouverte et masquerait l'erreur) : les écritures sont réelles, d'où
 * un contexte (et une base H2) reconstruit après ces tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EcrituresSansSessionOuverteTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private JsonNode call(MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response);
    }

    @Test
    void lesEcrituresQuiRenvoientDesTransactionsNOntPasBesoinDeSessionOuverte() throws Exception {
        String payee = "Sans session " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = call(post("/api/transactions"), """
                {"accountId": 1, "date": "2026-11-02", "amount": -40.00, "payee": "%s", "paymentMode": "CREDIT_CARD"}
                """.formatted(payee));
        long id = created.path("id").asLong();
        assertThat(created.path("payee").asText()).isEqualTo(payee);

        assertThat(call(post("/api/transactions/" + id + "/toggle-periodic-expense"), null).path("payee").asText()).isEqualTo(payee);
        assertThat(call(post("/api/transactions/" + id + "/toggle-exceptional"), null).path("payee").asText()).isEqualTo(payee);
        assertThat(call(post("/api/transactions/" + id + "/toggle-validated"), null).path("payee").asText()).isEqualTo(payee);

        JsonNode updated = call(put("/api/transactions/" + id), """
                {"accountId": 1, "date": "2026-11-02", "amount": -50.00, "payee": "%s", "paymentMode": "CREDIT_CARD"}
                """.formatted(payee));
        assertThat(updated.path("amount").decimalValue()).isEqualByComparingTo("-50.00");

        JsonNode parts = call(post("/api/transactions/" + id + "/split"), """
                {"firstAmount": -20.00}
                """);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(1).path("payee").asText()).isEqualTo(payee);

        JsonNode bulk = call(put("/api/transactions/bulk"), """
                {"ids": [%d], "validatedProvided": true, "validated": true}
                """.formatted(id));
        assertThat(bulk.get(0).path("payee").asText()).isEqualTo(payee);

        // Virement : le libellé de chaque côté lit le compte de sa jumelle.
        JsonNode transfer = call(post("/api/transactions/transfer"), """
                {"fromAccountId": 1, "toAccountId": 2, "amount": 15.00, "date": "2026-11-03"}
                """);
        assertThat(transfer).hasSize(2);
        assertThat(transfer.get(0).path("payee").asText()).isNotBlank();

        // Lectures construites par des services (détail d'une case, doublons).
        call(get("/api/reports/cell-transactions?from=2026-11-01&to=2026-11-30"), null);
        assertThat(call(get("/api/transactions/duplicates/count"), null).path("count").isInt()).isTrue();
        call(get("/api/transactions/duplicates"), null);
        call(get("/api/transactions/periodic-suggestions"), null);
    }

    @Test
    void importEtReimportSansSessionOuverte() throws Exception {
        byte[] csv = "date;paymode;info;payee;memo;amount;category;tags\n04/11/2026;6;;IMPORT SANS SESSION;;-9,90;Alimentation:Courses;\n"
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "releve.csv", "text/csv", csv);
        JsonNode first = json.readTree(mvc.perform(multipart("/api/import/csv").file(file).param("accountId", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode second = json.readTree(mvc.perform(multipart("/api/import/csv").file(file).param("accountId", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(first.path("imported").asInt()).isEqualTo(1);
        assertThat(second.path("duplicates").asInt()).isEqualTo(1);
    }
}

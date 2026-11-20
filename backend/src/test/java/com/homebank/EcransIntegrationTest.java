package com.homebank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parcours complet de chaque écran à travers l'API réelle (profil test, H2) :
 * création, modification, actions propres à l'écran, suppression. Vérifie
 * les routes, la lecture des paramètres et des corps JSON et les codes de
 * réponse, que les tests unitaires des services ne voient pas. Chaque test
 * est annulé en fin d'exécution (@Transactional) : données de démo intactes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EcransIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private JsonNode call(MockHttpServletRequestBuilder request, String body, ResultMatcher expected) throws Exception {
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        String response = mvc.perform(request).andExpect(expected).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return response.isEmpty() ? json.nullNode() : json.readTree(response);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(request, body, status().isOk());
    }

    @Test
    void comptes_creationRapprochementAjustementSuppression() throws Exception {
        JsonNode compte = ok(post("/api/accounts"), """
                {"name": "Compte écran", "type": "SAVINGS", "initialBalance": 100.00, "currency": "EUR"}
                """);
        long id = compte.path("id").asLong();

        JsonNode renomme = ok(put("/api/accounts/" + id), """
                {"name": "Compte écran renommé", "type": "SAVINGS", "initialBalance": 100.00, "currency": "EUR"}
                """);
        assertThat(renomme.path("name").asText()).isEqualTo("Compte écran renommé");

        String today = LocalDate.now().toString();
        JsonNode rapprochement = ok(get("/api/accounts/" + id + "/reconciliation")
                .param("date", today).param("statementBalance", "100.00"), null);
        assertThat(rapprochement.path("difference").decimalValue()).isZero();

        JsonNode ajustement = ok(post("/api/accounts/" + id + "/balance-adjustment")
                .param("date", today).param("actualBalance", "130.00"), null);
        assertThat(ajustement.path("amount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(ajustement.path("balanceAdjustment").asBoolean()).isTrue();

        ok(post("/api/accounts/" + id + "/reconciliation/validate").param("date", today), null);
        assertThat(ok(get("/api/accounts/balances"), null).path(String.valueOf(id)).decimalValue()).isEqualByComparingTo("130.00");

        // Un compte qui porte des opérations ne se supprime pas : il s'archive.
        call(delete("/api/accounts/" + id), null, status().is4xxClientError());
    }

    @Test
    void categories_creationBasculesFusionSuppression() throws Exception {
        long parent = ok(post("/api/categories"), """
                {"name": "Écran racine", "type": "EXPENSE"}
                """).path("id").asLong();
        long enfant = ok(post("/api/categories"), """
                {"name": "Écran enfant", "type": "EXPENSE", "parentId": %d}
                """.formatted(parent)).path("id").asLong();
        long autre = ok(post("/api/categories"), """
                {"name": "Écran autre", "type": "EXPENSE", "parentId": %d}
                """.formatted(parent)).path("id").asLong();

        assertThat(ok(get("/api/categories/" + enfant), null).path("parentId").asLong()).isEqualTo(parent);
        assertThat(ok(put("/api/categories/" + enfant), """
                {"name": "Écran enfant modifié", "type": "EXPENSE", "parentId": %d, "icon": "🧪"}
                """.formatted(parent)).path("icon").asText()).isEqualTo("🧪");
        assertThat(ok(post("/api/categories/" + enfant + "/toggle-hidden"), null).path("hidden").asBoolean()).isTrue();
        assertThat(ok(post("/api/categories/" + parent + "/toggle-annualized"), null).path("annualized").asBoolean()).isTrue();

        ok(post("/api/categories/merge"), """
                {"sourceIds": [%d], "targetId": %d}
                """.formatted(autre, enfant));
        call(get("/api/categories/" + autre), null, status().isNotFound());

        call(delete("/api/categories/" + enfant), null, status().isNoContent());
        call(delete("/api/categories/" + parent), null, status().isNoContent());
    }

    @Test
    void tiers_creationFusionCategorisationNettoyageSuppression() throws Exception {
        long carrefour = ok(post("/api/payees"), """
                {"name": "Écran Carrefour", "pattern": "ECRAN CARREFOUR"}
                """).path("id").asLong();
        long doublon = ok(post("/api/payees"), """
                {"name": "Écran Carrefour Market", "pattern": "ECRAN CARREFOUR MKT"}
                """).path("id").asLong();

        assertThat(ok(put("/api/payees/" + carrefour), """
                {"name": "Écran Carrefour", "pattern": "ECRAN CARREFOUR;ECRAN CRF"}
                """).path("pattern").asText()).isEqualTo("ECRAN CARREFOUR;ECRAN CRF");

        JsonNode fusion = ok(post("/api/payees/merge"), """
                {"sourceIds": [%d], "targetId": %d, "pattern": "ECRAN CARREFOUR;ECRAN CARREFOUR MKT"}
                """.formatted(doublon, carrefour));
        assertThat(fusion.path("id").asLong()).isEqualTo(carrefour);

        assertThat(ok(post("/api/payees/auto-categorize"), null).has("payeesUpdated")).isTrue();
        JsonNode apercu = ok(get("/api/payees/computed-label-cleanup"), null);
        assertThat(apercu.isArray()).isTrue();
        JsonNode nettoyage = ok(post("/api/payees/computed-label-cleanup"), "[]");
        call(post("/api/payees/computed-label-cleanup/undo"), "[]", status().is2xxSuccessful());
        assertThat(nettoyage.isObject()).isTrue();

        call(delete("/api/payees/" + carrefour), null, status().isNoContent());
    }

    @Test
    void prets_creationEcheancierModificationSuppression() throws Exception {
        JsonNode pret = ok(post("/api/loans"), """
                {"name": "Prêt écran", "principal": 12000.00, "annualRate": 3.5, "termMonths": 48,
                 "startDate": "2025-01-05", "monthlyPayment": 268.27}
                """);
        long id = pret.path("id").asLong();
        assertThat(pret.path("totalInterest").decimalValue()).isPositive();

        JsonNode echeancier = ok(get("/api/loans/" + id + "/schedule"), null);
        assertThat(echeancier.path("rows")).hasSize(48);

        assertThat(ok(put("/api/loans/" + id), """
                {"name": "Prêt écran renommé", "principal": 12000.00, "annualRate": 3.5, "termMonths": 48,
                 "startDate": "2025-01-05", "monthlyPayment": 268.27, "notes": "voiture"}
                """).path("notes").asText()).isEqualTo("voiture");

        call(delete("/api/loans/" + id), null, status().isNoContent());
        assertThat(ok(get("/api/loans"), null).findValuesAsText("name")).doesNotContain("Prêt écran renommé");
    }

    @Test
    void transactions_actionsDeLaListeEtAnnulations() throws Exception {
        long id = ok(post("/api/transactions"), """
                {"accountId": 1, "date": "2026-11-04", "amount": -60.00, "payee": "Écran supermarché", "validated": false}
                """).path("id").asLong();

        call(post("/api/transactions/ignore-periodic-suggestion"), "[" + id + "]", status().isNoContent());
        call(post("/api/transactions/unignore-periodic-suggestion"), "[" + id + "]", status().isNoContent());
        call(post("/api/transactions/mark-not-duplicate"), "[" + id + "]", status().isNoContent());
        call(post("/api/transactions/unmark-not-duplicate"), "[" + id + "]", status().isNoContent());

        // Division puis annulation : la transaction retrouve son montant d'origine.
        JsonNode parties = ok(post("/api/transactions/" + id + "/split"), """
                {"firstAmount": -25.00}
                """);
        long seconde = parties.get(1).path("id").asLong();
        JsonNode original = ok(get("/api/transactions"), null).findParent("id");
        assertThat(original).isNotNull();
        JsonNode rendue = ok(post("/api/transactions/" + id + "/undo-split"), """
                {"secondPartId": %d, "original": {"accountId": 1, "date": "2026-11-04", "amount": -60.00,
                 "payee": "Écran supermarché", "paymentMode": "NONE", "validated": false}}
                """.formatted(seconde));
        assertThat(rendue.path("amount").decimalValue()).isEqualByComparingTo("-60.00");

        // Suppression groupée puis restauration à l'identique.
        JsonNode avant = ok(get("/api/transactions"), null);
        JsonNode copie = null;
        for (JsonNode t : avant) {
            if (t.path("id").asLong() == id) copie = t;
        }
        assertThat(ok(delete("/api/transactions/bulk"), "[" + id + "]").path("deleted").asInt()).isEqualTo(1);
        JsonNode restaurees = ok(post("/api/transactions/restore"), "[" + json.writeValueAsString(copie) + "]");
        assertThat(restaurees.get(0).path("payee").asText()).isEqualTo("Écran supermarché");
        long restauree = restaurees.get(0).path("id").asLong();

        assertThat(ok(post("/api/transactions/undo-creation"), "[" + restauree + "]").path("deleted").asInt()).isEqualTo(1);
        assertThat(ok(post("/api/transactions/validate-all").param("accountId", "1"), null).has("validated")).isTrue();
        assertThat(ok(delete("/api/transactions/pending").param("accountId", "2"), null).has("deleted")).isTrue();
    }
}

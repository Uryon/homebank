package com.homebank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Application complete demarree sur H2 (profil "test", donnees de demo de
 * TestDataSeeder) : contrairement aux tests unitaires, qui simulent les
 * repositories, verifie les vraies requetes JPQL et le comportement reel
 * d'Hibernate — ce qu'une montee de version de Spring Boot/Hibernate peut
 * casser sans qu'aucun test unitaire ne le voie. Chaque test est annule en
 * fin d'execution (@Transactional) : les donnees de demo restent intactes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApplicationIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void lesEcransDeLectureRepondentTous() throws Exception {
        String[] urls = {
                "/api/accounts", "/api/accounts/balances", "/api/categories", "/api/payees", "/api/loans",
                "/api/transactions", "/api/transactions?accountId=1",
                "/api/transactions?amountOperator=>&amountValue=100", "/api/transactions?amountOperator=<&amountValue=-50",
                "/api/transactions?amountOperator==&amountValue=10", "/api/transactions?accountId=1&amountOperator=>&amountValue=100",
                "/api/transactions/duplicates", "/api/transactions/periodic-suggestions",
                "/api/reports/balance-forecast", "/api/reports/balance-forecast?accountId=1",
                "/api/reports/monthly-savings", "/api/reports/monthly-category-analysis",
                "/api/reports/periodic-expenses", "/api/reports/expenses-by-category?from=2026-01-01&to=2026-12-31"
        };
        for (String url : urls) {
            mvc.perform(get(url)).andExpect(status().isOk());
        }
    }

    /** Solde historique de fin de mois = soldes initiaux + toutes les transactions jusqu'a cette date (requete groupee de ServiceRapport). */
    @Test
    void soldePrevisionnel_historiqueEgaleSoldesInitiauxPlusTransactions() throws Exception {
        BigDecimal initial = BigDecimal.ZERO;
        for (JsonNode account : getJson("/api/accounts")) {
            initial = initial.add(account.path("initialBalance").decimalValue());
        }
        JsonNode transactions = getJson("/api/transactions");

        int checked = 0;
        for (JsonNode point : getJson("/api/reports/balance-forecast").path("points")) {
            if (!"historique".equals(point.path("type").asText())) continue;
            String date = point.path("date").asText();
            BigDecimal expected = initial;
            for (JsonNode t : transactions) {
                if (t.path("date").asText().compareTo(date) <= 0) expected = expected.add(t.path("amount").decimalValue());
            }
            assertThat(point.path("balance").decimalValue()).as("solde au %s", date).isEqualByComparingTo(expected);
            checked++;
        }
        assertThat(checked).isEqualTo(12);
    }

    /** Regle metier (CLAUDE.md) : un tiers saisi en texte libre est cree a la volee, jamais rejete. */
    @Test
    void transactionAvecTiersInconnu_creeLeTiersALaVolee() throws Exception {
        String payeeName = "Tiers inconnu " + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"accountId": 1, "date": "2026-11-01", "amount": -12.34, "payee": "%s", "paymentMode": "CREDIT_CARD"}
                """.formatted(payeeName);

        MvcResult result = mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();

        JsonNode created = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(created.path("payeeId").isNull()).isFalse();
        boolean payeeExists = false;
        for (JsonNode payee : getJson("/api/payees")) {
            payeeExists |= payeeName.equals(payee.path("name").asText());
        }
        assertThat(payeeExists).isTrue();
    }

    @Test
    void importCsv_puisReimport_detecteLeDoublon() throws Exception {
        byte[] csv = "date;paymode;info;payee;memo;amount;category;tags\n03/11/2026;6;;INTEGRATION TEST;;-7,50;;\n"
                .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "releve.csv", "text/csv", csv);

        JsonNode first = json.readTree(mvc.perform(multipart("/api/import/csv").file(file).param("accountId", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode second = json.readTree(mvc.perform(multipart("/api/import/csv").file(file).param("accountId", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(first.path("imported").asInt()).isEqualTo(1);
        assertThat(second.path("imported").asInt()).isZero();
        assertThat(second.path("duplicates").asInt()).isEqualTo(1);
    }
}

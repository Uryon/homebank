package com.homebank.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Demarrage d'entrainement de l'archive CDS (voir PrepareFastStart dans
 * HomeBank.vbs), active par homebank.entrainement-demarrage=true : une fois le
 * serveur demarre, lit les memes donnees qu'une ouverture de l'appli, puis
 * s'arrete. Les classes du serveur web, des requetes et de la serialisation
 * entrent ainsi dans l'archive, en plus de celles du contexte Spring.
 * Uniquement des lectures.
 */
@Component
@ConditionalOnProperty("homebank.entrainement-demarrage")
// Sans valeur : priorite la plus basse, apres tous les autres ApplicationRunner.
@Order
public class EntrainementDemarrage implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EntrainementDemarrage.class);
    private static final int DELAI_MS = 60_000;

    private final ConfigurableApplicationContext context;

    public EntrainementDemarrage(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Adresses lues par le prechargement de l'appli et par ses pages principales. */
    static List<String> adresses(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        String reference = "referenceMonth=" + month.minusMonths(1).atDay(1);
        return List.of(
                "/api/accounts",
                "/api/accounts/balances",
                "/api/categories",
                "/api/payees",
                "/api/transactions",
                "/api/transactions/duplicates",
                "/api/transactions/periodic-suggestions",
                "/api/reports/balance-forecast?" + reference,
                "/api/reports/monthly-savings?months=12&" + reference,
                "/api/reports/expenses-by-category?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth(),
                "/api/reports/monthly-category-analysis?" + reference,
                "/api/reports/periodic-expenses",
                "/api/loans",
                "/api/import/last");
    }

    @Override
    public void run(ApplicationArguments args) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        for (String adresse : adresses(LocalDate.now(ZoneId.systemDefault()))) {
            lire(port, adresse);
        }
        log.info("Entrainement du demarrage termine");
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** Un echec n'empeche pas de produire l'archive : elle contiendra seulement moins de classes. */
    private static void lire(int port, String adresse) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + adresse)
                    .toURL().openConnection();
            connection.setConnectTimeout(DELAI_MS);
            connection.setReadTimeout(DELAI_MS);
            int status = connection.getResponseCode();
            if (status >= 400) {
                log.warn("Entrainement : {} sur {}", status, adresse);
                return;
            }
            try (InputStream body = connection.getInputStream()) {
                body.transferTo(java.io.OutputStream.nullOutputStream());
            }
        } catch (IOException e) {
            log.warn("Entrainement : {} illisible ({})", adresse, e.getMessage());
        }
    }
}

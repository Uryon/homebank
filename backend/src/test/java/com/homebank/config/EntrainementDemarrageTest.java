package com.homebank.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntrainementDemarrageTest {

    @Test
    void adresses_lisentLesDonneesDeLOuvertureAvecDesPeriodesValides() {
        List<String> adresses = EntrainementDemarrage.adresses(LocalDate.of(2026, 2, 15));

        assertThat(adresses).contains(
                "/api/accounts",
                "/api/transactions",
                "/api/reports/balance-forecast?referenceMonth=2026-01-01",
                "/api/reports/expenses-by-category?from=2026-02-01&to=2026-02-28");
    }
}

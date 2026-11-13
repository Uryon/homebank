package com.homebank.config;

import com.homebank.model.Tiers;
import com.homebank.repository.DepotTiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La migration s'execute a CHAQUE demarrage sur la vraie base : elle doit
 * convertir les anciens motifs (regex) au format simplifie, sans jamais
 * toucher un motif deja au nouveau format.
 */
@ExtendWith(MockitoExtension.class)
class PayeePatternMigrationTest {

    @Mock
    private DepotTiers payeeRepository;

    private static Tiers tiers(String pattern) {
        Tiers t = new Tiers();
        t.setName("Tiers");
        t.setPattern(pattern);
        return t;
    }

    /** Lance la migration sur un seul motif et renvoie le motif obtenu. */
    private String migrate(String pattern) {
        Tiers payee = tiers(pattern);
        when(payeeRepository.findAll()).thenReturn(List.of(payee));
        new PayeePatternMigration(payeeRepository).run(null);
        return payee.getPattern();
    }

    @Test
    void convertitLesAnciensMotifsRegexAuFormatSimplifie() {
        assertThat(migrate("(?i)Carrefour")).isEqualTo("Carrefour");
        assertThat(migrate("\\QSUMUP *CAFE\\E")).isEqualTo("SUMUP *CAFE");
        assertThat(migrate("*Carrefour*")).isEqualTo("Carrefour");
        assertThat(migrate("(?i)*Free*|\\QFREE MOBILE\\E")).isEqualTo("Free;FREE MOBILE");
    }

    @Test
    void conserveLesMotifsAvancesAvecUneEtoileAuMilieu() {
        assertThat(migrate("Carrefour*Paris")).isEqualTo("Carrefour*Paris");
        assertThat(migrate("*Carrefour*Paris*")).isEqualTo("*Carrefour*Paris*");
    }

    @Test
    void neSauvegardeRienQuandTousLesMotifsSontDejaAuNouveauFormat() {
        when(payeeRepository.findAll()).thenReturn(List.of(tiers("Carrefour"), tiers("Free;FREE MOBILE"), tiers(null)));

        new PayeePatternMigration(payeeRepository).run(null);

        verify(payeeRepository, never()).saveAll(any());
    }

    @Test
    void estIdempotente_unSecondPassageNeModifiePlusRien() {
        List<Tiers> payees = new ArrayList<>(List.of(tiers("(?i)*Free*|\\QFREE MOBILE\\E"), tiers("*EDF*")));
        when(payeeRepository.findAll()).thenReturn(payees);
        PayeePatternMigration migration = new PayeePatternMigration(payeeRepository);

        migration.run(null);
        List<String> afterFirstRun = payees.stream().map(Tiers::getPattern).toList();
        migration.run(null);

        assertThat(payees).extracting(Tiers::getPattern).containsExactlyElementsOf(afterFirstRun);
        verify(payeeRepository).saveAll(any());
    }
}

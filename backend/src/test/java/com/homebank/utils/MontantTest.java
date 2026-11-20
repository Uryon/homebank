package com.homebank.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MontantTest {

    @Test
    void auCentime_arrondiComme_laBase() {
        assertThat(Montant.auCentime(new BigDecimal("10.005"), "Le montant")).isEqualByComparingTo("10.01");
        assertThat(Montant.auCentime(new BigDecimal("-10.005"), "Le montant")).isEqualByComparingTo("-10.01");
        assertThat(Montant.auCentime(new BigDecimal("150"), "Le montant")).isEqualTo(new BigDecimal("150.00"));
    }

    @Test
    void auCentime_laisseUneValeurAbsenteAuControleDeLAppelant() {
        assertThat(Montant.auCentime(null, "Le montant")).isNull();
    }

    @Test
    void auCentime_refuseUnMontantQueLaColonneNePeutPasContenir() {
        assertThat(Montant.auCentime(new BigDecimal("99999999999999999.99"), "Le montant")).isNotNull();
        assertThatThrownBy(() -> Montant.auCentime(new BigDecimal("100000000000000000"), "Le montant"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Le montant");
        // Notation scientifique : refusee sans developper le million de chiffres.
        assertThatThrownBy(() -> Montant.auCentime(new BigDecimal("-1E999999"), "Le solde"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Le solde");
    }
}

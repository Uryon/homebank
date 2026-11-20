package com.homebank.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodeTest {

    @Test
    void verifierOrdre_refuseUnDebutPosterieurALaFin() {
        assertThatThrownBy(() -> Periode.verifierOrdre(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> Periode.verifierOrdre(null, LocalDate.of(2026, 1, 1))).doesNotThrowAnyException();
    }

    @Test
    void verifierAnnee_refuseUneAnneeTapeeSurDeuxChiffres() {
        assertThatThrownBy(() -> Periode.verifierAnnee(LocalDate.of(26, 11, 17), "La date"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La date doit être comprise entre 1900 et 2099 (année saisie : 26).");
    }

    @Test
    void verifierAnnee_accepteLesBornesEtNull() {
        assertThatCode(() -> Periode.verifierAnnee(LocalDate.of(1900, 1, 1), "La date")).doesNotThrowAnyException();
        assertThatCode(() -> Periode.verifierAnnee(LocalDate.of(2099, 12, 31), "La date")).doesNotThrowAnyException();
        assertThatCode(() -> Periode.verifierAnnee(null, "La date")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Periode.verifierAnnee(LocalDate.of(2100, 1, 1), "La date"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.homebank.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongueurTexteTest {

    @Test
    void verifier_accepteNullEtUnTexteALaLimite() {
        assertThatCode(() -> LongueurTexte.verifier(null, "Le mémo")).doesNotThrowAnyException();
        assertThatCode(() -> LongueurTexte.verifier("x".repeat(LongueurTexte.MAX), "Le mémo")).doesNotThrowAnyException();
    }

    @Test
    void verifier_refuseUnTexteTropLongAvecUnMessageClair() {
        String texte = "x".repeat(LongueurTexte.MAX + 1);

        assertThatThrownBy(() -> LongueurTexte.verifier(texte, "Le mémo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Le mémo ne peut pas dépasser 255 caractères (256 saisis).");
    }

    @Test
    void tronquer_coupeALaLimiteSansEspaceFinal() {
        String texte = "a".repeat(LongueurTexte.MAX - 1) + " suite";

        assertThat(LongueurTexte.tronquer(texte)).isEqualTo("a".repeat(LongueurTexte.MAX - 1));
        assertThat(LongueurTexte.tronquer("court")).isEqualTo("court");
        assertThat(LongueurTexte.tronquer(null)).isNull();
    }

    @Test
    void libelleBancaire_videDevientNullEtLongEstTronqueA500() {
        assertThat(LongueurTexte.libelleBancaire("  ")).isNull();
        assertThat(LongueurTexte.libelleBancaire(" CB ALCEL ")).isEqualTo("CB ALCEL");
        assertThat(LongueurTexte.libelleBancaire("x".repeat(600))).hasSize(LongueurTexte.MAX_LIBELLE_BANCAIRE);
    }
}

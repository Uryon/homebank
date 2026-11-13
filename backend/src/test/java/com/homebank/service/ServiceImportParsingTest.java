package com.homebank.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lecture des montants et dates tels qu'ecrits dans les releves bancaires importes (voir ServiceImport). */
class ServiceImportParsingTest {

    @Test
    void parseAmount_accepteLesFormatsDesRelevesBancaires() {
        assertThat(ServiceImport.parseAmount("-12,50")).isEqualByComparingTo("-12.50");
        assertThat(ServiceImport.parseAmount("1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(ServiceImport.parseAmount("1,234.56")).isEqualByComparingTo("1234.56");
        // Espaces insecables (U+00A0, U+202F) des exports Excel francais
        assertThat(ServiceImport.parseAmount("1\u00A0234,56")).isEqualByComparingTo("1234.56");
        assertThat(ServiceImport.parseAmount("-1\u202F234,56 €")).isEqualByComparingTo("-1234.56");
        assertThat(ServiceImport.parseAmount("\u221245,00")).isEqualByComparingTo("-45.00");
        assertThat(ServiceImport.parseAmount("1'234.50 EUR")).isEqualByComparingTo("1234.50");
    }

    @Test
    void parseAmount_messageExplicitePourUnMontantIllisible() {
        assertThatThrownBy(() -> ServiceImport.parseAmount("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Montant non reconnu : abc");
    }

    @Test
    void parseDate_accepteJourEtMoisSurUnOuDeuxChiffres() {
        assertThat(ServiceImport.parseDate("05/01/2026")).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ServiceImport.parseDate("5/1/2026")).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ServiceImport.parseDate(" 05-01-26 ")).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ServiceImport.parseDate("2026-01-05")).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(ServiceImport.parseDate("05.01.2026")).isEqualTo(LocalDate.of(2026, 1, 5));
        // Format US en dernier recours seulement (13 ne peut pas etre un mois)
        assertThat(ServiceImport.parseDate("01/13/2026")).isEqualTo(LocalDate.of(2026, 1, 13));
    }

    @Test
    void decodeImportedText_litLUtf8EtRetireLaMarqueBomDExcel() {
        byte[] avecBom = "\uFEFFDate;Libellé;Montant".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(ServiceImport.decodeImportedText(avecBom)).isEqualTo("Date;Libellé;Montant");
    }

    @Test
    void decodeImportedText_basculeEnWindows1252PourUnExportBancaireNonUtf8() {
        byte[] windows1252 = "Prélèvement EDF;-45,00 €".getBytes(java.nio.charset.Charset.forName("windows-1252"));

        assertThat(ServiceImport.decodeImportedText(windows1252)).isEqualTo("Prélèvement EDF;-45,00 €");
    }

    @Test
    void splitCsvLine_respecteLePointVirguleEntreGuillemets() {
        assertThat(ServiceImport.splitCsvLine("01/03/2024;\"VIR SEPA ACME; REF 2024-03\";-45,50"))
                .containsExactly("01/03/2024", "VIR SEPA ACME; REF 2024-03", "-45,50");
    }

    @Test
    void splitCsvLine_gereLesGuillemetsDoublesEtLesChampsVides() {
        assertThat(ServiceImport.splitCsvLine("a;\"dit \"\"bonjour\"\"\";;"))
                .containsExactly("a", "dit \"bonjour\"", "", "");
    }
}

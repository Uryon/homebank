package com.homebank.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lecture des montants et dates tels qu'ecrits dans les releves bancaires importes (voir ServiceImport). */
class ServiceImportParsingTest {

    @Test
    void withoutSpreadsheetGuard_retireLApostropheAjouteeParLExport() {
        assertThat(ServiceImport.withoutSpreadsheetGuard("'=HYPERLINK(1)")).isEqualTo("=HYPERLINK(1)");
        assertThat(ServiceImport.withoutSpreadsheetGuard("'-remise")).isEqualTo("-remise");
        assertThat(ServiceImport.withoutSpreadsheetGuard("'  @note")).isEqualTo("  @note");
    }

    @Test
    void withoutSpreadsheetGuard_laisseLesAutresTextesIntacts() {
        assertThat(ServiceImport.withoutSpreadsheetGuard("'Auberge")).isEqualTo("'Auberge");
        assertThat(ServiceImport.withoutSpreadsheetGuard("'")).isEqualTo("'");
        assertThat(ServiceImport.withoutSpreadsheetGuard("=brut")).isEqualTo("=brut");
        assertThat(ServiceImport.withoutSpreadsheetGuard("")).isEmpty();
    }

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
    void parseAmount_lesMilliersSansDecimalesNeSontPasDesCentimes() {
        assertThat(ServiceImport.parseAmount("-1,234")).isEqualByComparingTo("-1234");
        assertThat(ServiceImport.parseAmount("1.234")).isEqualByComparingTo("1234");
        assertThat(ServiceImport.parseAmount("1.234.567")).isEqualByComparingTo("1234567");
        assertThat(ServiceImport.parseAmount("1,234,567")).isEqualByComparingTo("1234567");
        // Partie entiere nulle ou chiffres apres la virgule moins nombreux : decimales.
        assertThat(ServiceImport.parseAmount("0,123")).isEqualByComparingTo("0.12");
        assertThat(ServiceImport.parseAmount("12,5")).isEqualByComparingTo("12.50");
    }

    @Test
    void parseAmount_accepteLesNegatifsComptablesEtLesDevises() {
        assertThat(ServiceImport.parseAmount("12,50-")).isEqualByComparingTo("-12.50");
        assertThat(ServiceImport.parseAmount("(12,50)")).isEqualByComparingTo("-12.50");
        assertThat(ServiceImport.parseAmount("–12,50")).isEqualByComparingTo("-12.50");
        assertThat(ServiceImport.parseAmount("-12,50 eur")).isEqualByComparingTo("-12.50");
        assertThat(ServiceImport.parseAmount("$12.50")).isEqualByComparingTo("12.50");
    }

    @Test
    void parseDate_accepteLesFormatsAnneeDabordEtIgnoreLHeure() {
        LocalDate attendue = LocalDate.of(2026, 5, 1);
        assertThat(ServiceImport.parseDate("2026/05/01")).isEqualTo(attendue);
        assertThat(ServiceImport.parseDate("20260501")).isEqualTo(attendue);
        assertThat(ServiceImport.parseDate("01/05/2026 14:32")).isEqualTo(attendue);
        assertThat(ServiceImport.parseDate("2026-05-01T14:32:00")).isEqualTo(attendue);
    }

    @Test
    void parseAmount_rameneAuCentimeEtRefuseUnMontantDemesure() {
        assertThat(ServiceImport.parseAmount("150")).isEqualTo(new java.math.BigDecimal("150.00"));
        assertThatThrownBy(() -> ServiceImport.parseAmount("1E999999"))
                .isInstanceOf(IllegalArgumentException.class);
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
    void parseDate_accepteLesPointsAvecUneAnneeSurDeuxChiffres() {
        assertThat(ServiceImport.parseDate("05.01.26")).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void parseDate_refuseUnJourInexistantPlutotQueDeLeDeplacer() {
        assertThat(ServiceImport.parseDate("29/02/2024")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThatThrownBy(() -> ServiceImport.parseDate("31/02/2024"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("31/02/2024");
        assertThatThrownBy(() -> ServiceImport.parseDate("02/30/2024"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseDate_accepteLApostropheAvantLAnneeDesQifQuicken() {
        assertThat(ServiceImport.parseDate("15/01'2024")).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(ServiceImport.parseDate("5/1'24")).isEqualTo(LocalDate.of(2024, 1, 5));
        assertThat(ServiceImport.parseDate("1/15'24")).isEqualTo(LocalDate.of(2024, 1, 15));
    }

    @Test
    void decodeImportedText_litLUtf8EtRetireLaMarqueBomDExcel() {
        byte[] avecBom = "\uFEFFDate;Libellé;Montant".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(ServiceImport.decodeImportedText(avecBom)).isEqualTo("Date;Libellé;Montant");
    }

    @Test
    void decodeImportedText_litLUtf16AnnonceParSaMarque() {
        String texte = "Date\tLibellé\tMontant";
        java.io.ByteArrayOutputStream petitBoutiste = new java.io.ByteArrayOutputStream();
        petitBoutiste.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFE});
        petitBoutiste.writeBytes(texte.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        // Le jeu de caracteres UTF_16 de Java ecrit la marque gros-boutiste.
        byte[] grosBoutiste = texte.getBytes(java.nio.charset.StandardCharsets.UTF_16);

        assertThat(ServiceImport.decodeImportedText(petitBoutiste.toByteArray())).isEqualTo(texte);
        assertThat(ServiceImport.decodeImportedText(grosBoutiste)).isEqualTo(texte);
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

    @Test
    void detectSeparator_pointVirguleVirguleOuTabulation() {
        assertThat(ServiceImport.detectSeparator(List.of("", "Date;Libelle;Montant"))).isEqualTo(';');
        assertThat(ServiceImport.detectSeparator(List.of("Date,Libelle,Montant"))).isEqualTo(',');
        assertThat(ServiceImport.detectSeparator(List.of("Date	Libelle	Montant"))).isEqualTo('	');
        // Virgule decimale d'un montant : un ';' l'emporte toujours.
        assertThat(ServiceImport.detectSeparator(List.of("01/03/2024;CARREFOUR;-45,50"))).isEqualTo(';');
        // Virgule a l'interieur de guillemets : ignoree.
        assertThat(ServiceImport.detectSeparator(List.of("\"A, B\""))).isEqualTo(';');
    }

    @Test
    void splitCsvLine_separateurVirgule_respecteLesGuillemets() {
        assertThat(ServiceImport.splitCsvLine("01/03/2024,\"CARREFOUR, PARIS\",\"-45,50\"", ','))
                .containsExactly("01/03/2024", "CARREFOUR, PARIS", "-45,50");
    }

    @Test
    void isQifTransactionSection_reconnaitLesSectionsDOperations() {
        assertThat(ServiceImport.isQifTransactionSection("!Type:Bank")).isTrue();
        assertThat(ServiceImport.isQifTransactionSection("!type:ccard ")).isTrue();
        assertThat(ServiceImport.isQifTransactionSection("!Type:Oth  L")).isTrue();
        assertThat(ServiceImport.isQifTransactionSection("!Type:Cat")).isFalse();
        assertThat(ServiceImport.isQifTransactionSection("!Type:Invst")).isFalse();
        assertThat(ServiceImport.isQifTransactionSection("!Account")).isFalse();
    }

    @Test
    void endOfCsvRecord_champEntreGuillemetsSurDeuxLignes_lesReunit() {
        List<String> lignes = List.of("01-05-2026;0;;Tiers;\"ligne 1", "ligne 2\";-1.00;;", "02-05-2026;0;;Autre;;-2.00;;");

        assertThat(ServiceImport.endOfCsvRecord(lignes, 0, ';')).isEqualTo(1);
        assertThat(ServiceImport.endOfCsvRecord(lignes, 2, ';')).isEqualTo(2);
    }

    @Test
    void endOfCsvRecord_guillemetIsole_nAvalePasLOperationSuivante() {
        List<String> lignes = List.of("01-05-2026;0;;CAFE \"CHEZ;;-1.00;;", "02-05-2026;0;;BAR\" X;;-2.00;;");

        assertThat(ServiceImport.endOfCsvRecord(lignes, 0, ';')).isZero();
    }
}

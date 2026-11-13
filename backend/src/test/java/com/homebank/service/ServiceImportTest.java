package com.homebank.service;

import com.homebank.model.Categorie;
import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifie l'import CSV/QIF (ServiceImport), qui n'etait couvert par aucun
 * test jusqu'ici alors qu'il conditionne l'integrite des donnees saisies par
 * l'utilisateur (dedoublonnage, reconnaissance de plusieurs formats de CSV,
 * resolution tiers/categorie).
 */
@ExtendWith(MockitoExtension.class)
class ServiceImportTest {

    @Mock
    private ServiceCompte accountService;
    @Mock
    private ServiceCategorie categoryService;
    @Mock
    private DepotTransaction transactionRepository;
    @Mock
    private DepotTransactionSupprimee deletedTransactionRepository;
    @Mock
    private ServiceTiers payeeService;
    @Mock
    private ServiceDernierImport dernierImportService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ServiceImport service;
    private final Compte account = new Compte();

    @BeforeEach
    void setUp() {
        service = new ServiceImport(accountService, categoryService, transactionRepository,
                deletedTransactionRepository, payeeService, dernierImportService, transactionManager);
        account.setId(1L);
        when(accountService.findById(1L)).thenReturn(account);
    }

    private static MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "releve.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile qifFile(String content) {
        return new MockMultipartFile("file", "releve.qif", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static Tiers tiers(Long id, String name, Categorie category) {
        Tiers p = new Tiers();
        p.setId(id);
        p.setName(name);
        p.setPattern(name);
        p.setCategory(category);
        return p;
    }

    // --- Compte archive ---

    @Test
    void importCsv_refuseUnCompteArchiveSansRienEnregistrer() {
        account.setName("Ancien livret");
        account.setArchived(true);

        assertThatThrownBy(() -> service.importCsv(csvFile("Date;Montant\n"), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archivé");
        verify(dernierImportService, never()).enregistrerImport();
        verify(transactionRepository, never()).save(any());
    }

    // --- CSV : format HomeBank complet ---

    @Test
    void importCsv_formatHomebank_creeUneTransactionAvecTiersEtCategorieResolus() {
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Carrefour;Courses;-45,50;Alimentation;
                """;
        Categorie categorie = new Categorie();
        categorie.setId(9L);
        Tiers carrefour = tiers(2L, "Carrefour", null);
        when(payeeService.findExisting(eq("Carrefour"), any(), anyList())).thenReturn(Optional.of(carrefour));
        when(categoryService.findOrCreateByName(eq("Alimentation"), any())).thenReturn(categorie);

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isZero();
        assertThat(result.getErrors()).isEmpty();

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();
        assertThat(saved.getDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(saved.getAmount()).isEqualByComparingTo("-45.50");
        assertThat(saved.getPayee()).isEqualTo(carrefour);
        assertThat(saved.getCategory()).isEqualTo(categorie);
        assertThat(saved.isValidated()).isFalse();
    }

    @Test
    void importCsv_sansCategorieDansLeFichier_reprendLaCategorieParDefautDuTiers() {
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Carrefour;;-10,00;;
                """;
        Categorie categorieParDefaut = new Categorie();
        categorieParDefaut.setId(5L);
        Tiers carrefour = tiers(2L, "Carrefour", categorieParDefaut);
        when(payeeService.findExisting(eq("Carrefour"), any(), anyList())).thenReturn(Optional.of(carrefour));

        service.importCsv(csvFile(csv), 1L);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo(categorieParDefaut);
        verifyNoInteractions(categoryService);
    }

    @Test
    void importCsv_ligneEnDoublon_neCreeAucuneTransactionEtEstCompteeCommeDoublon() {
        Transaction existing = new Transaction();
        existing.setDate(LocalDate.of(2024, 3, 1));
        existing.setAmount(new BigDecimal("-45.50"));
        Tiers carrefour = tiers(2L, "Carrefour", null);
        existing.setPayee(carrefour);
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L)).thenReturn(List.of(existing));
        when(payeeService.findExisting(eq("Carrefour"), any(), anyList())).thenReturn(Optional.of(carrefour));

        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Carrefour;;-45,50;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getDuplicates()).isEqualTo(1);
        assertThat(result.getImported()).isZero();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_ligneAvecDateInvalide_estIgnoreeSansInterrompreLImportDesAutresLignes() {
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                date-invalide;;;Carrefour;;-10,00;;
                01/03/2024;;;Monoprix;;-5,00;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        verify(transactionRepository, times(1)).save(any());
    }

    @Test
    void importCsv_ligneAvecColonnesInsuffisantes_estIgnoreeAvecErreur() {
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Carrefour
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isZero();
        assertThat(result.getErrors().get(0)).contains("colonnes insuffisant");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_exportBancaireBrut_reconnaitLeFormatSansMemoNiCategorieEtUtiliseLeLibelleCommeTiers() {
        String csv = """
                Numero Compte;1234567890
                Solde;1000,00
                Date;Libelle;Montant(EUROS)
                01/03/2024;"VIR SEPA CARREFOUR PARIS";-45,50
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        verify(payeeService).findExisting(eq("VIR SEPA CARREFOUR PARIS"), eq(new BigDecimal("-45.50")), anyList());
        verify(payeeService).createFromText(eq("VIR SEPA CARREFOUR PARIS"), anyList());
    }

    @Test
    void importCsv_exportBancaireWindows1252AvecAccents_lesConserveDansLeLibelle() {
        String csv = "Date;Libellé;Montant(EUROS)\n01/03/2024;Prélèvement EDF;-45,50\n";
        MockMultipartFile windows1252 = new MockMultipartFile("file", "releve.csv", "text/csv",
                csv.getBytes(java.nio.charset.Charset.forName("windows-1252")));

        ServiceImport.ImportResult result = service.importCsv(windows1252, 1L);

        assertThat(result.getImported()).isEqualTo(1);
        verify(payeeService).findExisting(eq("Prélèvement EDF"), eq(new BigDecimal("-45.50")), anyList());
    }

    @Test
    void importCsv_exportBancaireAvecMarqueBomExcel_reconnaitLEnTete() {
        // BOM (U+FEFF) en tete : sans le retirer, "Date" n'etait pas reconnu
        // comme en-tete et chaque ligne etait rejetee ("nombre de colonnes insuffisant").
        String csv = "﻿Date;Libelle;Montant(EUROS)\n01/03/2024;CARREFOUR;-12,00\n";

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isZero();
    }

    @Test
    void importCsv_montantAuFormatEuropeenAvecSeparateurDeMilliers_estCorrectementConverti() {
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Salaire;;1.234,56;;
                """;

        service.importCsv(csvFile(csv), 1L);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("1234.56");
    }

    // --- QIF ---

    @Test
    void importQif_enregistrementCompletApresTypeBank_creeUneTransaction() {
        String qif = """
                !Type:Bank
                D01/03/2024
                T-45,50
                PCarrefour
                MCourses
                LAlimentation
                ^
                """;
        Categorie categorie = new Categorie();
        categorie.setId(9L);
        when(categoryService.findOrCreateByName(eq("Alimentation"), any())).thenReturn(categorie);

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();
        assertThat(saved.getDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(saved.getAmount()).isEqualByComparingTo("-45.50");
        assertThat(saved.getMemo()).isEqualTo("Courses");
        assertThat(saved.getCategory()).isEqualTo(categorie);
    }

    @Test
    void importQif_ignoreLesEnregistrementsAvantTypeBank() {
        String qif = """
                !Type:Cat
                NAutreSection
                ^
                !Type:Bank
                D01/03/2024
                T-10,00
                PMonoprix
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        verify(transactionRepository, times(1)).save(any());
    }

    @Test
    void importQif_enregistrementSansDateOuMontant_estIgnoreAvecErreurEtNeBloquePasLaSuite() {
        String qif = """
                !Type:Bank
                PSansDateNiMontant
                ^
                D01/03/2024
                T-5,00
                PMonoprix
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
    }

    /**
     * Meme regle que pour une ligne CSV (voir
     * importCsv_ligneAvecDateInvalide_estIgnoreeSansInterrompreLImportDesAutresLignes) :
     * une date illisible n'ignore que son enregistrement. Avant, l'exception
     * de parseDate remontait hors de importQif et faisait echouer tout
     * l'import (400) alors que les enregistrements precedents etaient deja
     * en base.
     */
    @Test
    void importQif_enregistrementAvecDateIllisible_estIgnoreSansInterrompreLImport() {
        String qif = """
                !Type:Bank
                D01/03/2024
                T-5,00
                PMonoprix
                ^
                D32/13/2024
                T-7,00
                PCarrefour
                ^
                D02/03/2024
                T-9,00
                PLidl
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(2);
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().asString()
                .contains("ligne 9")
                .contains("Format de date non reconnu : 32/13/2024");
        verify(transactionRepository, times(2)).save(any());
    }

    @Test
    void importQif_enregistrementAvecMontantIllisible_estIgnoreSansInterrompreLImport() {
        String qif = """
                !Type:Bank
                D01/03/2024
                Tabc
                PMonoprix
                ^
                D02/03/2024
                T-9,00
                PLidl
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().asString().contains("Montant non reconnu : abc");
    }

    /** Une exception sans message (ex : NullPointerException) ne doit pas s'afficher "Ligne 2 : null". */
    @Test
    void importCsv_erreurInattendueSansMessage_afficheLeTypeDErreurPlutotQueNull() {
        when(payeeService.findExisting(any(), any(), anyList())).thenThrow(new NullPointerException());
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Carrefour;;-10,00;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().asString()
                .doesNotContain("null")
                .contains("NullPointerException");
    }

    @Test
    void importCsv_enregistreLaDateDuDernierImportUneFoisLeCompteResolu() {
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Carrefour;;-10,00;;
                """;

        service.importCsv(csvFile(csv), 1L);

        verify(dernierImportService).enregistrerImport();
    }

    @Test
    void importQif_enregistreLaDateDuDernierImportUneFoisLeCompteResolu() {
        String qif = """
                !Type:Bank
                D01/03/2024
                T-10,00
                PMonoprix
                ^
                """;

        service.importQif(qifFile(qif), 1L);

        verify(dernierImportService).enregistrerImport();
    }

    @Test
    void importQif_doublon_estCompteCommeDoublonSansCreerDeTransaction() {
        Transaction existing = new Transaction();
        existing.setDate(LocalDate.of(2024, 3, 1));
        existing.setAmount(new BigDecimal("-10.00"));
        Tiers monoprix = tiers(3L, "Monoprix", null);
        existing.setPayee(monoprix);
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L)).thenReturn(List.of(existing));
        when(payeeService.findExisting(eq("Monoprix"), any(), anyList())).thenReturn(Optional.of(monoprix));

        String qif = """
                !Type:Bank
                D01/03/2024
                T-10,00
                PMonoprix
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getDuplicates()).isEqualTo(1);
        assertThat(result.getImported()).isZero();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_doublonDUneTransactionSupprimee_neRecreePasSonTiers() {
        // Transaction supprimee depuis (empreinte conservee) : son tiers
        // auto-cree a ete nettoye avec elle, il n'est donc plus connu.
        TransactionSupprimee deleted = new TransactionSupprimee();
        deleted.setAccountId(1L);
        deleted.setDate(LocalDate.of(2024, 3, 1));
        deleted.setAmount(new BigDecimal("-12.00"));
        deleted.setPayeeName("SUMUP CAFE");
        when(deletedTransactionRepository.findByAccountId(1L)).thenReturn(List.of(deleted));

        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;SUMUP CAFE;;-12,00;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getDuplicates()).isEqualTo(1);
        verify(payeeService, never()).createFromText(anyString(), anyList());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_nouveauTiers_estCreeUneFoisLaLigneConfirmeeNonDoublon() {
        Tiers cree = tiers(8L, "Boulangerie Martin", null);
        when(payeeService.createFromText(eq("Boulangerie Martin"), anyList())).thenReturn(cree);

        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Boulangerie Martin;;-3,20;;
                """;

        service.importCsv(csvFile(csv), 1L);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getPayee()).isEqualTo(cree);
    }

    @Test
    void importCsv_echecDEnregistrementDUneLigne_annuleSonTiersCreeEtLeRetireDuCache() {
        Tiers cree = tiers(8L, "Boulangerie Martin", null);
        when(payeeService.createFromText(eq("Boulangerie Martin"), anyList())).thenAnswer(invocation -> {
            List<Tiers> known = invocation.getArgument(1);
            known.add(cree);
            return cree;
        });
        List<Boolean> cacheContenaitLeTiersAnnule = new java.util.ArrayList<>();
        when(payeeService.findExisting(anyString(), any(), anyList())).thenAnswer(invocation -> {
            List<Tiers> known = invocation.getArgument(2);
            cacheContenaitLeTiersAnnule.add(known.contains(cree));
            return Optional.empty();
        });
        when(transactionRepository.save(any())).thenThrow(new IllegalStateException("contrainte")).thenReturn(null);

        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Boulangerie Martin;;-3,20;;
                02/03/2024;;;Boulangerie Martin;;-2,80;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isEqualTo(1);
        verify(transactionManager).rollback(any());
        // 2e ligne : le tiers annule avec la 1re n'est plus propose comme deja connu.
        assertThat(cacheContenaitLeTiersAnnule).containsExactly(false, false);
    }
}

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
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    private ServiceImport service;
    private final Compte account = new Compte();

    @BeforeEach
    void setUp() {
        service = new ServiceImport(accountService, categoryService, transactionRepository,
                deletedTransactionRepository, payeeService);
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

    // --- CSV : format HomeBank complet ---

    @Test
    void importCsv_formatHomebank_creeUneTransactionAvecTiersEtCategorieResolus() {
        String csv = "Date;Paymode;Info;Payee;Memo;Amount;Category;Tags\n"
                + "01/03/2024;;;Carrefour;Courses;-45,50;Alimentation;\n";
        Categorie categorie = new Categorie();
        categorie.setId(9L);
        Tiers carrefour = tiers(2L, "Carrefour", null);
        when(payeeService.resolveOrCreate(eq("Carrefour"), any(), anyList())).thenReturn(carrefour);
        when(categoryService.findOrCreateByName("Alimentation")).thenReturn(categorie);

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
        String csv = "Date;Paymode;Info;Payee;Memo;Amount;Category;Tags\n"
                + "01/03/2024;;;Carrefour;;-10,00;;\n";
        Categorie categorieParDefaut = new Categorie();
        categorieParDefaut.setId(5L);
        Tiers carrefour = tiers(2L, "Carrefour", categorieParDefaut);
        when(payeeService.resolveOrCreate(eq("Carrefour"), any(), anyList())).thenReturn(carrefour);

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
        when(payeeService.resolveOrCreate(eq("Carrefour"), any(), anyList())).thenReturn(carrefour);

        String csv = "Date;Paymode;Info;Payee;Memo;Amount;Category;Tags\n"
                + "01/03/2024;;;Carrefour;;-45,50;;\n";

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getDuplicates()).isEqualTo(1);
        assertThat(result.getImported()).isZero();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_ligneAvecDateInvalide_estIgnoreeSansInterrompreLImportDesAutresLignes() {
        String csv = "Date;Paymode;Info;Payee;Memo;Amount;Category;Tags\n"
                + "date-invalide;;;Carrefour;;-10,00;;\n"
                + "01/03/2024;;;Monoprix;;-5,00;;\n";
        when(payeeService.resolveOrCreate(anyString(), any(), anyList())).thenReturn(null);

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        verify(transactionRepository, times(1)).save(any());
    }

    @Test
    void importCsv_ligneAvecColonnesInsuffisantes_estIgnoreeAvecErreur() {
        String csv = "Date;Paymode;Info;Payee;Memo;Amount;Category;Tags\n"
                + "01/03/2024;;;Carrefour\n";

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isZero();
        assertThat(result.getErrors().get(0)).contains("colonnes insuffisant");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_exportBancaireBrut_reconnaitLeFormatSansMemoNiCategorieEtUtiliseLeLibelleCommeTiers() {
        String csv = "Numero Compte;1234567890\n"
                + "Solde;1000,00\n"
                + "Date;Libelle;Montant(EUROS)\n"
                + "01/03/2024;\"VIR SEPA CARREFOUR PARIS\";-45,50\n";
        when(payeeService.resolveOrCreate(eq("VIR SEPA CARREFOUR PARIS"), any(), anyList())).thenReturn(null);

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        verify(payeeService).resolveOrCreate(eq("VIR SEPA CARREFOUR PARIS"), eq(new BigDecimal("-45.50")), anyList());
    }

    @Test
    void importCsv_montantAuFormatEuropeenAvecSeparateurDeMilliers_estCorrectementConverti() {
        String csv = "Date;Paymode;Info;Payee;Memo;Amount;Category;Tags\n"
                + "01/03/2024;;;Salaire;;1.234,56;;\n";

        service.importCsv(csvFile(csv), 1L);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("1234.56");
    }

    // --- QIF ---

    @Test
    void importQif_enregistrementCompletApresTypeBank_creeUneTransaction() {
        String qif = "!Type:Bank\n"
                + "D01/03/2024\n"
                + "T-45,50\n"
                + "PCarrefour\n"
                + "MCourses\n"
                + "LAlimentation\n"
                + "^\n";
        Categorie categorie = new Categorie();
        categorie.setId(9L);
        when(categoryService.findOrCreateByName("Alimentation")).thenReturn(categorie);

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
        String qif = "!Type:Cat\n"
                + "NAutreSection\n"
                + "^\n"
                + "!Type:Bank\n"
                + "D01/03/2024\n"
                + "T-10,00\n"
                + "PMonoprix\n"
                + "^\n";

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        verify(transactionRepository, times(1)).save(any());
    }

    @Test
    void importQif_enregistrementSansDateOuMontant_estIgnoreAvecErreurEtNeBloquePasLaSuite() {
        String qif = "!Type:Bank\n"
                + "PSansDateNiMontant\n"
                + "^\n"
                + "D01/03/2024\n"
                + "T-5,00\n"
                + "PMonoprix\n"
                + "^\n";

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
    }

    @Test
    void importQif_doublon_estCompteCommeDoublonSansCreerDeTransaction() {
        Transaction existing = new Transaction();
        existing.setDate(LocalDate.of(2024, 3, 1));
        existing.setAmount(new BigDecimal("-10.00"));
        Tiers monoprix = tiers(3L, "Monoprix", null);
        existing.setPayee(monoprix);
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L)).thenReturn(List.of(existing));
        when(payeeService.resolveOrCreate(eq("Monoprix"), any(), anyList())).thenReturn(monoprix);

        String qif = "!Type:Bank\n"
                + "D01/03/2024\n"
                + "T-10,00\n"
                + "PMonoprix\n"
                + "^\n";

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getDuplicates()).isEqualTo(1);
        assertThat(result.getImported()).isZero();
        verify(transactionRepository, never()).save(any());
    }
}

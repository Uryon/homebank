package com.homebank.service;

import com.homebank.model.Categorie;
import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import jakarta.persistence.EntityManager;
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
    @Mock
    private EntityManager entityManager;

    private ServiceImport service;
    private final Compte account = new Compte();

    @BeforeEach
    void setUp() {
        service = new ServiceImport(accountService, categoryService, transactionRepository,
                deletedTransactionRepository, payeeService, dernierImportService, transactionManager, entityManager);
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

    // --- Fichier sans aucune operation ---

    @Test
    void importCsv_fichierVide_expliquePourquoiRienNAEteImporte() {
        ServiceImport.ImportResult result = service.importCsv(csvFile(""), 1L);

        assertThat(result.getImported()).isZero();
        assertThat(result.getErrors()).containsExactly(ServiceImport.NO_OPERATION_FOUND);
        verify(dernierImportService, never()).enregistrerImport();
    }

    @Test
    void importCsv_enTeteSeule_expliquePourquoiRienNAEteImporte() {
        ServiceImport.ImportResult result = service.importCsv(csvFile("Date;Libelle;Montant(EUROS)\n"), 1L);

        assertThat(result.getErrors()).containsExactly(ServiceImport.NO_OPERATION_FOUND);
    }

    @Test
    void importQif_contenuCsv_signaleQueLeFormatNeCorrespondPas() {
        // Erreur de manipulation : format "QIF" choisi pour un releve CSV, qui
        // n'a aucune section !Type:Bank et ne produisait donc aucune ligne.
        String csv = """
                Date;Libelle;Montant(EUROS)
                01/03/2024;CARREFOUR;-45,50
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(csv), 1L);

        assertThat(result.getErrors()).containsExactly(ServiceImport.NO_OPERATION_FOUND);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_lignesToutesEnErreur_neRajoutePasLeMessageDeFichierSansOperation() {
        String csv = """
                Date;Libelle;Montant(EUROS)
                pas-une-date;CARREFOUR;-45,50
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getErrors()).doesNotContain(ServiceImport.NO_OPERATION_FOUND);
    }

    @Test
    void importCsv_separateurVirgule_estReconnu() {
        String csv = """
                Date,Libelle,Montant
                01/03/2024,"CARREFOUR, PARIS","-45,50"
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getImported()).isEqualTo(1);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("-45.50");
    }

    @Test
    void importCsv_colonnesDebitCredit_nePrendJamaisLeSoldePourLeMontant() {
        String csv = """
                Date;Date de valeur;Débit;Crédit;Libellé;Solde
                01/03/2024;01/03/2024;-45,50;;CARREFOUR;1000,00
                02/03/2024;02/03/2024;;1500,00;SALAIRE;2500,00
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        List<Transaction> saved = transactionsEnregistrees(2);
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo("-45.50");
        assertThat(saved.get(0).getBankLabel()).isEqualTo("CARREFOUR");
        assertThat(saved.get(1).getAmount()).isEqualByComparingTo("1500.00");
        assertThat(saved.get(1).getBankLabel()).isEqualTo("SALAIRE");
    }

    /** Colonne de libellé au nom inconnu (« Opération ») : la première colonne de texte sert de libellé. */
    @Test
    void importCsv_libelleAuNomInconnu_prendLaPremiereColonneDeTexte() {
        String csv = """
                Date;Opération;Montant
                01/03/2024;PRLV EDF;-72,40
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        Transaction saved = transactionsEnregistrees(1).get(0);
        assertThat(saved.getBankLabel()).isEqualTo("PRLV EDF");
        assertThat(saved.getAmount()).isEqualByComparingTo("-72.40");
    }

    /** Une seconde colonne de date (« Date de valeur ») n'est jamais prise pour le libellé. */
    @Test
    void importCsv_libelleAuNomInconnu_sauteLesColonnesDeDate() {
        String csv = """
                Date;Date de valeur;Opération;Montant
                01/03/2024;02/03/2024;CB BOULANGERIE;-4,80
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        Transaction saved = transactionsEnregistrees(1).get(0);
        assertThat(saved.getBankLabel()).isEqualTo("CB BOULANGERIE");
        assertThat(saved.getAmount()).isEqualByComparingTo("-4.80");
        assertThat(saved.getDate()).isEqualTo(LocalDate.of(2024, 3, 1));
    }

    /** Aucune colonne nommée « Montant » : repli sur la disposition d'un relevé bancaire (date, libellé, montant). */
    @Test
    void importCsv_sansColonneDeMontantNommee_replieSurLaDispositionDUnReleve() {
        String csv = """
                Date;Libellé;Valeur
                01/03/2024;CARREFOUR;-12,30
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        Transaction saved = transactionsEnregistrees(1).get(0);
        assertThat(saved.getBankLabel()).isEqualTo("CARREFOUR");
        assertThat(saved.getAmount()).isEqualByComparingTo("-12.30");
    }

    @Test
    void importCsv_debitEcritSansSigne_devientUneDepense() {
        String csv = """
                Date;Libellé;Débit euros;Crédit euros;
                01/03/2024;CARREFOUR;45,50;;
                02/03/2024;SALAIRE;;1500,00
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        List<Transaction> saved = transactionsEnregistrees(2);
        assertThat(saved.get(0).getAmount()).isEqualByComparingTo("-45.50");
        assertThat(saved.get(1).getAmount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void importCsv_debitEtCreditVides_ligneEnErreur() {
        String csv = """
                Date;Libellé;Débit;Crédit
                01/03/2024;CARREFOUR;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().asString().contains("Ligne 2").contains("Débit");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_enTeteDetaillee_apresDesInformationsDeCompteSurTroisColonnes() {
        String csv = """
                Compte;FR7612345;EUR
                Date de l'opération;Libellé;Détail de l'écriture;Montant de l'opération;Devise
                01/03/2024;CARTE CARREFOUR;Courses du samedi;-45,50;EUR
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        Transaction saved = transactionsEnregistrees(1).get(0);
        assertThat(saved.getAmount()).isEqualByComparingTo("-45.50");
        assertThat(saved.getBankLabel()).isEqualTo("CARTE CARREFOUR");
        assertThat(saved.getMemo()).isEqualTo("Courses du samedi");
    }

    @Test
    void importCsv_dateDeDebit_nestPasUneColonneDeMontant() {
        String csv = """
                Date;Date de débit;Libellé;Montant
                01/03/2024;05/03/2024;CARREFOUR;-45,50
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getErrors()).isEmpty();
        assertThat(transactionsEnregistrees(1).get(0).getAmount()).isEqualByComparingTo("-45.50");
    }

    private List<Transaction> transactionsEnregistrees(int attendues) {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(attendues)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void importQif_derniereOperationSansCirconflexe_estImportee() {
        String qif = """
                !Type:Bank
                D01/03/2024
                T-45,50
                PCARREFOUR
                ^
                D02/03/2024
                T-12,00
                PBOULANGERIE
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getImported()).isEqualTo(2);
        assertThat(transactionsEnregistrees(2).get(1).getAmount()).isEqualByComparingTo("-12.00");
    }

    @Test
    void importQif_operationNonTermineeAvantUneNouvelleSection_estImportee() {
        String qif = """
                !Type:Bank
                D01/03/2024
                T-45,50
                !Type:CCard
                D02/03/2024
                T-12,00
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getImported()).isEqualTo(2);
    }

    @Test
    void importQif_derniereOperationIncomplete_estSignalee() {
        String qif = """
                !Type:Bank
                D01/03/2024
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().asString().contains("date ou montant manquant");
    }

    @Test
    void importQif_compteEntreCrochets_neCreePasDeCategorie() {
        String qif = """
                !Type:Bank
                D01/03/2024
                T-45,50
                L[Livret A]
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        verify(categoryService, never()).findOrCreateByName(any(), any());
        assertThat(transactionsEnregistrees(1).get(0).getCategory()).isNull();
    }

    @Test
    void importCsv_chercheChaqueCategorieUneSeuleFois() {
        when(categoryService.findOrCreateByName(any(), any())).thenReturn(new Categorie());
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;A;;-1,00;Courses:Bio;
                02/03/2024;;;B;;-2,00;courses:bio;
                03/03/2024;;;C;;-3,00;Courses:Bio;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isEqualTo(3);
        verify(categoryService, times(1)).findOrCreateByName(any(), any());
    }

    @Test
    void importCsv_grosFichier_valideParLots() {
        StringBuilder csv = new StringBuilder("Date;Libelle;Montant\n");
        for (int i = 0; i < 450; i++) csv.append("01/03/2024;ACHAT ").append(i).append(";-1,00\n");

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv.toString()), 1L);

        assertThat(result.getImported()).isEqualTo(450);
        verify(transactionManager, times(3)).commit(any());
    }

    @Test
    void importCsv_lotEnEchec_neCompteRienEnDoubleEtGardeLesAutresLignes() {
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getAmount().compareTo(new BigDecimal("-2.00")) == 0) throw new IllegalStateException("contrainte");
            t.setId(t.getAmount().abs().longValue());
            return t;
        });
        String csv = """
                Date;Libelle;Montant
                01/03/2024;A;-1,00
                pas-une-date;B;-9,00
                02/03/2024;C;-2,00
                03/03/2024;D;-3,00
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isEqualTo(2);
        assertThat(result.getSkipped()).isEqualTo(2);
        assertThat(result.getImportedIds()).containsExactly(1L, 3L);
        assertThat(result.getErrors()).hasSize(2);
        assertThat(result.getErrors().get(0)).startsWith("Ligne 3 : ");
        assertThat(result.getErrors().get(1)).startsWith("Ligne 4 : ").contains("contrainte");
    }

    @Test
    void importCsv_renvoieLesIdsDesTransactionsCreees() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(42L);
            return t;
        });

        ServiceImport.ImportResult result = service.importCsv(csvFile("Date;Libelle;Montant\n01/03/2024;CARREFOUR;-45,50\n"), 1L);

        assertThat(result.getImportedIds()).containsExactly(42L);
    }

    @Test
    void importCsv_memoTropLong_estTronquePlutotQueRejete() {
        String csv = "Date;Paymode;Info;Payee;Memo;Amount;Category;Tags\n"
                + "01/03/2024;;;;" + "m".repeat(300) + ";-45,50;;\n";

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getMemo()).hasSize(255);
    }

    @Test
    void importCsv_anneeHorsPlage_ligneEnErreurAvecLaDateFautive() {
        String csv = """
                Date;Libelle;Montant(EUROS)
                17/11/0026;CARREFOUR;-45,50
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(ServiceImport.parseDate("17/11/26")).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().asString()
                .contains("17/11/0026").contains("année saisie : 26");
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
        assertThat(saved.getBankLabel()).isEqualTo("Carrefour");
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

    // --- Doublons : comptage des occurrences (deux operations identiques d'un meme releve) ---

    private static final String DEUX_CAFES_CSV = """
            Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
            01/03/2024;;;Café du coin;;-2,50;;
            01/03/2024;;;Café du coin;;-2,50;;
            """;

    private Tiers cafeConnu() {
        Tiers cafe = tiers(3L, "Café du coin", null);
        when(payeeService.findExisting(eq("Café du coin"), any(), anyList())).thenReturn(Optional.of(cafe));
        return cafe;
    }

    private static Transaction cafeEnBase(Tiers cafe) {
        Transaction t = new Transaction();
        t.setDate(LocalDate.of(2024, 3, 1));
        t.setAmount(new BigDecimal("-2.50"));
        t.setPayee(cafe);
        return t;
    }

    @Test
    void importCsv_operationDontLeTiersAEteChangeDepuis_nEstPasReimportee() {
        Tiers cafe = cafeConnu();
        Transaction reaffectee = cafeEnBase(tiers(8L, "Boulangerie", null));
        reaffectee.setBankLabel("Café du coin");
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L)).thenReturn(List.of(reaffectee));
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Café du coin;;-2,50;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(cafe.getName()).isEqualTo("Café du coin");
        assertThat(result.getImported()).isZero();
        assertThat(result.getDuplicates()).isEqualTo(1);
    }

    @Test
    void importCsv_operationDiviseeDepuis_nEstPasReimportee() {
        Tiers cafe = cafeConnu();
        Transaction premierePartie = cafeEnBase(cafe);
        premierePartie.setAmount(new BigDecimal("-1.00"));
        premierePartie.setSplitFromAmount(new BigDecimal("-2.50"));
        Transaction secondePartie = cafeEnBase(cafe);
        secondePartie.setAmount(new BigDecimal("-1.50"));
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L))
                .thenReturn(List.of(premierePartie, secondePartie));
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Café du coin;;-2,50;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getImported()).isZero();
        assertThat(result.getDuplicates()).isEqualTo(1);
    }

    @Test
    void importCsv_deuxOperationsIdentiquesDuMemeReleve_sontToutesDeuxImportees() {
        cafeConnu();

        ServiceImport.ImportResult result = service.importCsv(csvFile(DEUX_CAFES_CSV), 1L);

        assertThat(result.getImported()).isEqualTo(2);
        assertThat(result.getDuplicates()).isZero();
        verify(transactionRepository, times(2)).save(any());
    }

    @Test
    void importCsv_exportReimporte_reconnaitPartieDiviseeVirementEtAjustement() {
        Compte compte = new Compte();
        compte.setId(1L);
        when(accountService.findById(1L)).thenReturn(compte);
        Tiers cafe = new Tiers();
        cafe.setId(5L);
        cafe.setName("Cafe");
        cafe.setPattern("Cafe");
        when(payeeService.findAllForMatching()).thenReturn(new java.util.ArrayList<>(List.of(cafe)));
        when(payeeService.findExisting(any(), any(), any())).thenAnswer(inv ->
                "Cafe".equals(inv.getArgument(0)) ? Optional.of(cafe) : Optional.empty());

        Transaction partie = new Transaction();
        partie.setDate(LocalDate.of(2024, 3, 1));
        partie.setAmount(new BigDecimal("-4.00"));
        partie.setSplitFromAmount(new BigDecimal("-10.00"));
        partie.setPayee(cafe);
        Compte autre = new Compte();
        autre.setName("Livret");
        Transaction jumelle = new Transaction();
        jumelle.setAccount(autre);
        Transaction virement = new Transaction();
        virement.setDate(LocalDate.of(2024, 3, 2));
        virement.setAmount(new BigDecimal("-7.00"));
        virement.setPaymentMode(com.homebank.utils.ModePaiement.TRANSFER);
        virement.setTransferTransaction(jumelle);
        Transaction ajustement = new Transaction();
        ajustement.setDate(LocalDate.of(2024, 3, 3));
        ajustement.setAmount(new BigDecimal("5.00"));
        ajustement.setBalanceAdjustment(true);
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L)).thenReturn(List.of(partie, virement, ajustement));

        String csv = """
                date;paymode;info;payee;memo;amount;category;tags
                01-03-2024;0;;Cafe;;-4.00;;
                02-03-2024;5;;Virement vers Livret;;-7.00;;
                03-03-2024;0;;Ajustement de solde;;5.00;;
                """;
        ServiceImport.ImportResult result = service.importCsv(
                new MockMultipartFile("file", "x.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)), 1L);

        assertThat(result.getImported()).isZero();
        assertThat(result.getDuplicates()).isEqualTo(3);
    }

    @Test
    void importCsv_reimportDuMemeReleve_reconnaitLesDeuxOccurrencesCommeDoublons() {
        Tiers cafe = cafeConnu();
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L))
                .thenReturn(List.of(cafeEnBase(cafe), cafeEnBase(cafe)));

        ServiceImport.ImportResult result = service.importCsv(csvFile(DEUX_CAFES_CSV), 1L);

        assertThat(result.getImported()).isZero();
        assertThat(result.getDuplicates()).isEqualTo(2);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_releveQuiChevauche_nImporteQueLOccurrenceManquante() {
        Tiers cafe = cafeConnu();
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L)).thenReturn(List.of(cafeEnBase(cafe)));

        ServiceImport.ImportResult result = service.importCsv(csvFile(DEUX_CAFES_CSV), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getDuplicates()).isEqualTo(1);
        verify(transactionRepository, times(1)).save(any());
    }

    @Test
    void importCsv_occurrenceSupprimeeVolontairement_neRevientPasAuReimport() {
        Tiers cafe = cafeConnu();
        TransactionSupprimee supprimee = new TransactionSupprimee();
        supprimee.setDate(LocalDate.of(2024, 3, 1));
        supprimee.setAmount(new BigDecimal("-2.50"));
        supprimee.setPayeeName("Café du coin");
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L)).thenReturn(List.of(cafeEnBase(cafe)));
        when(deletedTransactionRepository.findByAccountId(1L)).thenReturn(List.of(supprimee));

        ServiceImport.ImportResult result = service.importCsv(csvFile(DEUX_CAFES_CSV), 1L);

        assertThat(result.getImported()).isZero();
        assertThat(result.getDuplicates()).isEqualTo(2);
        verify(transactionRepository, never()).save(any());
        // Seule la seconde occurrence correspond a la suppression : signalee a part.
        assertThat(result.getPreviouslyDeleted()).containsExactly(
                new ServiceImport.LigneDejaSupprimee(LocalDate.of(2024, 3, 1), new BigDecimal("-2.50"), "Café du coin"));
    }

    @Test
    void importCsv_doublonDUneTransactionEncoreEnBase_nEstPasSignaleCommeSupprime() {
        Tiers cafe = cafeConnu();
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L))
                .thenReturn(List.of(cafeEnBase(cafe), cafeEnBase(cafe)));

        ServiceImport.ImportResult result = service.importCsv(csvFile(DEUX_CAFES_CSV), 1L);

        assertThat(result.getDuplicates()).isEqualTo(2);
        assertThat(result.getPreviouslyDeleted()).isEmpty();
    }

    @Test
    void importQif_deuxOperationsIdentiquesDuMemeReleve_sontToutesDeuxImportees() {
        cafeConnu();
        String qif = """
                !Type:Bank
                D01/03/2024
                T-2,50
                PCafé du coin
                ^
                D01/03/2024
                T-2,50
                PCafé du coin
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(2);
        assertThat(result.getDuplicates()).isZero();
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
        verify(payeeService).createFromBankLabel(eq("VIR SEPA CARREFOUR PARIS"), anyList());
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
    void importQif_sectionCarteDeCredit_estImportee() {
        String qif = """
                !Type:CCard
                D01/03/2024
                T-12,00
                PCafe
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getImported()).isEqualTo(1);
    }

    @Test
    void importQif_sectionDeCategories_estIgnoree() {
        String qif = """
                !Type:Cat
                NAlimentation
                E
                ^
                !Type:Bank
                D01/03/2024
                T-12,00
                ^
                """;

        ServiceImport.ImportResult result = service.importQif(qifFile(qif), 1L);

        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getSkipped()).isZero();
    }

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
    void importCsv_sansAucuneLigneLisible_nEnregistrePasDeDateDeDernierImport() {
        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                pas une date;;;Carrefour;;-10,00;;
                """;

        service.importCsv(csvFile(csv), 1L);

        verify(dernierImportService, never()).enregistrerImport();
    }

    @Test
    void importCsv_releveDejaImporte_enregistreQuandMemeLaDateDeDernierImport() {
        Tiers cafe = cafeConnu();
        when(transactionRepository.findByAccount_IdOrderByDateDescIdDesc(1L))
                .thenReturn(List.of(cafeEnBase(cafe), cafeEnBase(cafe)));

        service.importCsv(csvFile(DEUX_CAFES_CSV), 1L);

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
        verify(payeeService, never()).createFromBankLabel(anyString(), anyList());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void importCsv_nouveauTiers_estCreeUneFoisLaLigneConfirmeeNonDoublon() {
        Tiers cree = tiers(8L, "Boulangerie Martin", null);
        when(payeeService.createFromBankLabel(eq("Boulangerie Martin"), anyList())).thenReturn(cree);

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
        when(payeeService.createFromBankLabel(eq("Boulangerie Martin"), anyList())).thenAnswer(invocation -> {
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
        // La 1re ligne echoue a chaque tentative : dans son lot, puis rejouee seule.
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getAmount().compareTo(new BigDecimal("-3.20")) == 0) throw new IllegalStateException("contrainte");
            return t;
        });

        String csv = """
                Date;Paymode;Info;Payee;Memo;Amount;Category;Tags
                01/03/2024;;;Boulangerie Martin;;-3,20;;
                02/03/2024;;;Boulangerie Martin;;-2,80;;
                """;

        ServiceImport.ImportResult result = service.importCsv(csvFile(csv), 1L);

        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getImported()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().asString().startsWith("Ligne 2 : ");
        verify(transactionManager, times(2)).rollback(any()); // le lot, puis la ligne fautive
        // Le tiers annule n'est jamais propose comme deja connu aux lignes suivantes.
        assertThat(cacheContenaitLeTiersAnnule).containsOnly(false);
    }
}

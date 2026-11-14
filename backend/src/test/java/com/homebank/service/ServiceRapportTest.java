package com.homebank.service;

import com.homebank.dto.AnalyseMensuelleDTO;
import com.homebank.dto.DepenseParCategorieDTO;
import com.homebank.dto.EpargneMensuelleDTO;
import com.homebank.dto.LigneAnalyseDTO;
import com.homebank.dto.MoisAnalyseDTO;
import com.homebank.dto.PointSoldeDTO;
import com.homebank.dto.PrevisionSoldeDTO;
import com.homebank.dto.ResumeMoisAnalyseDTO;
import com.homebank.dto.ResumePeriodeDTO;
import com.homebank.dto.SynthesePeriodiqueDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Categorie;
import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotCompte;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.ModePaiement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie ServiceRapport, le plus gros morceau non teste jusqu'ici :
 * l'attribution des transactions (attributedTransactions, exercee via les
 * methodes publiques), le lissage des categories "annuelles" sur 12 mois, et
 * les filtres du detail par cellule (cellTransactions).
 */
@ExtendWith(MockitoExtension.class)
class ServiceRapportTest {

    @Mock
    private DepotTransaction transactionRepository;
    @Mock
    private DepotCompte accountRepository;

    private ServiceRapport service;

    @BeforeEach
    void setUp() {
        service = new ServiceRapport(transactionRepository, accountRepository, new MappeurTransaction());
        // Par defaut, aucune transaction annualisee : seuls les tests qui passent par
        // attributedTransactions l'utilisent (lenient : periodicExpensesSummary/balanceForecast ne l'appellent pas).
        lenient().when(transactionRepository.findByCategory_AnnualizedTrue()).thenReturn(List.of());
    }

    private static Compte compte(Long id, String amount) {
        Compte c = new Compte();
        c.setId(id);
        c.setInitialBalance(new BigDecimal(amount));
        return c;
    }

    private static Categorie categorie(Long id, String name, Categorie parent, boolean annualized) {
        Categorie c = new Categorie();
        c.setId(id);
        c.setName(name);
        c.setParent(parent);
        c.setAnnualized(annualized);
        return c;
    }

    /**
     * Simule findByDateBetween comme la vraie requete (transactions dont la
     * date est dans [from, to]) plutot que mois par mois : le service peut
     * ainsi charger un mois ou toute une periode d'un coup.
     */
    private void stubTransactionsBetween(Transaction... all) {
        when(transactionRepository.findByDateBetween(any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(0);
            LocalDate to = inv.getArgument(1);
            return Arrays.stream(all).filter(t -> !t.getDate().isBefore(from) && !t.getDate().isAfter(to)).toList();
        });
    }

    private static Transaction transaction(Long id, Compte account, LocalDate date, String amount, Categorie category) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setCategory(category);
        t.setPaymentMode(ModePaiement.NONE);
        return t;
    }

    /** Fait de t un cote de virement interne : liee a sa jumelle de montant oppose sur un autre compte. */
    private static void lierAUnAutreCompte(Transaction t) {
        Transaction jumelle = transaction(t.getId() + 1000, compte(99L, "0"), t.getDate(),
                t.getAmount().negate().toPlainString(), null);
        t.setPaymentMode(ModePaiement.TRANSFER);
        jumelle.setPaymentMode(ModePaiement.TRANSFER);
        t.setTransferTransaction(jumelle);
        jumelle.setTransferTransaction(t);
    }

    // --- attributedTransactions (exclusions), exercee via expensesByCategory ---

    @Test
    void expensesByCategory_ignoreLesAjustementsDeSolde() {
        Compte account = compte(1L, "0");
        Transaction ajustement = transaction(1L, account, LocalDate.of(2026, 1, 5), "-120.00", null);
        ajustement.setBalanceAdjustment(true);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(ajustement));

        assertThat(service.expensesByCategory(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null)).isEmpty();
    }

    /** « Virement » comme simple mode de paiement (loyer payé par virement) : une vraie dépense, comptée. */
    @Test
    void expensesByCategory_paiementParVirementNonLie_estCompteCommeUneDepense() {
        Compte account = compte(1L, "0");
        Categorie logement = categorie(1L, "Logement", null, false);
        Transaction loyer = transaction(1L, account, LocalDate.of(2026, 1, 5), "-800.00", logement);
        loyer.setPaymentMode(ModePaiement.TRANSFER);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(loyer));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);

        assertThat(result).singleElement().extracting(DepenseParCategorieDTO::total)
                .isEqualTo(new BigDecimal("800.00"));
    }

    @Test
    void expensesByCategory_excluLesVirementsLesTransactionsFuturesEtLesExceptionnelles() {
        Compte account = compte(1L, "0");
        Categorie courses = categorie(1L, "Courses", null, false);
        Transaction depenseValide = transaction(1L, account, LocalDate.of(2026, 1, 10), "-50.00", courses);
        Transaction virement = transaction(2L, account, LocalDate.of(2026, 1, 10), "-30.00", courses);
        lierAUnAutreCompte(virement);
        Transaction future = transaction(3L, account, LocalDate.now().plusDays(5), "-20.00", courses);
        Transaction exceptionnelle = transaction(4L, account, LocalDate.of(2026, 1, 15), "-999.00", courses);
        exceptionnelle.setExceptional(true);
        when(transactionRepository.findByDateBetween(any(), any()))
                .thenReturn(List.of(depenseValide, virement, future, exceptionnelle));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).total()).isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    void expensesByCategory_neGardeQueLesDepensesEtIgnoreLesRevenus() {
        Compte account = compte(1L, "0");
        Categorie salaire = categorie(1L, "Salaire", null, false);
        Transaction revenu = transaction(1L, account, LocalDate.of(2026, 1, 5), "2000.00", salaire);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(revenu));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result).isEmpty();
    }

    @Test
    void expensesByCategory_regroupeSousCategorieCommeParentDeuxPointsEnfant() {
        Compte account = compte(1L, "0");
        Categorie alimentation = categorie(1L, "Alimentation", null, false);
        Categorie restaurant = categorie(2L, "Restaurant", alimentation, false);
        Transaction t = transaction(1L, account, LocalDate.of(2026, 1, 5), "-25.00", restaurant);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(t));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result.get(0).category()).isEqualTo("Alimentation : Restaurant");
        assertThat(result.get(0).categoryId()).isEqualTo(2L);
        assertThat(result.get(0).uncategorized()).isFalse();
    }

    @Test
    void expensesByCategory_transactionSansCategorie_apparaitCommeNonCategorise() {
        Compte account = compte(1L, "0");
        Transaction t = transaction(1L, account, LocalDate.of(2026, 1, 5), "-25.00", null);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(t));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result.get(0).category()).isEqualTo("Non catégorisé");
        assertThat(result.get(0).uncategorized()).isTrue();
        assertThat(result.get(0).categoryId()).isNull();
    }

    @Test
    void expensesByCategory_calculeLePourcentageDeChaqueCategorieSurLeTotalEtTrieParMontantDecroissant() {
        Compte account = compte(1L, "0");
        Categorie courses = categorie(1L, "Courses", null, false);
        Categorie loisirs = categorie(2L, "Loisirs", null, false);
        Transaction t1 = transaction(1L, account, LocalDate.of(2026, 1, 5), "-60.00", courses);
        Transaction t2 = transaction(2L, account, LocalDate.of(2026, 1, 6), "-40.00", loisirs);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(t1, t2));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).category()).isEqualTo("Courses");
        assertThat(result.get(0).percentage()).isEqualTo(new BigDecimal("60.0"));
        assertThat(result.get(1).category()).isEqualTo("Loisirs");
        assertThat(result.get(1).percentage()).isEqualTo(new BigDecimal("40.0"));
    }

    // --- attributedTransactions : lissage des categories "annuelles" sur 12 mois ---

    @Test
    void attributedTransactions_lisseUneTransactionAnnualiseeSur12MoisAuLieuDeSonMontantPlein() {
        Compte account = compte(1L, "0");
        Categorie taxeFonciere = categorie(1L, "Taxe fonciere", null, true);
        Transaction annuelle = transaction(1L, account, LocalDate.of(2025, 10, 15), "-1200.00", taxeFonciere);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());
        when(transactionRepository.findByCategory_AnnualizedTrue()).thenReturn(List.of(annuelle));

        // Fenetre de 3 mois (nov 2025 a jan 2026) a l'interieur de la fenetre glissante
        // de 12 mois demarree en oct 2025 : chaque mois compte pour 1/12 du montant.
        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).total()).isEqualTo(new BigDecimal("300.00")); // 3 * (1200/12)
    }

    @Test
    void attributedTransactions_neCompteEnUneFoisJamaisLeMontantPleinDuneCategorieAnnuelleSurSonMoisDePaiement() {
        Compte account = compte(1L, "0");
        Categorie taxeFonciere = categorie(1L, "Taxe fonciere", null, true);
        Transaction annuelle = transaction(1L, account, LocalDate.of(2025, 10, 15), "-1200.00", taxeFonciere);
        // Meme si findByDateBetween la renvoie (sa date reelle tombe dans la periode),
        // hasAnnualizedCategory doit l'exclure de la boucle "normale".
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(annuelle));
        when(transactionRepository.findByCategory_AnnualizedTrue()).thenReturn(List.of(annuelle));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 31), null);

        assertThat(result.get(0).total()).isEqualTo(new BigDecimal("100.00")); // 1200/12, jamais 1200
    }

    @Test
    void annualizedShare_lesDouzePartsRedonnentExactementLeMontant() {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < 12; i++) total = total.add(ServiceRapport.annualizedShare(new BigDecimal("-100.00"), i));

        assertThat(total).isEqualByComparingTo("-100.00");
        assertThat(ServiceRapport.annualizedShare(new BigDecimal("-100.00"), 0)).isEqualByComparingTo("-8.33");
        assertThat(ServiceRapport.annualizedShare(new BigDecimal("-100.00"), 11)).isEqualByComparingTo("-8.37");
    }

    @Test
    void attributedTransactions_excludLaTransactionAnnualiseeSiSonCompteNeCorrespondPasAuFiltre() {
        Compte compte1 = compte(1L, "0");
        Compte compte2 = compte(2L, "0");
        Categorie taxeFonciere = categorie(1L, "Taxe fonciere", null, true);
        Transaction annuelle = transaction(1L, compte1, LocalDate.of(2025, 10, 15), "-1200.00", taxeFonciere);
        when(transactionRepository.findByAccountIdAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(transactionRepository.findByCategory_AnnualizedTrue()).thenReturn(List.of(annuelle));

        List<DepenseParCategorieDTO> result = service.expensesByCategory(
                LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 30), compte2.getId());

        assertThat(result).isEmpty();
    }

    // --- summary ---

    @Test
    void summary_calculeRevenusDepensesEtNetEnExcluantLesVirements() {
        Compte account = compte(1L, "0");
        Transaction revenu = transaction(1L, account, LocalDate.of(2026, 1, 5), "2000.00", null);
        Transaction depense = transaction(2L, account, LocalDate.of(2026, 1, 10), "-500.00", null);
        Transaction virement = transaction(3L, account, LocalDate.of(2026, 1, 12), "-300.00", null);
        lierAUnAutreCompte(virement);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(revenu, depense, virement));

        ResumePeriodeDTO result = service.summary(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result.income()).isEqualTo(new BigDecimal("2000.00"));
        assertThat(result.expenses()).isEqualTo(new BigDecimal("500.00"));
        assertThat(result.net()).isEqualTo(new BigDecimal("1500.00"));
    }

    // --- cellTransactions : le detail doit correspondre exactement aux agregats ---

    @Test
    void cellTransactions_filtreParFlowIncomeOuExpense() {
        Compte account = compte(1L, "0");
        Transaction revenu = transaction(1L, account, LocalDate.of(2026, 1, 5), "2000.00", null);
        Transaction depense = transaction(2L, account, LocalDate.of(2026, 1, 10), "-500.00", null);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(revenu, depense));

        List<TransactionDTO> expenses = service.cellTransactions(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, "expense", null, null, false);

        assertThat(expenses).hasSize(1);
        assertThat(expenses.get(0).getAmount()).isEqualByComparingTo("-500.00");
    }

    @Test
    void cellTransactions_filtreParCategorieExacte() {
        Compte account = compte(1L, "0");
        Categorie courses = categorie(1L, "Courses", null, false);
        Categorie loisirs = categorie(2L, "Loisirs", null, false);
        Transaction t1 = transaction(1L, account, LocalDate.of(2026, 1, 5), "-25.00", courses);
        Transaction t2 = transaction(2L, account, LocalDate.of(2026, 1, 6), "-15.00", loisirs);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(t1, t2));

        List<TransactionDTO> result = service.cellTransactions(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null, 1L, null, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategoryId()).isEqualTo(1L);
    }

    @Test
    void cellTransactions_filtreParCategorieRacineIncluLaRacineEtSesEnfants() {
        Compte account = compte(1L, "0");
        Categorie alimentation = categorie(1L, "Alimentation", null, false);
        Categorie restaurant = categorie(2L, "Restaurant", alimentation, false);
        Categorie loisirs = categorie(3L, "Loisirs", null, false);
        Transaction surRacine = transaction(1L, account, LocalDate.of(2026, 1, 5), "-10.00", alimentation);
        Transaction surEnfant = transaction(2L, account, LocalDate.of(2026, 1, 6), "-20.00", restaurant);
        Transaction ailleurs = transaction(3L, account, LocalDate.of(2026, 1, 7), "-30.00", loisirs);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(surRacine, surEnfant, ailleurs));

        List<TransactionDTO> result = service.cellTransactions(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null, null, 1L, false);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TransactionDTO::getId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void cellTransactions_filtreLesTransactionsNonCategorisees() {
        Compte account = compte(1L, "0");
        Categorie courses = categorie(1L, "Courses", null, false);
        Transaction categorisee = transaction(1L, account, LocalDate.of(2026, 1, 5), "-10.00", courses);
        Transaction sansCategorie = transaction(2L, account, LocalDate.of(2026, 1, 6), "-20.00", null);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(categorisee, sansCategorie));

        List<TransactionDTO> result = service.cellTransactions(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, null, null, null, true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(2L);
    }

    @Test
    void cellTransactions_pourUneTrancheAnnualisee_afficheLeMontantEtLeMoisDeLaTrancheEtNonCeuxDeLaTransactionSource() {
        Compte account = compte(1L, "0");
        Categorie taxeFonciere = categorie(1L, "Taxe fonciere", null, true);
        Transaction annuelle = transaction(1L, account, LocalDate.of(2025, 10, 15), "-1200.00", taxeFonciere);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());
        when(transactionRepository.findByCategory_AnnualizedTrue()).thenReturn(List.of(annuelle));

        List<TransactionDTO> result = service.cellTransactions(
                LocalDate.of(2025, 11, 1), LocalDate.of(2025, 11, 30), null, "expense", null, null, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("-100.00");
        assertThat(result.get(0).getDate()).isEqualTo(LocalDate.of(2025, 11, 1));
    }

    // --- monthlySavings ---

    @Test
    void monthlySavings_produitUnPointParMoisAncreSurReferenceMonth() {
        Compte account = compte(1L, "0");
        // referenceMonth = mars 2026 -> ancre = avril 2026 -> les 2 derniers mois
        // entierement termines avant cette ancre sont fevrier et mars 2026.
        LocalDate referenceMonth = LocalDate.of(2026, 3, 15);
        Transaction fevrier = transaction(1L, account, LocalDate.of(2026, 2, 10), "1000.00", null);
        Transaction mars = transaction(2L, account, LocalDate.of(2026, 3, 10), "-200.00", null);
        stubTransactionsBetween(fevrier, mars);

        List<EpargneMensuelleDTO> result = service.monthlySavings(2, null, referenceMonth);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).date()).isEqualTo("2026-02-01");
        assertThat(result.get(0).savings()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(result.get(1).date()).isEqualTo("2026-03-01");
        assertThat(result.get(1).savings()).isEqualTo(new BigDecimal("-200.00"));
    }

    /**
     * Navigue vers le mois calendaire reel en cours (referenceMonth =
     * currentMonthStart, meme situation qu'un clic sur "mois suivant" depuis
     * le dernier mois complet par defaut) : ce dernier point est
     * previsionnel, rempli avec avgMonthlyNetOverTrailing12Months — la
     * moyenne sur les 12 VRAIS derniers mois (TOUJOURS divisee par 12, mois
     * sans transaction inclus), plutot qu'une epargne reelle forcement
     * nulle.
     */
    @Test
    void monthlySavings_moisCourantEnCours_estPrevisionnelRempliParLaMoyenneDesMoisPasses() {
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);
        Compte account = compte(1L, "0");
        Transaction moisMoins2 = transaction(1L, account, currentMonthStart.minusMonths(2).plusDays(5), "1000.00", null);
        Transaction moisMoins1 = transaction(2L, account, currentMonthStart.minusMonths(1).plusDays(5), "-200.00", null);
        stubTransactionsBetween(moisMoins2, moisMoins1);

        List<EpargneMensuelleDTO> result = service.monthlySavings(3, null, currentMonthStart);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).date()).isEqualTo(currentMonthStart.minusMonths(2).toString());
        assertThat(result.get(0).savings()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(result.get(0).forecast()).isFalse();
        assertThat(result.get(1).date()).isEqualTo(currentMonthStart.minusMonths(1).toString());
        assertThat(result.get(1).savings()).isEqualTo(new BigDecimal("-200.00"));
        assertThat(result.get(1).forecast()).isFalse();
        // Moyenne sur les 12 VRAIS derniers mois (dont les 2 ci-dessus, le reste a 0) : (1000 - 200) / 12 = 66.67.
        assertThat(result.get(2).date()).isEqualTo(currentMonthStart.toString());
        assertThat(result.get(2).savings()).isEqualTo(new BigDecimal("66.67"));
        assertThat(result.get(2).forecast()).isTrue();
    }

    /**
     * Navigue tres loin dans le futur : la fenetre affichee (monthsBack
     * points avant referenceMonth) ne touche plus du tout les 12 vrais
     * derniers mois, mais la projection doit rester la MEME que juste apres
     * le mois calendaire en cours — jamais nulle.
     */
    @Test
    void monthlySavings_naviguerTresLoinDansLeFutur_gardeLaMemeProjectionQueLeMoisCourant() {
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);
        Compte account = compte(1L, "0");
        Transaction moisMoins1 = transaction(1L, account, currentMonthStart.minusMonths(1).plusDays(5), "2400.00", null);
        stubTransactionsBetween(moisMoins1);

        List<EpargneMensuelleDTO> result = service.monthlySavings(1, null, currentMonthStart.plusMonths(15));

        // Moyenne sur les 12 VRAIS derniers mois (une seule transaction a 2400) : 2400 / 12 = 200.00.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).savings()).isEqualTo(new BigDecimal("200.00"));
        assertThat(result.get(0).forecast()).isTrue();
    }

    // --- periodicExpensesSummary ---

    @Test
    void periodicExpensesSummary_separeDepensesEtRevenusEtGardeUneSeuleLigneParTiersLaPlusRecente() {
        LocalDate today = LocalDate.now();
        Tiers netflix = new Tiers();
        netflix.setId(1L);
        netflix.setName("Netflix");
        Transaction ancienneOccurrence = new Transaction();
        ancienneOccurrence.setId(1L);
        ancienneOccurrence.setPayee(netflix);
        ancienneOccurrence.setDate(today.minusMonths(1));
        ancienneOccurrence.setAmount(new BigDecimal("-15.99"));
        Transaction recenteOccurrence = new Transaction();
        recenteOccurrence.setId(2L);
        recenteOccurrence.setPayee(netflix);
        recenteOccurrence.setDate(today.minusDays(5));
        recenteOccurrence.setAmount(new BigDecimal("-15.99"));

        Tiers employeur = new Tiers();
        employeur.setId(2L);
        employeur.setName("Employeur");
        Transaction salaire = new Transaction();
        salaire.setId(3L);
        salaire.setPayee(employeur);
        salaire.setDate(today.minusDays(2));
        salaire.setAmount(new BigDecimal("2500.00"));

        when(transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc())
                .thenReturn(List.of(recenteOccurrence, ancienneOccurrence, salaire));

        SynthesePeriodiqueDTO result = service.periodicExpensesSummary();

        assertThat(result.expenses().items()).hasSize(1); // une seule ligne pour Netflix, la plus recente
        assertThat(result.expenses().items().get(0).transactionId()).isEqualTo(2L);

        assertThat(result.revenues().items()).hasSize(1);
        assertThat(result.revenues().items().get(0).transactionId()).isEqualTo(3L);
    }

    @Test
    void periodicExpensesSummary_deuxTiersHomonymes_gardeUneLigneChacun() {
        LocalDate today = LocalDate.now();
        Tiers ligneMel = new Tiers();
        ligneMel.setId(1L);
        ligneMel.setName("Bouygues Telecom");
        Tiers ligneVal = new Tiers();
        ligneVal.setId(2L);
        ligneVal.setName("Bouygues Telecom");
        Transaction forfaitMel = new Transaction();
        forfaitMel.setId(1L);
        forfaitMel.setPayee(ligneMel);
        forfaitMel.setDate(today.minusDays(3));
        forfaitMel.setAmount(new BigDecimal("-10.00"));
        Transaction forfaitVal = new Transaction();
        forfaitVal.setId(2L);
        forfaitVal.setPayee(ligneVal);
        forfaitVal.setDate(today.minusDays(4));
        forfaitVal.setAmount(new BigDecimal("-20.00"));
        when(transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc())
                .thenReturn(List.of(forfaitMel, forfaitVal));

        SynthesePeriodiqueDTO result = service.periodicExpensesSummary();

        assertThat(result.expenses().items()).hasSize(2);
        assertThat(result.expenses().total()).isEqualByComparingTo("30.00");
    }

    @Test
    void periodicExpensesSummary_ignoreLesVirementsInternes() {
        LocalDate today = LocalDate.now();
        Transaction versLivret = new Transaction();
        versLivret.setId(1L);
        versLivret.setDate(today.minusDays(2));
        versLivret.setAmount(new BigDecimal("-200.00"));
        Transaction surLivret = new Transaction();
        surLivret.setId(2L);
        surLivret.setDate(today.minusDays(2));
        surLivret.setAmount(new BigDecimal("200.00"));
        versLivret.setTransferTransaction(surLivret);
        surLivret.setTransferTransaction(versLivret);
        when(transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc())
                .thenReturn(List.of(versLivret, surLivret));

        SynthesePeriodiqueDTO result = service.periodicExpensesSummary();

        assertThat(result.expenses().items()).isEmpty();
        assertThat(result.revenues().items()).isEmpty();
    }

    @Test
    void periodicExpensesSummary_excludLesOccurrencesFuturesEtExceptionnellesEtCellesDePlusDeTroisMois() {
        LocalDate today = LocalDate.now();
        Tiers netflix = new Tiers();
        netflix.setId(1L);
        netflix.setName("Netflix");

        Transaction future = new Transaction();
        future.setId(1L);
        future.setPayee(netflix);
        future.setDate(today.plusDays(3));
        future.setAmount(new BigDecimal("-15.99"));

        Transaction exceptionnelle = new Transaction();
        exceptionnelle.setId(2L);
        exceptionnelle.setPayee(netflix);
        exceptionnelle.setDate(today.minusDays(1));
        exceptionnelle.setAmount(new BigDecimal("-15.99"));
        exceptionnelle.setExceptional(true);

        Transaction tropAncienne = new Transaction();
        tropAncienne.setId(3L);
        tropAncienne.setPayee(netflix);
        tropAncienne.setDate(today.minusMonths(4));
        tropAncienne.setAmount(new BigDecimal("-15.99"));

        when(transactionRepository.findByPeriodicExpenseTrueOrderByDateDescIdDesc())
                .thenReturn(List.of(future, exceptionnelle, tropAncienne));

        SynthesePeriodiqueDTO result = service.periodicExpensesSummary();

        assertThat(result.expenses().total()).isEqualTo(BigDecimal.ZERO);
    }

    // --- balanceForecast ---

    @Test
    void balanceForecast_produit24PointsSurUneAnneeAvantEtApres() {
        Compte account = compte(1L, "1000.00");
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(1L), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());

        PrevisionSoldeDTO result = service.balanceForecast(null, null);

        assertThat(result.points()).hasSize(24);
    }

    @Test
    void balanceForecast_exposeLaTendanceMensuelleMoyenneBaseeSurLeNet() {
        Compte account = compte(1L, "1000.00");
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(1L), any())).thenReturn(BigDecimal.ZERO);
        // Aucune transaction sur les 12 mois de reference -> net moyen nul,
        // quel que soit le solde reel des comptes (voir aussi le test dedie
        // au calcul base sur le "net" ci-dessous).
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());

        PrevisionSoldeDTO result = service.balanceForecast(null, null);

        assertThat(result.monthlyTrend()).isEqualTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void balanceForecast_ignoreLesVirementsEtLesTransactionsExceptionnellesDansLaTendance() {
        Compte account = compte(1L, "1000.00");
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(1L), any())).thenReturn(BigDecimal.ZERO);

        LocalDate lastMonthStart = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        Transaction normale = transaction(1L, account, lastMonthStart.plusDays(1), "100.00", null);
        Transaction virement = transaction(2L, account, lastMonthStart.plusDays(2), "5000.00", null);
        lierAUnAutreCompte(virement);
        Transaction exceptionnelle = transaction(3L, account, lastMonthStart.plusDays(3), "3000.00", null);
        exceptionnelle.setExceptional(true);

        stubTransactionsBetween(normale, virement, exceptionnelle);

        PrevisionSoldeDTO result = service.balanceForecast(null, null);

        // Seule la transaction normale (100.00) compte sur ce mois -> moyenne sur 12 mois = 100/12.
        assertThat(result.monthlyTrend())
                .isEqualTo(new BigDecimal("100.00").divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));
    }

    /**
     * Soldes historiques de fin de mois = solde a la veille de la fenetre
     * (solde initial + transactions anterieures) + cumul des mouvements de
     * chaque mois, virements compris (solde reel, pas le "net" des rapports).
     */
    @Test
    void balanceForecast_soldesHistoriques_cumulentLesMouvementsDeChaqueMois() {
        Compte account = compte(1L, "1000.00");
        LocalDate windowStart = LocalDate.now().withDayOfMonth(1).minusMonths(12);
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(1L, windowStart.minusDays(1))).thenReturn(new BigDecimal("200.00"));
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());
        when(transactionRepository.sumAmountGroupedByDate(any(), any())).thenReturn(List.of(
                new Object[]{windowStart.plusMonths(1).plusDays(3), new BigDecimal("50.00")},
                new Object[]{windowStart.plusMonths(1).plusDays(9), new BigDecimal("-20.00")},
                new Object[]{windowStart.plusMonths(5), new BigDecimal("100.00")}));

        PrevisionSoldeDTO result = service.balanceForecast(null, null);

        List<PointSoldeDTO> points = result.points();
        assertThat(points.get(0).balance()).isEqualTo(new BigDecimal("1200.00"));
        assertThat(points.get(0).type()).isEqualTo(PointSoldeDTO.HISTORIQUE);
        assertThat(points.get(1).balance()).isEqualTo(new BigDecimal("1230.00"));
        assertThat(points.get(4).balance()).isEqualTo(new BigDecimal("1230.00"));
        assertThat(points.get(5).balance()).isEqualTo(new BigDecimal("1330.00"));
        assertThat(points.get(11).balance()).isEqualTo(new BigDecimal("1330.00"));
        assertThat(points.get(11).type()).isEqualTo(PointSoldeDTO.HISTORIQUE);
        // Previsionnel : aucune transaction "nette" sur 12 mois -> tendance nulle, le solde reste stable.
        assertThat(points.get(12).balance()).isEqualTo(new BigDecimal("1330.00"));
        assertThat(points.get(12).type()).isEqualTo(PointSoldeDTO.PREVISIONNEL);
    }

    @Test
    void balanceForecast_avecCompteSpecifique_neChargeQueCeCompte() {
        Compte account = compte(5L, "500.00");
        when(accountRepository.findById(5L)).thenReturn(Optional.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(5L), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findByAccountIdAndDateBetween(eq(5L), any(), any())).thenReturn(List.of());

        service.balanceForecast(5L, null);

        verify(accountRepository, never()).findAll();
        verify(transactionRepository, never()).sumAmountGroupedByDate(any(), any());
    }

    /** Compte supprimé entre-temps : message explicite, pas le "No value present" par défaut d'Optional. */
    @Test
    void balanceForecast_compteInexistant_messageExplicite() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.balanceForecast(99L, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Compte introuvable : 99");
    }

    // --- monthlyCategoryAnalysis ---

    @Test
    void monthlyCategoryAnalysis_construit12MoisEtSepareLesLignesRacineEtEnfantParCategorieEtParMois() {
        Compte account = compte(1L, "0");
        Categorie alimentation = categorie(1L, "Alimentation", null, false);
        Categorie restaurant = categorie(2L, "Restaurant", alimentation, false);
        Categorie salaire = categorie(3L, "Salaire", null, false);
        Transaction depenseRacine = transaction(1L, account, LocalDate.of(2025, 6, 10), "-40.00", alimentation);
        Transaction depenseEnfant = transaction(2L, account, LocalDate.of(2025, 6, 20), "-25.00", restaurant);
        Transaction revenu = transaction(3L, account, LocalDate.of(2025, 7, 5), "3000.00", salaire);
        when(transactionRepository.findByDateBetween(any(), any()))
                .thenReturn(List.of(depenseRacine, depenseEnfant, revenu));

        AnalyseMensuelleDTO result = service.monthlyCategoryAnalysis(null, LocalDate.of(2026, 1, 1));

        assertThat(result.months()).hasSize(12);

        List<LigneAnalyseDTO> expenseRows = result.expenses();
        assertThat(expenseRows).hasSize(2);
        LigneAnalyseDTO ligneAlimentation = ligneNommee(expenseRows, "Alimentation");
        LigneAnalyseDTO ligneRestaurant = ligneNommee(expenseRows, "Restaurant");
        assertThat(ligneAlimentation.parentId()).isNull();
        assertThat(ligneRestaurant.parentId()).isEqualTo(1L);
        assertThat(ligneAlimentation.values().get("2025-06-01")).isEqualByComparingTo("40.00");
        assertThat(ligneRestaurant.values().get("2025-06-01")).isEqualByComparingTo("25.00");

        assertThat(result.income()).hasSize(1);
        assertThat(result.income().get(0).name()).isEqualTo("Salaire");

        assertThat(result.expenseTotal().values().get("2025-06-01")).isEqualByComparingTo("65.00"); // 40 + 25

        ResumeMoisAnalyseDTO juin = resumeDuMois(result, "2025-06-01");
        assertThat(juin.expenses()).isEqualTo(new BigDecimal("65.00"));
        assertThat(juin.net()).isEqualTo(new BigDecimal("-65.00"));
    }

    @Test
    void monthlyCategoryAnalysis_sansTransactions_renvoie12MoisVidesSansLignesNiErreur() {
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());

        AnalyseMensuelleDTO result = service.monthlyCategoryAnalysis(null, LocalDate.of(2026, 1, 1));

        assertThat(result.months()).hasSize(12);
        assertThat(result.expenses()).isEmpty();
    }

    /**
     * Navigue vers le mois calendaire reel en cours (referenceMonth =
     * currentMonthStart : meme situation qu'un clic sur "mois suivant" depuis
     * le dernier mois complet par defaut, voir Dashboard.vue#goToNextMonth) :
     * ce dernier mois de la fenetre est "previsionnel" (voir buildMonthsWindow),
     * rempli avec la moyenne des 12 VRAIS derniers mois (voir
     * categoryForecastAverages, TOUJOURS divisee par 12, mois sans
     * transaction inclus) — jamais a 0, meme si aucune transaction reelle
     * n'existe encore ce mois-la.
     */
    @Test
    void monthlyCategoryAnalysis_moisCourantEnCours_estPrevisionnelRempliParLaMoyenneDesMoisPasses() {
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);
        Compte account = compte(1L, "0");
        Categorie salaire = categorie(3L, "Salaire", null, false);
        Transaction revenu1 = transaction(1L, account, currentMonthStart.minusMonths(3).plusDays(4), "1000.00", salaire);
        Transaction revenu2 = transaction(2L, account, currentMonthStart.minusMonths(2).plusDays(4), "3000.00", salaire);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(revenu1, revenu2));

        AnalyseMensuelleDTO result = service.monthlyCategoryAnalysis(null, currentMonthStart);

        assertThat(result.months()).hasSize(12);
        assertThat(result.months().get(11).key()).isEqualTo(currentMonthStart.toString());
        assertThat(result.months().get(11).forecast()).isTrue();
        assertThat(result.months().get(10).forecast()).isFalse();

        // Moyenne sur les 12 VRAIS derniers mois (dont 2 a 1000/3000, le reste a 0) : 4000 / 12.
        BigDecimal expectedAverage = new BigDecimal("4000.00").divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);

        LigneAnalyseDTO ligneSalaire = ligneNommee(result.income(), "Salaire");
        assertThat(ligneSalaire.values().get(currentMonthStart.toString())).isEqualByComparingTo(expectedAverage);

        assertThat(result.incomeTotal().values().get(currentMonthStart.toString())).isEqualByComparingTo(expectedAverage);

        // Le resume mensuel (Revenus/Depenses/Solde) reprend lui aussi le previsionnel, pas 0.
        ResumeMoisAnalyseDTO moisCourant = resumeDuMois(result, currentMonthStart.toString());
        assertThat(moisCourant.income()).isEqualByComparingTo(expectedAverage);
        assertThat(moisCourant.forecast()).isTrue();
    }

    /**
     * Navigue tres loin dans le futur (bien au-dela des 12 mois de la
     * fenetre affichee) : celle-ci ne contient alors plus AUCUN mois reel,
     * mais le previsionnel doit rester base sur les 12 VRAIS derniers mois
     * (ancres sur aujourd'hui, voir categoryForecastAverages), jamais sur les
     * mois reels de la fenetre affichee (puisqu'il n'y en a plus) — sans quoi
     * les montants retombent a 0, precisement le bug corrige ici.
     */
    @Test
    void monthlyCategoryAnalysis_naviguerTresLoinDansLeFutur_gardeLaMemeProjectionQueLeMoisCourant() {
        LocalDate currentMonthStart = LocalDate.now().withDayOfMonth(1);
        Compte account = compte(1L, "0");
        Categorie salaire = categorie(3L, "Salaire", null, false);
        Transaction revenu = transaction(1L, account, currentMonthStart.minusMonths(2).plusDays(4), "2400.00", salaire);
        // Stub sensible a la plage interrogee (comme le ferait vraiment le
        // repository) : la fenetre affichee (tres loin dans le futur) et la
        // fenetre de reference du previsionnel (12 vrais derniers mois) ne se
        // recoupent pas, il ne faut donc PAS renvoyer la transaction pour
        // n'importe quelle plage (thenReturn), sans quoi elle "fuite" dans
        // des mois hors fenetre affichee.
        when(transactionRepository.findByDateBetween(any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(0);
            LocalDate to = inv.getArgument(1);
            LocalDate date = revenu.getDate();
            return !date.isBefore(from) && !date.isAfter(to) ? List.of(revenu) : List.of();
        });

        // 15 mois dans le futur : la fenetre de 12 mois affichee ([+4 ; +15])
        // ne touche plus du tout les 12 vrais derniers mois ([-12 ; -1]).
        LocalDate loinDansLeFutur = currentMonthStart.plusMonths(15);
        AnalyseMensuelleDTO result = service.monthlyCategoryAnalysis(null, loinDansLeFutur);

        assertThat(result.months()).allMatch(MoisAnalyseDTO::forecast);

        // Moyenne sur les 12 VRAIS derniers mois (une seule transaction a 2400) : 2400 / 12 = 200.00.
        BigDecimal expectedAverage = new BigDecimal("2400.00").divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        assertThat(expectedAverage).isEqualByComparingTo("200.00");

        LigneAnalyseDTO ligneSalaire = ligneNommee(result.income(), "Salaire");
        assertThat(ligneSalaire.values().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo(expectedAverage));

        assertThat(result.incomeTotal().values().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo(expectedAverage));
    }

    /** Ligne du tableau d'analyse portant ce nom de categorie (echoue si absente). */
    private static LigneAnalyseDTO ligneNommee(List<LigneAnalyseDTO> rows, String name) {
        return rows.stream().filter(r -> name.equals(r.name())).findFirst().orElseThrow();
    }

    /** Resume (revenus/depenses/net) du mois de cle monthKey (echoue si absent). */
    private static ResumeMoisAnalyseDTO resumeDuMois(AnalyseMensuelleDTO analyse, String monthKey) {
        return analyse.summary().stream().filter(m -> monthKey.equals(m.key())).findFirst().orElseThrow();
    }
}

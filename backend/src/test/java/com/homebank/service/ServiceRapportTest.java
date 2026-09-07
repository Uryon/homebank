package com.homebank.service;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    // --- attributedTransactions (exclusions), exercee via expensesByCategory ---

    @Test
    void expensesByCategory_excluLesVirementsLesTransactionsFuturesEtLesExceptionnelles() {
        Compte account = compte(1L, "0");
        Categorie courses = categorie(1L, "Courses", null, false);
        Transaction depenseValide = transaction(1L, account, LocalDate.of(2026, 1, 10), "-50.00", courses);
        Transaction virement = transaction(2L, account, LocalDate.of(2026, 1, 10), "-30.00", courses);
        virement.setPaymentMode(ModePaiement.TRANSFER);
        Transaction future = transaction(3L, account, LocalDate.now().plusDays(5), "-20.00", courses);
        Transaction exceptionnelle = transaction(4L, account, LocalDate.of(2026, 1, 15), "-999.00", courses);
        exceptionnelle.setExceptional(true);
        when(transactionRepository.findByDateBetween(any(), any()))
                .thenReturn(List.of(depenseValide, virement, future, exceptionnelle));

        List<Map<String, Object>> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("total", new BigDecimal("50.00"));
    }

    @Test
    void expensesByCategory_neGardeQueLesDepensesEtIgnoreLesRevenus() {
        Compte account = compte(1L, "0");
        Categorie salaire = categorie(1L, "Salaire", null, false);
        Transaction revenu = transaction(1L, account, LocalDate.of(2026, 1, 5), "2000.00", salaire);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(revenu));

        List<Map<String, Object>> result = service.expensesByCategory(
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

        List<Map<String, Object>> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result.get(0))
                .containsEntry("category", "Alimentation : Restaurant")
                .containsEntry("categoryId", 2L)
                .containsEntry("uncategorized", false);
    }

    @Test
    void expensesByCategory_transactionSansCategorie_apparaitCommeNonCategorise() {
        Compte account = compte(1L, "0");
        Transaction t = transaction(1L, account, LocalDate.of(2026, 1, 5), "-25.00", null);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(t));

        List<Map<String, Object>> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result.get(0))
                .containsEntry("category", "Non categorise")
                .containsEntry("uncategorized", true);
        assertThat(result.get(0).get("categoryId")).isNull();
    }

    @Test
    void expensesByCategory_calculeLePourcentageDeChaqueCategorieSurLeTotalEtTrieParMontantDecroissant() {
        Compte account = compte(1L, "0");
        Categorie courses = categorie(1L, "Courses", null, false);
        Categorie loisirs = categorie(2L, "Loisirs", null, false);
        Transaction t1 = transaction(1L, account, LocalDate.of(2026, 1, 5), "-60.00", courses);
        Transaction t2 = transaction(2L, account, LocalDate.of(2026, 1, 6), "-40.00", loisirs);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(t1, t2));

        List<Map<String, Object>> result = service.expensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0))
                .containsEntry("category", "Courses")
                .containsEntry("percentage", new BigDecimal("60.0"));
        assertThat(result.get(1))
                .containsEntry("category", "Loisirs")
                .containsEntry("percentage", new BigDecimal("40.0"));
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
        List<Map<String, Object>> result = service.expensesByCategory(
                LocalDate.of(2025, 11, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("total", new BigDecimal("300.00")); // 3 * (1200/12)
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

        List<Map<String, Object>> result = service.expensesByCategory(
                LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 31), null);

        assertThat(result.get(0)).containsEntry("total", new BigDecimal("100.00")); // 1200/12, jamais 1200
    }

    @Test
    void attributedTransactions_excludLaTransactionAnnualiseeSiSonCompteNeCorrespondPasAuFiltre() {
        Compte compte1 = compte(1L, "0");
        Compte compte2 = compte(2L, "0");
        Categorie taxeFonciere = categorie(1L, "Taxe fonciere", null, true);
        Transaction annuelle = transaction(1L, compte1, LocalDate.of(2025, 10, 15), "-1200.00", taxeFonciere);
        when(transactionRepository.findByAccountIdAndDateBetween(any(), any(), any())).thenReturn(List.of());
        when(transactionRepository.findByCategory_AnnualizedTrue()).thenReturn(List.of(annuelle));

        List<Map<String, Object>> result = service.expensesByCategory(
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
        virement.setPaymentMode(ModePaiement.TRANSFER);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of(revenu, depense, virement));

        Map<String, Object> result = service.summary(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);

        assertThat(result)
                .containsEntry("income", new BigDecimal("2000.00"))
                .containsEntry("expenses", new BigDecimal("500.00"))
                .containsEntry("net", new BigDecimal("1500.00"));
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
        // Stub generique : le mois interroge determine la reponse.
        when(transactionRepository.findByDateBetween(any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(0);
            if (from.equals(LocalDate.of(2026, 2, 1))) return List.of(fevrier);
            if (from.equals(LocalDate.of(2026, 3, 1))) return List.of(mars);
            return List.of();
        });

        List<Map<String, Object>> result = service.monthlySavings(2, null, referenceMonth);

        assertThat(result).hasSize(2);
        assertThat(result.get(0))
                .containsEntry("date", "2026-02-01")
                .containsEntry("savings", new BigDecimal("1000.00"));
        assertThat(result.get(1))
                .containsEntry("date", "2026-03-01")
                .containsEntry("savings", new BigDecimal("-200.00"));
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

        Map<String, Object> result = service.periodicExpensesSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> expenses = (Map<String, Object>) result.get("expenses");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expenseItems = (List<Map<String, Object>>) expenses.get("items");
        assertThat(expenseItems).hasSize(1); // une seule ligne pour Netflix, la plus recente
        assertThat(expenseItems.get(0)).containsEntry("transactionId", 2L);

        @SuppressWarnings("unchecked")
        Map<String, Object> revenues = (Map<String, Object>) result.get("revenues");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> revenueItems = (List<Map<String, Object>>) revenues.get("items");
        assertThat(revenueItems).hasSize(1);
        assertThat(revenueItems.get(0)).containsEntry("transactionId", 3L);
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

        Map<String, Object> result = service.periodicExpensesSummary();

        @SuppressWarnings("unchecked")
        Map<String, Object> expenses = (Map<String, Object>) result.get("expenses");
        assertThat(expenses).containsEntry("total", BigDecimal.ZERO);
    }

    // --- balanceForecast ---

    @Test
    void balanceForecast_produit24PointsSurUneAnneeAvantEtApres() {
        Compte account = compte(1L, "1000.00");
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(1L), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.balanceForecast(null, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) result.get("points");
        assertThat(points).hasSize(24);
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

        Map<String, Object> result = service.balanceForecast(null, null);

        assertThat(result).containsEntry("monthlyTrend", BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void balanceForecast_ignoreLesVirementsEtLesTransactionsExceptionnellesDansLaTendance() {
        Compte account = compte(1L, "1000.00");
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(1L), any())).thenReturn(BigDecimal.ZERO);

        LocalDate lastMonthStart = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        Transaction normale = transaction(1L, account, lastMonthStart.plusDays(1), "100.00", null);
        Transaction virement = transaction(2L, account, lastMonthStart.plusDays(2), "5000.00", null);
        virement.setPaymentMode(ModePaiement.TRANSFER);
        Transaction exceptionnelle = transaction(3L, account, lastMonthStart.plusDays(3), "3000.00", null);
        exceptionnelle.setExceptional(true);

        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());
        when(transactionRepository.findByDateBetween(eq(lastMonthStart), any()))
                .thenReturn(List.of(normale, virement, exceptionnelle));

        Map<String, Object> result = service.balanceForecast(null, null);

        // Seule la transaction normale (100.00) compte sur ce mois -> moyenne sur 12 mois = 100/12.
        assertThat(result).containsEntry("monthlyTrend",
                new BigDecimal("100.00").divide(BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void balanceForecast_avecCompteSpecifique_neChargeQueCeCompte() {
        Compte account = compte(5L, "500.00");
        when(accountRepository.findById(5L)).thenReturn(Optional.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(5L), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findByAccountIdAndDateBetween(eq(5L), any(), any())).thenReturn(List.of());

        service.balanceForecast(5L, null);

        verify(accountRepository, never()).findAll();
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

        Map<String, Object> result = service.monthlyCategoryAnalysis(null, LocalDate.of(2026, 1, 1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> months = (List<Map<String, Object>>) result.get("months");
        assertThat(months).hasSize(12);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expenseRows = (List<Map<String, Object>>) result.get("expenses");
        assertThat(expenseRows).hasSize(2);
        Map<String, Object> ligneAlimentation = expenseRows.stream()
                .filter(r -> "Alimentation".equals(r.get("name"))).findFirst().orElseThrow();
        Map<String, Object> ligneRestaurant = expenseRows.stream()
                .filter(r -> "Restaurant".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(ligneAlimentation.get("parentId")).isNull();
        assertThat(ligneRestaurant).containsEntry("parentId", 1L);
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> valuesAlimentation = (Map<String, BigDecimal>) ligneAlimentation.get("values");
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> valuesRestaurant = (Map<String, BigDecimal>) ligneRestaurant.get("values");
        assertThat(valuesAlimentation.get("2025-06-01")).isEqualByComparingTo("40.00");
        assertThat(valuesRestaurant.get("2025-06-01")).isEqualByComparingTo("25.00");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incomeRows = (List<Map<String, Object>>) result.get("income");
        assertThat(incomeRows).hasSize(1);
        assertThat(incomeRows.get(0)).containsEntry("name", "Salaire");

        @SuppressWarnings("unchecked")
        Map<String, Object> expenseTotal = (Map<String, Object>) result.get("expenseTotal");
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> expenseTotalValues = (Map<String, BigDecimal>) expenseTotal.get("values");
        assertThat(expenseTotalValues.get("2025-06-01")).isEqualByComparingTo("65.00"); // 40 + 25

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) result.get("summary");
        Map<String, Object> juin = summary.stream()
                .filter(m -> "2025-06-01".equals(m.get("key"))).findFirst().orElseThrow();
        assertThat(juin)
                .containsEntry("expenses", new BigDecimal("65.00"))
                .containsEntry("net", new BigDecimal("-65.00"));
    }

    @Test
    void monthlyCategoryAnalysis_sansTransactions_renvoie12MoisVidesSansLignesNiErreur() {
        when(transactionRepository.findByDateBetween(any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.monthlyCategoryAnalysis(null, LocalDate.of(2026, 1, 1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> months = (List<Map<String, Object>>) result.get("months");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expenseRows = (List<Map<String, Object>>) result.get("expenses");
        assertThat(months).hasSize(12);
        assertThat(expenseRows).isEmpty();
    }
}

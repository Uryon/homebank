package com.homebank.service;

import com.homebank.dto.TiersDTO;
import com.homebank.mapper.MappeurTiers;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.dto.CategorisationAutoResultatDTO;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifie la logique de reconnaissance/fusion des tiers (ServiceTiers), qui
 * ne l'etait par aucun test jusqu'ici alors qu'elle conditionne la
 * categorisation automatique a l'import et la fusion (page Tiers).
 */
@ExtendWith(MockitoExtension.class)
class ServiceTiersTest {

    @Mock
    private DepotTiers payeeRepository;
    @Mock
    private ServiceCategorie categoryService;
    @Mock
    private DepotTransaction transactionRepository;

    private ServiceTiers service;

    @BeforeEach
    void setUp() {
        service = new ServiceTiers(payeeRepository, categoryService, new MappeurTiers(), transactionRepository);
    }

    private static Tiers tiers(Long id, String name, String pattern, BigDecimal amount) {
        Tiers p = new Tiers();
        p.setId(id);
        p.setName(name);
        p.setPattern(pattern);
        p.setAmount(amount);
        return p;
    }

    // --- matchPayee : reconnaissance par motif "joker" ---

    @Test
    void merge_refuseUneDemandeSansCibleOuSansSources() {
        assertThatThrownBy(() -> service.merge(List.of(1L), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(null, 1L, null)).isInstanceOf(IllegalArgumentException.class);
        verify(payeeRepository, never()).delete(any(Tiers.class));
    }

    @Test
    void create_refuseUnNomVideSansRienEnregistrer() {
        TiersDTO payload = TiersDTO.builder().name("  ").pattern("Carrefour").build();

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nom");
        verify(payeeRepository, never()).save(any(Tiers.class));
    }

    @Test
    void matchPayee_reconnaitUnMotifSimpleContenuDansLeTexteSansTenirCompteDeLaCasse() {
        Tiers carrefour = tiers(1L, "Carrefour", "Carrefour", null);
        when(payeeRepository.findAll()).thenReturn(List.of(carrefour));

        Optional<Tiers> result = service.matchPayee("CARREFOUR PARIS 12", new BigDecimal("10.00"));

        assertThat(result).contains(carrefour);
    }

    @Test
    void matchPayee_traiteLEspaceInsecableDuReleveCommeUnEspaceOrdinaire() {
        Tiers sgc = tiers(1L, "SGC Tourcoing", "SGC TOURCOING", null);
        when(payeeRepository.findAll()).thenReturn(List.of(sgc));

        assertThat(service.matchPayee("CB SGC\u00A0 TOURCOING 12/03", null)).contains(sgc);
        assertThat(service.matchPayee("CB SGC\u202FTOURCOING", null)).contains(sgc);
    }

    @Test
    void matchPayee_neReconnaitPasUnTexteQuiNeContientPasLeMotif() {
        Tiers carrefour = tiers(1L, "Carrefour", "Carrefour", null);
        when(payeeRepository.findAll()).thenReturn(List.of(carrefour));

        Optional<Tiers> result = service.matchPayee("Monoprix Paris", null);

        assertThat(result).isEmpty();
    }

    @Test
    void matchPayee_motifAvanceAvecJokerAuMilieuExigeLesDeuxMorceauxDansLOrdre() {
        Tiers p = tiers(1L, "Carrefour Paris", "Carrefour*Paris", null);
        when(payeeRepository.findAll()).thenReturn(List.of(p));

        assertThat(service.matchPayee("Carrefour Market Paris 15", null)).contains(p);
        assertThat(service.matchPayee("Carrefour Lyon", null)).isEmpty();
    }

    @Test
    void matchPayee_plusieursMotifsSepareParPointVirguleFonctionnentEnOu() {
        Tiers p = tiers(1L, "Bailleur", "Bailleur;Loyer", null);
        when(payeeRepository.findAll()).thenReturn(List.of(p));

        assertThat(service.matchPayee("Prelevement Loyer mensuel", null)).contains(p);
        assertThat(service.matchPayee("Virement Bailleur SCI", null)).contains(p);
    }

    @Test
    void matchPayee_prefereLeTiersAuMontantExactAuTiersGeneriqueMaisReplieSurLeGeneriqueSinon() {
        Tiers generique = tiers(1L, "Bouygues", "Bouygues", null);
        Tiers precis = tiers(2L, "Bouygues Mel", "Bouygues", new BigDecimal("10.00"));
        when(payeeRepository.findAll()).thenReturn(List.of(generique, precis));

        assertThat(service.matchPayee("Bouygues Telecom", new BigDecimal("10.00"))).contains(precis);
        assertThat(service.matchPayee("Bouygues Telecom", new BigDecimal("25.00"))).contains(generique);
    }

    // --- resolveOrCreate : creation automatique a l'import ---

    @Test
    void resolveOrCreate_creeUnTiersGeneriqueSansMontantQuandAucunMotifNeCorrespond() {
        when(payeeRepository.findAll()).thenReturn(new ArrayList<>());
        when(payeeRepository.save(any(Tiers.class))).thenAnswer(inv -> {
            Tiers saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        Tiers created = service.resolveOrCreate("Le Bistrot", new BigDecimal("38.50"));

        assertThat(created.getName()).isEqualTo("Le Bistrot");
        assertThat(created.getPattern()).isEqualTo("Le Bistrot");
        assertThat(created.getAmount()).isNull();
    }

    @Test
    void resolveOrCreate_reutiliseUnTiersExistantDeMemeNomEtMontantMemeSiSonMotifNeCorrespondPlus() {
        Tiers renomme = tiers(3L, "Vinted", "Un motif totalement different", new BigDecimal("20.00"));
        when(payeeRepository.findAll()).thenReturn(List.of(renomme));

        Tiers result = service.resolveOrCreate("Vinted", new BigDecimal("20.00"));

        assertThat(result).isSameAs(renomme);
        verify(payeeRepository, never()).save(any());
    }

    // --- merge : fusion de tiers ---

    @Test
    void merge_reaffecteLesTransactionsEtSupprimeLesTiersSources() {
        Tiers target = tiers(1L, "Cible", "Cible", null);
        Tiers source = tiers(2L, "Source", "Source", null);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(target));
        when(payeeRepository.findById(2L)).thenReturn(Optional.of(source));
        when(payeeRepository.save(target)).thenReturn(target);

        service.merge(List.of(2L), 1L, null);

        verify(transactionRepository).reassignPayee(source, target);
        verify(payeeRepository).delete(source);
    }

    @Test
    void merge_appliqueLeMotifFourniSansConcatenerCeluiDesSources() {
        Tiers target = tiers(1L, "Cible", "Cible", null);
        Tiers source = tiers(2L, "Source", "MotifSource", null);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(target));
        when(payeeRepository.findById(2L)).thenReturn(Optional.of(source));
        when(payeeRepository.save(target)).thenReturn(target);

        TiersDTO dto = service.merge(List.of(2L), 1L, "MotifChoisiParLUtilisateur");

        assertThat(target.getPattern()).isEqualTo("MotifChoisiParLUtilisateur");
        assertThat(dto.getPattern()).isEqualTo("MotifChoisiParLUtilisateur");
    }

    @Test
    void merge_laisseLeMotifDeLaCibleInchangeQuandAucunMotifNestFourni() {
        Tiers target = tiers(1L, "Cible", "MotifCibleInitial", null);
        Tiers source = tiers(2L, "Source", "MotifSource", null);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(target));
        when(payeeRepository.findById(2L)).thenReturn(Optional.of(source));
        when(payeeRepository.save(target)).thenReturn(target);

        service.merge(List.of(2L), 1L, "   ");

        assertThat(target.getPattern()).isEqualTo("MotifCibleInitial");
    }

    @Test
    void merge_completeLaCategorieDeLaCibleAvecCelleDuneSourceSiElleEnEtaitDepourvue() {
        Categorie categorieSource = new Categorie();
        categorieSource.setId(7L);
        Tiers target = tiers(1L, "Cible", "Cible", null);
        Tiers source = tiers(2L, "Source", "Source", null);
        source.setCategory(categorieSource);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(target));
        when(payeeRepository.findById(2L)).thenReturn(Optional.of(source));
        when(payeeRepository.save(target)).thenReturn(target);

        service.merge(List.of(2L), 1L, null);

        assertThat(target.getCategory()).isEqualTo(categorieSource);
    }

    // --- migrateLegacyTransactionPayees ---

    @Test
    void migrateLegacyTransactionPayees_neFaitRienSiLAncienneColonneNexistePlus() {
        when(transactionRepository.countLegacyPayeeColumn()).thenReturn(0L);

        int migrated = service.migrateLegacyTransactionPayees();

        assertThat(migrated).isZero();
        verify(transactionRepository, never()).findLegacyPayeeTextForMigration();
    }

    private static com.homebank.model.Transaction transaction(Long id, BigDecimal amount) {
        com.homebank.model.Transaction t = new com.homebank.model.Transaction();
        t.setId(id);
        t.setAmount(amount);
        return t;
    }

    @Test
    void migrateLegacyTransactionPayees_relieChaqueTransactionAuTiersReconnuOuCreeEtSauvegardeEnUnSeulBatch() {
        Tiers carrefourConnu = tiers(1L, "Carrefour", "Carrefour", null);
        com.homebank.model.Transaction t10 = transaction(10L, new BigDecimal("15.00"));
        com.homebank.model.Transaction t11 = transaction(11L, new BigDecimal("20.00"));
        when(transactionRepository.countLegacyPayeeColumn()).thenReturn(1L);
        when(transactionRepository.findLegacyPayeeTextForMigration()).thenReturn(List.<Object[]>of(
                new Object[]{10L, "Carrefour"},
                new Object[]{11L, "NouveauTiers"}
        ));
        when(payeeRepository.findAll()).thenReturn(new ArrayList<>(List.of(carrefourConnu)));
        when(transactionRepository.findAllById(List.of(10L, 11L))).thenReturn(List.of(t10, t11));
        when(payeeRepository.save(any(Tiers.class))).thenAnswer(inv -> {
            Tiers saved = inv.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        int migrated = service.migrateLegacyTransactionPayees();

        assertThat(migrated).isEqualTo(2);
        assertThat(t10.getPayee()).isEqualTo(carrefourConnu);
        assertThat(t11.getPayee()).isNotNull();
        assertThat(t11.getPayee().getName()).isEqualTo("NouveauTiers");
        verify(transactionRepository).saveAll(List.of(t10, t11));
    }

    @Test
    void migrateLegacyTransactionPayees_compteLaLigneMaisNeFaitRienSiLaTransactionNexistePlus() {
        when(transactionRepository.countLegacyPayeeColumn()).thenReturn(1L);
        when(transactionRepository.findLegacyPayeeTextForMigration())
                .thenReturn(List.<Object[]>of(new Object[]{99L, "Inconnu"}));
        when(payeeRepository.findAll()).thenReturn(new ArrayList<>());
        when(transactionRepository.findAllById(List.of(99L))).thenReturn(List.of());

        int migrated = service.migrateLegacyTransactionPayees();

        assertThat(migrated).isEqualTo(1);
        verify(transactionRepository).saveAll(List.of());
        verify(payeeRepository, never()).save(any());
    }

    // --- create / update : validation et normalisation du formulaire ---

    private static Categorie categorie(Long id, String name) {
        Categorie c = new Categorie();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static Transaction transaction(Long id, Tiers payee, Categorie category) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setPayee(payee);
        t.setCategory(category);
        t.setAmount(new BigDecimal("-10.00"));
        return t;
    }

    @Test
    void create_nettoieLeNomEtReprendLeNomCommeMotifParDefaut() {
        when(payeeRepository.save(any(Tiers.class))).thenAnswer(inv -> inv.getArgument(0));
        TiersDTO payload = TiersDTO.builder().name("  Boulangerie Paul  ").pattern("  ").amount(new BigDecimal("-4.50")).build();

        TiersDTO created = service.create(payload);

        assertThat(created.getName()).isEqualTo("Boulangerie Paul");
        assertThat(created.getPattern()).isEqualTo("Boulangerie Paul");
        // Montant de reconnaissance stocke en valeur absolue (le signe vient de la transaction).
        assertThat(created.getAmount()).isEqualByComparingTo("4.50");
        assertThat(created.getCategoryId()).isNull();
    }

    @Test
    void create_nettoieChaqueMorceauDUnMotifMultipleSansAjouterDEtoile() {
        when(payeeRepository.save(any(Tiers.class))).thenAnswer(inv -> inv.getArgument(0));
        TiersDTO payload = TiersDTO.builder().name("Carrefour").pattern(" Carrefour ; CRF*Paris ").build();

        assertThat(service.create(payload).getPattern()).isEqualTo("Carrefour;CRF*Paris");
    }

    @Test
    void update_motifVide_conserveLeMotifPersonnaliseExistant() {
        Tiers existing = tiers(7L, "Free", "FREE MOBILE;FREE TELECOM", null);
        when(payeeRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(payeeRepository.save(any(Tiers.class))).thenAnswer(inv -> inv.getArgument(0));
        Categorie internet = categorie(3L, "Internet");
        when(categoryService.findById(3L)).thenReturn(internet);

        TiersDTO updated = service.update(7L, TiersDTO.builder().name("Free").pattern("").categoryId(3L).build());

        assertThat(updated.getPattern()).isEqualTo("FREE MOBILE;FREE TELECOM");
        assertThat(updated.getCategoryId()).isEqualTo(3L);
    }

    // --- delete : suppression manuelle depuis la page Tiers ---

    @Test
    void delete_tiersInutilise_estSupprime() {
        Tiers inutilise = tiers(1L, "Ancien", "Ancien", null);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(inutilise));
        when(transactionRepository.countByPayee(inutilise)).thenReturn(0L);

        service.delete(1L);

        verify(payeeRepository).delete(inutilise);
    }

    @Test
    void delete_tiersUtilise_estRefuseAvecLeNombreDeTransactions() {
        Tiers utilise = tiers(1L, "EDF", "EDF", null);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(utilise));
        when(transactionRepository.countByPayee(utilise)).thenReturn(12L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12 transaction(s)")
                .hasMessageContaining("Fusionnez");
        verify(payeeRepository, never()).delete(any(Tiers.class));
    }

    @Test
    void delete_tiersInexistant_renvoieIntrouvable() {
        when(payeeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(NoSuchElementException.class);
    }

    // --- deleteIfOrphan : suppression des tiers auto-crees devenus orphelins ---

    @Test
    void deleteIfOrphan_supprimeUnTiersAutoCreeQuiNAPlusDeTransaction() {
        Tiers ancien = tiers(1L, "SUMUP CAFE", "SUMUP CAFE", null);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(ancien));
        when(transactionRepository.existsByPayee(ancien)).thenReturn(false);

        service.deleteIfOrphan(1L);

        verify(payeeRepository).delete(ancien);
    }

    @Test
    void deleteIfOrphan_gardeUnTiersEncoreUtiliseOuPersonnaliseOuDejaSupprime() {
        Tiers encoreUtilise = tiers(1L, "EDF", "EDF", null);
        when(payeeRepository.findById(1L)).thenReturn(Optional.of(encoreUtilise));
        when(transactionRepository.existsByPayee(encoreUtilise)).thenReturn(true);
        Tiers personnalise = tiers(3L, "Free", "FREE MOBILE", null);
        when(payeeRepository.findById(3L)).thenReturn(Optional.of(personnalise));
        when(payeeRepository.findById(4L)).thenReturn(Optional.empty());

        service.deleteIfOrphan(1L);
        service.deleteIfOrphan(3L);
        service.deleteIfOrphan(4L);

        verify(payeeRepository, never()).delete(any(Tiers.class));
        // Tiers personnalise : jamais supprime, sans meme verifier ses transactions.
        verify(transactionRepository, never()).existsByPayee(personnalise);
    }

    // --- autoCategorize : categorisation automatique en masse (page Tiers) ---

    @Test
    void autoCategorize_deduitLaCategorieUnanimeEtCombleLesTransactionsSansCategorie() {
        Categorie courses = categorie(10L, "Courses");
        Tiers carrefour = tiers(1L, "Carrefour", "Carrefour", null);
        Transaction categorisee1 = transaction(1L, carrefour, courses);
        Transaction categorisee2 = transaction(2L, carrefour, courses);
        Transaction sansCategorie = transaction(3L, carrefour, null);
        Transaction sansTiers = transaction(4L, null, null);
        when(payeeRepository.findAll()).thenReturn(List.of(carrefour));
        when(transactionRepository.findAllWithPayeeAndCategory())
                .thenReturn(List.of(categorisee1, categorisee2, sansCategorie, sansTiers));

        CategorisationAutoResultatDTO result = service.autoCategorize();

        assertThat(carrefour.getCategory()).isSameAs(courses);
        assertThat(sansCategorie.getCategory()).isSameAs(courses);
        assertThat(sansTiers.getCategory()).isNull();
        verify(payeeRepository).saveAll(List.of(carrefour));
        verify(transactionRepository).saveAll(List.of(sansCategorie));
        assertThat(result.getPayeesUpdated()).isEqualTo(1);
        assertThat(result.getTransactionsUpdated()).isEqualTo(1);
    }

    @Test
    void autoCategorize_categoriesEnDesaccord_neModifieRien() {
        Tiers amazon = tiers(1L, "Amazon", "Amazon", null);
        Transaction livre = transaction(1L, amazon, categorie(10L, "Livres"));
        Transaction maison = transaction(2L, amazon, categorie(11L, "Maison"));
        Transaction sansCategorie = transaction(3L, amazon, null);
        when(payeeRepository.findAll()).thenReturn(List.of(amazon));
        when(transactionRepository.findAllWithPayeeAndCategory()).thenReturn(List.of(livre, maison, sansCategorie));

        CategorisationAutoResultatDTO result = service.autoCategorize();

        assertThat(amazon.getCategory()).isNull();
        assertThat(sansCategorie.getCategory()).isNull();
        assertThat(result.getPayeesUpdated()).isZero();
        assertThat(result.getTransactionsUpdated()).isZero();
    }

    @Test
    void autoCategorize_tiersDejaCategorise_combleLesVidesSansToucherAuxChoixExistants() {
        Categorie telephone = categorie(10L, "Telephone");
        Categorie cadeaux = categorie(11L, "Cadeaux");
        Tiers bouygues = tiers(1L, "Bouygues", "Bouygues", null);
        bouygues.setCategory(telephone);
        Transaction choixUtilisateur = transaction(1L, bouygues, cadeaux);
        Transaction sansCategorie = transaction(2L, bouygues, null);
        when(payeeRepository.findAll()).thenReturn(List.of(bouygues));
        when(transactionRepository.findAllWithPayeeAndCategory()).thenReturn(List.of(choixUtilisateur, sansCategorie));

        CategorisationAutoResultatDTO result = service.autoCategorize();

        assertThat(choixUtilisateur.getCategory()).isSameAs(cadeaux);
        assertThat(sansCategorie.getCategory()).isSameAs(telephone);
        assertThat(bouygues.getCategory()).isSameAs(telephone);
        // Le tiers avait deja sa categorie : il n'est pas compte comme "categorise".
        assertThat(result.getPayeesUpdated()).isZero();
        assertThat(result.getTransactionsUpdated()).isEqualTo(1);
    }
}

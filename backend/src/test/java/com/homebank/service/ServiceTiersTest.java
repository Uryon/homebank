package com.homebank.service;

import com.homebank.dto.TiersDTO;
import com.homebank.mapper.MappeurTiers;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    void matchPayee_reconnaitUnMotifSimpleContenuDansLeTexteSansTenirCompteDeLaCasse() {
        Tiers carrefour = tiers(1L, "Carrefour", "Carrefour", null);
        when(payeeRepository.findAll()).thenReturn(List.of(carrefour));

        Optional<Tiers> result = service.matchPayee("CARREFOUR PARIS 12", new BigDecimal("10.00"));

        assertThat(result).contains(carrefour);
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
}

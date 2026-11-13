package com.homebank.service;

import com.homebank.dto.CategorieDTO;
import com.homebank.model.Categorie;
import com.homebank.model.Categorie.TypeCategorie;
import com.homebank.repository.DepotCategorie;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifie ServiceCategorie, qui n'etait couvert par aucun test jusqu'ici :
 * la regle "une sous-categorie suit toujours le type de son parent", la
 * fusion (reaffectation transactions/tiers/enfants), et la resolution
 * hierarchique "Parent:Enfant" utilisee a l'import.
 */
@ExtendWith(MockitoExtension.class)
class ServiceCategorieTest {

    @Mock
    private DepotCategorie categoryRepository;
    @Mock
    private DepotTransaction transactionRepository;
    @Mock
    private DepotTiers payeeRepository;

    private ServiceCategorie service;

    @BeforeEach
    void setUp() {
        service = new ServiceCategorie(categoryRepository, transactionRepository, payeeRepository);
        // save() renvoie l'entite telle que passee, comme le ferait Hibernate pour une entite deja porteuse de son etat.
        // lenient() : ce stub n'est pas utilise par les tests qui ne sauvegardent rien (ex : chemins en erreur).
        lenient().when(categoryRepository.save(any(Categorie.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Categorie categorie(Long id, String name, TypeCategorie type, Categorie parent) {
        Categorie c = new Categorie();
        c.setId(id);
        c.setName(name);
        c.setType(type);
        c.setParent(parent);
        return c;
    }

    // --- create : heritage du type par la sous-categorie ---

    @Test
    void create_avecParent_ignoreLeTypeDuPayloadEtReprendCeluiDuParent() {
        Categorie parent = categorie(1L, "Alimentation", TypeCategorie.EXPENSE, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(parent));
        CategorieDTO payload = CategorieDTO.builder().name("Restaurant").parentId(1L).type(TypeCategorie.INCOME).build();

        Categorie result = service.create(payload);

        assertThat(result.getParent()).isEqualTo(parent);
        assertThat(result.getType()).isEqualTo(TypeCategorie.EXPENSE);
    }

    @Test
    void create_sansParent_utiliseLeTypeDuPayload() {
        CategorieDTO payload = CategorieDTO.builder().name("Salaire").type(TypeCategorie.INCOME).build();

        Categorie result = service.create(payload);

        assertThat(result.getParent()).isNull();
        assertThat(result.getType()).isEqualTo(TypeCategorie.INCOME);
    }

    // --- update ---

    @Test
    void update_versSonPropreId_leveUneIllegalArgumentException() {
        Categorie existing = categorie(1L, "Loisirs", TypeCategorie.EXPENSE, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        CategorieDTO payload = CategorieDTO.builder().name("Loisirs").parentId(1L).build();

        assertThatThrownBy(() -> service.update(1L, payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_categorieRacineDontLeTypeChange_repercuteLeNouveauTypeSurSesSousCategories() {
        Categorie existing = categorie(1L, "Alimentation", TypeCategorie.EXPENSE, null);
        Categorie child = categorie(2L, "Restaurant", TypeCategorie.EXPENSE, existing);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findByParent(existing)).thenReturn(List.of(child));
        CategorieDTO payload = CategorieDTO.builder().name("Alimentation").type(TypeCategorie.BOTH).build();

        service.update(1L, payload);

        assertThat(child.getType()).isEqualTo(TypeCategorie.BOTH);
        verify(categoryRepository).saveAll(List.of(child));
    }

    @Test
    void update_versUnNouveauParent_suitLeTypeDuParentQuelQueSoitLeTypeDuPayload() {
        Categorie existing = categorie(1L, "Restaurant", TypeCategorie.EXPENSE, null);
        Categorie newParent = categorie(2L, "Loisirs", TypeCategorie.BOTH, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(newParent));
        when(categoryRepository.findByParent(existing)).thenReturn(List.of());
        CategorieDTO payload = CategorieDTO.builder().name("Restaurant").parentId(2L).type(TypeCategorie.INCOME).build();

        Categorie result = service.update(1L, payload);

        assertThat(result.getParent()).isEqualTo(newParent);
        assertThat(result.getType()).isEqualTo(TypeCategorie.BOTH);
    }

    @Test
    void update_versUnNouveauParent_repercuteLeNouveauTypeSurSesPropresSousCategories() {
        // "Restaurant" a elle-meme une sous-categorie ("Fast-food") : la reparenter
        // sous "Loisirs" doit aussi retyper cette sous-categorie (petit-enfant du
        // nouveau parent), pas seulement "Restaurant" elle-meme.
        Categorie existing = categorie(1L, "Restaurant", TypeCategorie.EXPENSE, null);
        Categorie grandchild = categorie(3L, "Fast-food", TypeCategorie.EXPENSE, existing);
        Categorie newParent = categorie(2L, "Loisirs", TypeCategorie.BOTH, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(newParent));
        when(categoryRepository.findByParent(existing)).thenReturn(List.of(grandchild));
        when(categoryRepository.findByParent(grandchild)).thenReturn(List.of());
        CategorieDTO payload = CategorieDTO.builder().name("Restaurant").parentId(2L).type(TypeCategorie.INCOME).build();

        service.update(1L, payload);

        assertThat(grandchild.getType()).isEqualTo(TypeCategorie.BOTH);
    }

    // --- delete ---

    @Test
    void delete_detacheDabordLesTiersQuiLavaientCommeCategorieParDefautPuisSupprimeLaCategorie() {
        Categorie category = categorie(1L, "Frais bancaires", TypeCategorie.EXPENSE, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));

        service.delete(1L);

        verify(payeeRepository).clearCategory(category);
        verify(categoryRepository).delete(category);
    }

    @Test
    void delete_detacheAussiLesTiersDesSousCategoriesSupprimeesEnCascade() {
        Categorie root = categorie(1L, "Loisirs", TypeCategorie.EXPENSE, null);
        Categorie child = categorie(2L, "Cinema", TypeCategorie.EXPENSE, root);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(root));
        when(categoryRepository.findByParent(root)).thenReturn(List.of(child));

        service.delete(1L);

        verify(payeeRepository).clearCategory(root);
        verify(payeeRepository).clearCategory(child);
        verify(categoryRepository).delete(root);
    }

    @Test
    void delete_categorieUtiliseeParUneSousCategorie_estRefuseeSansRienModifier() {
        Categorie root = categorie(1L, "Loisirs", TypeCategorie.EXPENSE, null);
        Categorie child = categorie(2L, "Cinema", TypeCategorie.EXPENSE, root);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(root));
        when(categoryRepository.findByParent(root)).thenReturn(List.of(child));
        when(transactionRepository.countByCategoryIn(List.of(root, child))).thenReturn(7L);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sous-catégories")
                .hasMessageContaining("7 transaction(s)")
                .hasMessageContaining("Fusionnez");
        verifyNoInteractions(payeeRepository);
        verify(categoryRepository, never()).delete(any(Categorie.class));
    }

    // --- validation ---

    @Test
    void merge_refuseUneDemandeSansCibleOuSansSources() {
        assertThatThrownBy(() -> service.merge(List.of(1L), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(null, 1L)).isInstanceOf(IllegalArgumentException.class);
        verify(categoryRepository, never()).delete(any(Categorie.class));
    }

    @Test
    void create_refuseUnNomVide() {
        CategorieDTO payload = CategorieDTO.builder().name("  ").type(TypeCategorie.EXPENSE).build();

        assertThatThrownBy(() -> service.create(payload)).isInstanceOf(IllegalArgumentException.class);
        verify(categoryRepository, never()).save(any(Categorie.class));
    }

    @Test
    void create_refuseUneRacineSansType() {
        CategorieDTO payload = CategorieDTO.builder().name("Divers").build();

        assertThatThrownBy(() -> service.create(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- merge ---

    @Test
    void merge_reaffecteTransactionsTiersEtEnfantsPuisSupprimeLaCategorieSource() {
        Categorie target = categorie(1L, "Alimentation", TypeCategorie.EXPENSE, null);
        Categorie source = categorie(2L, "Courses", TypeCategorie.EXPENSE, null);
        Categorie child = categorie(3L, "Supermarche", TypeCategorie.EXPENSE, source);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(target));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(source));
        when(categoryRepository.findByParent(source)).thenReturn(List.of(child));

        Categorie result = service.merge(List.of(2L), 1L);

        assertThat(result).isEqualTo(target);
        verify(transactionRepository).reassignCategory(source, target);
        verify(payeeRepository).reassignCategory(source, target);
        assertThat(child.getParent()).isEqualTo(target);
        assertThat(child.getType()).isEqualTo(target.getType());
        verify(categoryRepository).delete(source);
    }

    @Test
    void merge_repercuteAussiLeTypeSurLesPetitsEnfantsDesEnfantsReparentes() {
        // "Supermarche" (enfant de la source) a elle-meme une sous-categorie
        // ("Bio") : la fusion doit aussi la retyper, pas seulement "Supermarche".
        Categorie target = categorie(1L, "Alimentation", TypeCategorie.INCOME, null);
        Categorie source = categorie(2L, "Courses", TypeCategorie.EXPENSE, null);
        Categorie child = categorie(3L, "Supermarche", TypeCategorie.EXPENSE, source);
        Categorie grandchild = categorie(4L, "Bio", TypeCategorie.EXPENSE, child);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(target));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(source));
        when(categoryRepository.findByParent(source)).thenReturn(List.of(child));
        when(categoryRepository.findByParent(child)).thenReturn(List.of(grandchild));
        when(categoryRepository.findByParent(grandchild)).thenReturn(List.of());

        service.merge(List.of(2L), 1L);

        assertThat(grandchild.getType()).isEqualTo(TypeCategorie.INCOME);
    }

    @Test
    void merge_ciblePetiteFilleDeLaSource_rejeteeAvantDeCreerUnCycle() {
        // Fusionner "Restaurant" (source) dans "Restaurant:Fast-food" (target, enfant de source)
        // creerait un cycle parent/enfant (target deviendrait son propre parent) : doit etre rejete.
        Categorie source = categorie(1L, "Restaurant", TypeCategorie.EXPENSE, null);
        Categorie target = categorie(2L, "Fast-food", TypeCategorie.EXPENSE, source);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(source));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.merge(List.of(1L), 2L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionRepository, payeeRepository);
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void merge_ignoreLidNulleEtLidIdentiqueALaCibleSansLesTraiterCommeSource() {
        Categorie target = categorie(1L, "Alimentation", TypeCategorie.EXPENSE, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(target));

        service.merge(Arrays.asList(1L, null), 1L);

        verifyNoInteractions(transactionRepository, payeeRepository);
        verify(categoryRepository, never()).delete(any());
    }

    // --- findOrCreateByName ---

    @Test
    void findOrCreateByName_blancOuNull_renvoieNullSansAccederAuDepot() {
        assertThat(service.findOrCreateByName(null)).isNull();
        assertThat(service.findOrCreateByName("   ")).isNull();
        verifyNoInteractions(categoryRepository);
    }

    @Test
    void findOrCreateByName_nomRacineExistant_renvoieLaCategorieExistanteSansEnCreer() {
        Categorie existing = categorie(1L, "Alimentation", TypeCategorie.EXPENSE, null);
        when(categoryRepository.findByNameIgnoreCaseAndParentIsNull("Alimentation")).thenReturn(Optional.of(existing));

        Categorie result = service.findOrCreateByName("Alimentation");

        assertThat(result).isEqualTo(existing);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void findOrCreateByName_nomRacineInconnu_creeUneNouvelleCategorie() {
        when(categoryRepository.findByNameIgnoreCaseAndParentIsNull("Loisirs")).thenReturn(Optional.empty());

        Categorie result = service.findOrCreateByName("Loisirs");

        assertThat(result.getName()).isEqualTo("Loisirs");
        assertThat(result.getType()).isEqualTo(TypeCategorie.EXPENSE);
        verify(categoryRepository).save(result);
    }

    @Test
    void findOrCreateByName_nomRacineInconnuAvecMontantPositif_creeUneCategorieDeTypeRevenu() {
        when(categoryRepository.findByNameIgnoreCaseAndParentIsNull("Salaire")).thenReturn(Optional.empty());

        Categorie result = service.findOrCreateByName("Salaire", new BigDecimal("1500.00"));

        assertThat(result.getType()).isEqualTo(TypeCategorie.INCOME);
    }

    @Test
    void findOrCreateByName_nomRacineInconnuAvecMontantNegatif_creeUneCategorieDeTypeDepense() {
        when(categoryRepository.findByNameIgnoreCaseAndParentIsNull("Loisirs")).thenReturn(Optional.empty());

        Categorie result = service.findOrCreateByName("Loisirs", new BigDecimal("-20.00"));

        assertThat(result.getType()).isEqualTo(TypeCategorie.EXPENSE);
    }

    @Test
    void findOrCreateByName_nomRacineExistantAvecMontantPositif_neModifiePasLeTypeDeLaCategorieExistante() {
        Categorie existing = categorie(9L, "Loisirs", TypeCategorie.EXPENSE, null);
        when(categoryRepository.findByNameIgnoreCaseAndParentIsNull("Loisirs")).thenReturn(Optional.of(existing));

        Categorie result = service.findOrCreateByName("Loisirs", new BigDecimal("50.00"));

        assertThat(result.getType()).isEqualTo(TypeCategorie.EXPENSE);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void findOrCreateByName_formatParentEnfant_creeLeParentSiAbsentPuisLEnfantSousLuiEtHeriteSonType() {
        when(categoryRepository.findByNameIgnoreCaseAndParentIsNull("Alimentation")).thenReturn(Optional.empty());
        when(categoryRepository.findByNameIgnoreCaseAndParent(eq("Restaurant"), any())).thenReturn(Optional.empty());

        Categorie result = service.findOrCreateByName("Alimentation:Restaurant");

        assertThat(result.getName()).isEqualTo("Restaurant");
        assertThat(result.getParent()).isNotNull();
        assertThat(result.getParent().getName()).isEqualTo("Alimentation");
        assertThat(result.getType()).isEqualTo(result.getParent().getType());
        verify(categoryRepository, times(2)).save(any());
    }

    @Test
    void findOrCreateByName_formatParentEnfantDejaExistant_renvoieLEnfantExistantSansEnCreerUnNouveau() {
        Categorie parent = categorie(1L, "Alimentation", TypeCategorie.EXPENSE, null);
        Categorie child = categorie(2L, "Restaurant", TypeCategorie.EXPENSE, parent);
        when(categoryRepository.findByNameIgnoreCaseAndParentIsNull("Alimentation")).thenReturn(Optional.of(parent));
        when(categoryRepository.findByNameIgnoreCaseAndParent("Restaurant", parent)).thenReturn(Optional.of(child));

        Categorie result = service.findOrCreateByName("Alimentation:Restaurant");

        assertThat(result).isEqualTo(child);
        verify(categoryRepository, never()).save(any());
    }
}

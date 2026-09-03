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
        CategorieDTO payload = CategorieDTO.builder().name("Restaurant").parentId(2L).type(TypeCategorie.INCOME).build();

        Categorie result = service.update(1L, payload);

        assertThat(result.getParent()).isEqualTo(newParent);
        assertThat(result.getType()).isEqualTo(TypeCategorie.BOTH);
        verify(categoryRepository, never()).findByParent(any());
    }

    // --- delete ---

    @Test
    void delete_detacheDabordLesTiersQuiLavaientCommeCategorieParDefautPuisSupprimeLaCategorie() {
        Categorie category = categorie(1L, "Frais bancaires", TypeCategorie.EXPENSE, null);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));

        service.delete(1L);

        verify(payeeRepository).clearCategory(category);
        verify(categoryRepository).deleteById(1L);
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
        verify(categoryRepository).save(result);
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

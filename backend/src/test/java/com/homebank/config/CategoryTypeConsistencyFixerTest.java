package com.homebank.config;

import com.homebank.model.Categorie;
import com.homebank.model.Categorie.TypeCategorie;
import com.homebank.repository.DepotCategorie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie que CategoryTypeConsistencyFixer converge en un seul run(), meme
 * quand findAll() renvoie une chaine de parente dans l'ordre "feuille avant
 * parent" (l'ordre reel n'etant jamais garanti) : sans le passage repete
 * jusqu'a point fixe, la feuille se serait realignee sur le type encore
 * incorrect de son parent direct avant que celui-ci soit lui-meme corrige.
 */
@ExtendWith(MockitoExtension.class)
class CategoryTypeConsistencyFixerTest {

    @Mock
    private DepotCategorie categoryRepository;

    private CategoryTypeConsistencyFixer fixer;

    @BeforeEach
    void setUp() {
        fixer = new CategoryTypeConsistencyFixer(categoryRepository);
    }

    private static Categorie categorie(String name, TypeCategorie type, Categorie parent) {
        Categorie c = new Categorie();
        c.setName(name);
        c.setType(type);
        c.setParent(parent);
        return c;
    }

    @Test
    void run_realigneTouteLaDescendanceEnUnSeulPassageMemeDansLePireOrdreDIteration() {
        Categorie root = categorie("Restaurant", TypeCategorie.INCOME, null); // type "correct" vise
        Categorie mid = categorie("Fast-food", TypeCategorie.EXPENSE, root); // incoherent avec root
        Categorie leaf = categorie("Burger", TypeCategorie.EXPENSE, mid); // incoherent avec mid

        // Ordre delibere "feuille avant parent avant grand-parent", le pire cas :
        // sans boucle a point fixe, leaf se figerait sur le type encore
        // incorrect de mid.
        when(categoryRepository.findAll()).thenReturn(List.of(leaf, mid, root));

        fixer.run(null);

        assertThat(mid.getType()).isEqualTo(TypeCategorie.INCOME);
        assertThat(leaf.getType()).isEqualTo(TypeCategorie.INCOME);
        verify(categoryRepository).saveAll(any());
    }

    @Test
    void run_neFaitRienSiToutEstDejaCoherent() {
        Categorie root = categorie("Alimentation", TypeCategorie.EXPENSE, null);
        Categorie child = categorie("Courses", TypeCategorie.EXPENSE, root);
        when(categoryRepository.findAll()).thenReturn(List.of(root, child));

        fixer.run(null);

        verify(categoryRepository, org.mockito.Mockito.never()).saveAll(any());
    }
}

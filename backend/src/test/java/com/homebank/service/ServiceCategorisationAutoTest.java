package com.homebank.service;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Verifie ServiceCategorisationAuto : categorie unanime des transactions
 * deja categorisees reportee sur le tiers, puis sur ses transactions encore
 * sans categorie, sans jamais modifier un choix existant.
 */
@ExtendWith(MockitoExtension.class)
class ServiceCategorisationAutoTest {

    @Mock
    private DepotTiers payeeRepository;
    @Mock
    private DepotTransaction transactionRepository;

    private ServiceCategorisationAuto categorisationAuto;

    @BeforeEach
    void setUp() {
        categorisationAuto = new ServiceCategorisationAuto(payeeRepository, transactionRepository);
    }

    private static Tiers tiers(Long id, String name, String pattern, BigDecimal amount) {
        Tiers p = new Tiers();
        p.setId(id);
        p.setName(name);
        p.setPattern(pattern);
        p.setAmount(amount);
        return p;
    }

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

        CategorisationAutoResultatDTO result = categorisationAuto.autoCategorize();

        assertThat(carrefour.getCategory()).isSameAs(courses);
        assertThat(sansCategorie.getCategory()).isSameAs(courses);
        assertThat(sansTiers.getCategory()).isNull();
        verify(payeeRepository).saveAll(List.of(carrefour));
        verify(transactionRepository).saveAll(List.of(sansCategorie));
        assertThat(result.getPayeesUpdated()).isEqualTo(1);
        assertThat(result.getTransactionsUpdated()).isEqualTo(1);
        assertThat(result.getPayeeIds()).containsExactly(1L);
        assertThat(result.getTransactionIds()).containsExactly(3L);
    }

    @Test
    void autoCategorize_categorieUnanimeMaisMasquee_neLaReportePas() {
        Categorie resilie = categorie(10L, "Ancien abonnement");
        resilie.setHidden(true);
        Tiers salle = tiers(1L, "Salle de sport", "Salle de sport", null);
        Transaction ancienne = transaction(1L, salle, resilie);
        Transaction sansCategorie = transaction(2L, salle, null);
        when(payeeRepository.findAll()).thenReturn(List.of(salle));
        when(transactionRepository.findAllWithPayeeAndCategory()).thenReturn(List.of(ancienne, sansCategorie));

        CategorisationAutoResultatDTO result = categorisationAuto.autoCategorize();

        assertThat(salle.getCategory()).isNull();
        assertThat(sansCategorie.getCategory()).isNull();
        assertThat(result.getPayeesUpdated()).isZero();
        assertThat(result.getTransactionsUpdated()).isZero();
    }

    @Test
    void autoCategorize_categoriesEnDesaccord_neModifieRien() {
        Tiers amazon = tiers(1L, "Amazon", "Amazon", null);
        Transaction livre = transaction(1L, amazon, categorie(10L, "Livres"));
        Transaction maison = transaction(2L, amazon, categorie(11L, "Maison"));
        Transaction sansCategorie = transaction(3L, amazon, null);
        when(payeeRepository.findAll()).thenReturn(List.of(amazon));
        when(transactionRepository.findAllWithPayeeAndCategory()).thenReturn(List.of(livre, maison, sansCategorie));

        CategorisationAutoResultatDTO result = categorisationAuto.autoCategorize();

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

        CategorisationAutoResultatDTO result = categorisationAuto.autoCategorize();

        assertThat(choixUtilisateur.getCategory()).isSameAs(cadeaux);
        assertThat(sansCategorie.getCategory()).isSameAs(telephone);
        assertThat(bouygues.getCategory()).isSameAs(telephone);
        // Le tiers avait deja sa categorie : il n'est pas compte comme "categorise".
        assertThat(result.getPayeesUpdated()).isZero();
        assertThat(result.getTransactionsUpdated()).isEqualTo(1);
    }
}

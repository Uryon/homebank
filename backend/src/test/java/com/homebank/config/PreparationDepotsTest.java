package com.homebank.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.data.jpa.repository.JpaRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie que les depots sont prepares sur des fils a part : demander les
 * depots eux-memes au fil principal (getBeansOfType) les construisait tous en
 * serie avant l'ouverture du port (~0,6 s de demarrage en plus).
 */
class PreparationDepotsTest {

    private static final long DELAI_FILS_MS = 2000;

    @Test
    @SuppressWarnings("unchecked")
    void prepareChaqueDepotSurSonFilSansLeConstruireSurLeFilPrincipal() {
        ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);
        JpaRepository<Object, Long> comptes = mock(JpaRepository.class);
        JpaRepository<Object, Long> transactions = mock(JpaRepository.class);
        when(beanFactory.getBeanNamesForType(JpaRepository.class, false, false))
                .thenReturn(new String[] {"depotCompte", "depotTransaction"});
        when(beanFactory.getBean("depotCompte", JpaRepository.class)).thenReturn(comptes);
        when(beanFactory.getBean("depotTransaction", JpaRepository.class)).thenReturn(transactions);

        new PreparationDepots(beanFactory).afterSingletonsInstantiated();

        verify(comptes, timeout(DELAI_FILS_MS)).count();
        verify(transactions, timeout(DELAI_FILS_MS)).count();
        verify(beanFactory, never()).getBeansOfType(any(), anyBoolean(), anyBoolean());
        verify(beanFactory, never()).getBeansOfType(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unDepotEnEchecNEmpechePasLesAutres() {
        ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);
        JpaRepository<Object, Long> comptes = mock(JpaRepository.class);
        when(beanFactory.getBeanNamesForType(JpaRepository.class, false, false))
                .thenReturn(new String[] {"depotCasse", "depotCompte"});
        when(beanFactory.getBean("depotCasse", JpaRepository.class))
                .thenThrow(new IllegalStateException("requete invalide"));
        when(beanFactory.getBean("depotCompte", JpaRepository.class)).thenReturn(comptes);

        new PreparationDepots(beanFactory).afterSingletonsInstantiated();

        verify(comptes, timeout(DELAI_FILS_MS)).count();
    }
}

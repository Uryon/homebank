package com.homebank.service;

import com.homebank.dto.CompteDTO;
import com.homebank.model.Compte;
import com.homebank.repository.DepotCompte;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.TypeCompte;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie ServiceCompte, qui n'etait couvert par aucun test jusqu'ici :
 * la mise a jour des champs et surtout le calcul du solde courant
 * (allBalances), utilise par le tableau de bord.
 */
@ExtendWith(MockitoExtension.class)
class ServiceCompteTest {

    @Mock
    private DepotCompte accountRepository;
    @Mock
    private DepotTransaction transactionRepository;

    private ServiceCompte service;

    @BeforeEach
    void setUp() {
        service = new ServiceCompte(accountRepository, transactionRepository);
    }

    private static Compte compte(Long id, String initialBalance) {
        Compte c = new Compte();
        c.setId(id);
        c.setInitialBalance(new BigDecimal(initialBalance));
        return c;
    }

    @Test
    void findById_leveNoSuchElementExceptionSiLeCompteEstIntrouvable() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void create_reprendTousLesChampsDuPayload() {
        CompteDTO payload = CompteDTO.builder()
                .name("Livret A")
                .type(TypeCompte.SAVINGS)
                .initialBalance(new BigDecimal("500.00"))
                .currency("EUR")
                .notes("Epargne")
                .archived(false)
                .build();
        when(accountRepository.save(any(Compte.class))).thenAnswer(inv -> inv.getArgument(0));

        Compte result = service.create(payload);

        assertThat(result.getName()).isEqualTo("Livret A");
        assertThat(result.getType()).isEqualTo(TypeCompte.SAVINGS);
        assertThat(result.getInitialBalance()).isEqualByComparingTo("500.00");
        assertThat(result.getCurrency()).isEqualTo("EUR");
        assertThat(result.getNotes()).isEqualTo("Epargne");
        assertThat(result.isArchived()).isFalse();
    }

    @Test
    void update_ecraseLesChampsExistantsDuCompteTrouve() {
        Compte existing = compte(1L, "100.00");
        existing.setName("Ancien nom");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountRepository.save(any(Compte.class))).thenAnswer(inv -> inv.getArgument(0));

        CompteDTO payload = CompteDTO.builder()
                .name("Nouveau nom")
                .type(TypeCompte.CHECKING)
                .initialBalance(new BigDecimal("200.00"))
                .currency("EUR")
                .archived(true)
                .build();

        Compte result = service.update(1L, payload);

        assertThat(result.getName()).isEqualTo("Nouveau nom");
        assertThat(result.getInitialBalance()).isEqualByComparingTo("200.00");
        assertThat(result.isArchived()).isTrue();
    }

    // --- allBalances : solde initial + transactions deja survenues ---

    @Test
    void allBalances_additionneLeSoldeInitialEtLaSommeDesTransactionsDejaSurvenues() {
        Compte account = compte(1L, "1000.00");
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(1L), any())).thenReturn(new BigDecimal("-150.00"));

        Map<Long, BigDecimal> balances = service.allBalances();

        assertThat(balances.get(1L)).isEqualByComparingTo("850.00");
    }

    @Test
    void allBalances_calculeChaqueCompteIndependammentSansRechargerParId() {
        Compte compte1 = compte(1L, "1000.00");
        Compte compte2 = compte(2L, "0.00");
        when(accountRepository.findAll()).thenReturn(List.of(compte1, compte2));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(1L), any())).thenReturn(new BigDecimal("50.00"));
        when(transactionRepository.sumAmountByAccountIdAsOf(eq(2L), any())).thenReturn(new BigDecimal("300.00"));

        Map<Long, BigDecimal> balances = service.allBalances();

        assertThat(balances).hasSize(2);
        assertThat(balances.get(1L)).isEqualByComparingTo("1050.00");
        assertThat(balances.get(2L)).isEqualByComparingTo("300.00");
        verify(accountRepository, never()).findById(any());
    }
}

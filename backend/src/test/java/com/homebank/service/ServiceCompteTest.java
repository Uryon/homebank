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
    void ensureNotArchived_refuseUnCompteArchiveEtAccepteUnCompteActif() {
        Compte actif = compte(1L, "0");
        Compte archive = compte(2L, "0");
        archive.setName("Ancien livret");
        archive.setArchived(true);

        ServiceCompte.ensureNotArchived(actif);
        assertThatThrownBy(() -> ServiceCompte.ensureNotArchived(archive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ancien livret");
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
    void create_refuseUnNomVideSansRienEnregistrer() {
        CompteDTO payload = CompteDTO.builder().name("   ").currency("EUR").build();

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nom");
        verify(accountRepository, never()).save(any(Compte.class));
    }

    @Test
    void create_appliqueLesValeursParDefautEtNormaliseLaDevise() {
        CompteDTO payload = CompteDTO.builder().name("  Livret  ").currency(" usd ").build();
        when(accountRepository.save(any(Compte.class))).thenAnswer(inv -> inv.getArgument(0));

        Compte result = service.create(payload);

        assertThat(result.getName()).isEqualTo("Livret");
        assertThat(result.getType()).isEqualTo(TypeCompte.CHECKING);
        assertThat(result.getInitialBalance()).isEqualByComparingTo("0");
        assertThat(result.getCurrency()).isEqualTo("USD");
    }

    @Test
    void normalizeCurrency_refuseUnCodeInvalideEtDonneEurParDefaut() {
        assertThat(ServiceCompte.normalizeCurrency(null)).isEqualTo("EUR");
        assertThat(ServiceCompte.normalizeCurrency("")).isEqualTo("EUR");
        assertThatThrownBy(() -> ServiceCompte.normalizeCurrency("EURO")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceCompte.normalizeCurrency("E1R")).isInstanceOf(IllegalArgumentException.class);
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
        when(transactionRepository.sumAmountGroupedByAccountIdAsOf(any()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, new BigDecimal("-150.00")}));

        Map<Long, BigDecimal> balances = service.allBalances();

        assertThat(balances.get(1L)).isEqualByComparingTo("850.00");
    }

    @Test
    void allBalances_calculeTousLesComptesEnUneSeuleRequeteDeSomme() {
        Compte compte1 = compte(1L, "1000.00");
        Compte compte2 = compte(2L, "0.00");
        when(accountRepository.findAll()).thenReturn(List.of(compte1, compte2));
        when(transactionRepository.sumAmountGroupedByAccountIdAsOf(any())).thenReturn(List.of(
                new Object[]{1L, new BigDecimal("50.00")},
                new Object[]{2L, new BigDecimal("300.00")}));

        Map<Long, BigDecimal> balances = service.allBalances();

        assertThat(balances).hasSize(2);
        assertThat(balances.get(1L)).isEqualByComparingTo("1050.00");
        assertThat(balances.get(2L)).isEqualByComparingTo("300.00");
        verify(accountRepository, never()).findById(any());
        verify(transactionRepository, never()).sumAmountByAccountIdAsOf(any(), any());
    }

    @Test
    void allBalances_compteSansTransactionGardeSonSoldeInitial() {
        when(accountRepository.findAll()).thenReturn(List.of(compte(3L, "420.00")));
        when(transactionRepository.sumAmountGroupedByAccountIdAsOf(any())).thenReturn(List.of());

        Map<Long, BigDecimal> balances = service.allBalances();

        assertThat(balances.get(3L)).isEqualByComparingTo("420.00");
    }
    @Test
    void delete_refuseDeSupprimerUnCompteQuiContientDesTransactions() {
        Compte account = compte(7L, "250.00");
        account.setName("Compte courant");
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(transactionRepository.countByAccount_Id(7L)).thenReturn(12L);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12 transaction(s)")
                .hasMessageContaining("Archivez-le");

        verify(accountRepository, never()).delete(any(Compte.class));
    }

    @Test
    void delete_supprimeUnCompteVide() {
        Compte account = compte(8L, "0.00");
        when(accountRepository.findById(8L)).thenReturn(Optional.of(account));
        when(transactionRepository.countByAccount_Id(8L)).thenReturn(0L);

        service.delete(8L);

        verify(accountRepository).delete(account);
    }

}

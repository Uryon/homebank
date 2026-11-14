package com.homebank.service;

import com.homebank.dto.RapprochementDTO;
import com.homebank.dto.ValidationRapprochementDTO;
import com.homebank.model.Compte;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Rapprochement bancaire : comparaison au solde du releve et validation des operations rapprochees. */
@ExtendWith(MockitoExtension.class)
class ServiceRapprochementTest {

    private static final LocalDate DATE_RELEVE = LocalDate.of(2026, 10, 31);

    @Mock
    private ServiceCompte accountService;
    @Mock
    private DepotTransaction transactionRepository;

    private ServiceRapprochement service;
    private final Compte compte = new Compte();

    @BeforeEach
    void setUp() {
        service = new ServiceRapprochement(accountService, transactionRepository);
        compte.setId(1L);
        compte.setInitialBalance(new BigDecimal("100.00"));
    }

    private static Transaction enAttente(Long id, String amount) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setAmount(new BigDecimal(amount));
        t.setValidated(false);
        return t;
    }

    @Test
    void compare_calculeLEcartEntreLeReleveEtLeSoldeALaDateDuReleve() {
        when(accountService.findById(1L)).thenReturn(compte);
        when(transactionRepository.sumAmountByAccountIdAsOf(1L, DATE_RELEVE)).thenReturn(new BigDecimal("400.00"));
        when(transactionRepository.findPendingByAccountIdUpTo(1L, DATE_RELEVE))
                .thenReturn(List.of(enAttente(1L, "-20.00"), enAttente(2L, "-5.50")));

        RapprochementDTO result = service.compare(1L, DATE_RELEVE, new BigDecimal("480.00"));

        assertThat(result.bookBalance()).isEqualByComparingTo("500.00");
        assertThat(result.difference()).isEqualByComparingTo("-20.00");
        assertThat(result.pendingCount()).isEqualTo(2);
        assertThat(result.pendingTotal()).isEqualByComparingTo("-25.50");
    }

    @Test
    void compare_soldeConcordant_ecartNul() {
        when(accountService.findById(1L)).thenReturn(compte);
        when(transactionRepository.sumAmountByAccountIdAsOf(1L, DATE_RELEVE)).thenReturn(new BigDecimal("400.00"));
        when(transactionRepository.findPendingByAccountIdUpTo(1L, DATE_RELEVE)).thenReturn(List.of());

        RapprochementDTO result = service.compare(1L, DATE_RELEVE, new BigDecimal("500.00"));

        assertThat(result.difference()).isZero();
    }

    @Test
    void compare_refuseUneDateFutureOuUnSoldeManquant() {
        LocalDate demain = LocalDate.now().plusDays(1);
        BigDecimal solde = BigDecimal.TEN;
        assertThatThrownBy(() -> service.compare(1L, demain, solde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("futur");
        assertThatThrownBy(() -> service.compare(1L, DATE_RELEVE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("solde");
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void validatePendingUpTo_valideLesOperationsEnAttenteEtLAutreCoteDesVirements() {
        Transaction achat = enAttente(1L, "-20.00");
        Transaction virementDebit = enAttente(2L, "-50.00");
        Transaction virementCredit = enAttente(3L, "50.00");
        virementDebit.setTransferTransaction(virementCredit);
        virementCredit.setTransferTransaction(virementDebit);
        when(accountService.findById(1L)).thenReturn(compte);
        when(transactionRepository.findPendingByAccountIdUpTo(1L, DATE_RELEVE)).thenReturn(List.of(achat, virementDebit));

        ValidationRapprochementDTO result = service.validatePendingUpTo(1L, DATE_RELEVE);

        assertThat(result.validatedCount()).isEqualTo(2);
        assertThat(achat.isValidated()).isTrue();
        assertThat(virementDebit.isValidated()).isTrue();
        assertThat(virementCredit.isValidated()).isTrue();
        verify(transactionRepository).saveAll(List.of(achat, virementDebit));
    }

    @Test
    void validatePendingUpTo_compteInexistant_neValideRien() {
        when(accountService.findById(9L)).thenThrow(new NoSuchElementException("Compte introuvable : 9"));

        assertThatThrownBy(() -> service.validatePendingUpTo(9L, DATE_RELEVE))
                .isInstanceOf(NoSuchElementException.class);
        verify(transactionRepository, never()).saveAll(any());
    }
}

package com.homebank.service;

import com.homebank.dto.DemandeVirementDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.ModePaiement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.homebank.service.FabriqueTransactions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifie ServiceOperationsCompte : virement entre deux comptes (deux
 * transactions liees, meme devise) et ajustement du solde calcule sur la
 * valeur reelle d'un compte.
 */
@ExtendWith(MockitoExtension.class)
class ServiceOperationsCompteTest {

    @Mock
    private DepotTransaction transactionRepository;
    @Mock
    private ServiceCompte accountService;

    private ServiceOperationsCompte operationsCompte;

    @BeforeEach
    void setUp() {
        operationsCompte = new ServiceOperationsCompte(transactionRepository, accountService, new MappeurTransaction());
    }

    private static Compte compteArchive(Long id) {
        Compte c = compte(id);
        c.setName("Ancien livret");
        c.setArchived(true);
        return c;
    }

    @Test
    void createTransfer_refuseUnMontantNulUneFoisArrondi() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(new BigDecimal("0.004"));

        assertThatThrownBy(() -> operationsCompte.createTransfer(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positif");
    }

    @Test
    void createTransfer_leveUneExceptionSiUnCompteManque() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> operationsCompte.createTransfer(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTransfer_leveUneExceptionSiMemeCompteSourceEtDestination() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(1L);
        payload.setAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> operationsCompte.createTransfer(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTransfer_leveUneExceptionSiMontantNulOuNegatifOuZero() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> operationsCompte.createTransfer(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTransfer_refuseDesComptesDansDesDevisesDifferentes() {
        Compte euro = compte(1L);
        euro.setCurrency("EUR");
        Compte dollar = compte(2L);
        dollar.setCurrency("USD");
        when(accountService.findById(1L)).thenReturn(euro);
        when(accountService.findById(2L)).thenReturn(dollar);

        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> operationsCompte.createTransfer(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("devises différentes");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void createTransfer_accepteDesCodesDeviseIdentiquesSansTenirCompteDeLaCasse() {
        Compte source = compte(1L);
        source.setCurrency("EUR");
        Compte destination = compte(2L);
        destination.setCurrency("eur");
        when(accountService.findById(1L)).thenReturn(source);
        when(accountService.findById(2L)).thenReturn(destination);
        AtomicLong idSeq = new AtomicLong(1);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(idSeq.getAndIncrement());
            return t;
        });

        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(new BigDecimal("50.00"));

        assertThat(operationsCompte.createTransfer(payload)).hasSize(2);
    }

    @Test
    void createTransfer_creeUnDebitEtUnCreditLiesEtValidesEtExclusDesRapports() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(accountService.findById(2L)).thenReturn(compte(2L));
        AtomicLong idSeq = new AtomicLong(1);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            if (t.getId() == null) t.setId(idSeq.getAndIncrement());
            return t;
        });

        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(new BigDecimal("50.00"));
        payload.setDate(LocalDate.of(2026, 1, 10));

        List<TransactionDTO> result = operationsCompte.createTransfer(payload);

        assertThat(result).hasSize(2);
        TransactionDTO debit = result.get(0);
        TransactionDTO credit = result.get(1);
        assertThat(debit.getAmount()).isEqualByComparingTo("-50.00");
        assertThat(credit.getAmount()).isEqualByComparingTo("50.00");
        assertThat(debit.getAccountId()).isEqualTo(1L);
        assertThat(credit.getAccountId()).isEqualTo(2L);
        assertThat(debit.getPaymentMode()).isEqualTo(ModePaiement.TRANSFER);
        assertThat(credit.getPaymentMode()).isEqualTo(ModePaiement.TRANSFER);
        assertThat(debit.isValidated()).isTrue();
        assertThat(credit.isValidated()).isTrue();
        assertThat(debit.getTransferTransactionId()).isEqualTo(credit.getId());
        assertThat(credit.getTransferTransactionId()).isEqualTo(debit.getId());
    }

    /** Annulation de la suppression d'un virement en attente : il est recree en attente, pas valide. */
    @Test
    void createTransfer_recreeUnVirementEnAttenteQuandLaDemandeLePrecise() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(accountService.findById(2L)).thenReturn(compte(2L));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(new BigDecimal("25.00"));
        payload.setDate(LocalDate.of(2026, 1, 10));
        payload.setValidated(false);

        List<TransactionDTO> result = operationsCompte.createTransfer(payload);

        assertThat(result).extracting(TransactionDTO::isValidated).containsExactly(false, false);
    }

    @Test
    void adjustAccountBalance_exigeUneDateEtUnSoldeReel() {
        LocalDate hier = LocalDate.now().minusDays(1);

        assertThatThrownBy(() -> operationsCompte.adjustAccountBalance(1L, null, new BigDecimal("100.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La date est obligatoire.");
        assertThatThrownBy(() -> operationsCompte.adjustAccountBalance(1L, hier, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Le solde réel est obligatoire.");
        verifyNoInteractions(accountService, transactionRepository);
    }

    @Test
    void adjustAccountBalance_creeUnAjustementValideDuMontantDeLEcart() {
        Compte placement = compte(3L);
        placement.setInitialBalance(new BigDecimal("15000.00"));
        LocalDate date = LocalDate.of(2026, 11, 1);
        when(accountService.findById(3L)).thenReturn(placement);
        when(transactionRepository.sumAmountByAccountIdAsOf(3L, date)).thenReturn(new BigDecimal("1666.74"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = operationsCompte.adjustAccountBalance(3L, date, new BigDecimal("17250.00"));

        assertThat(result.getAmount()).isEqualByComparingTo("583.26");
        assertThat(result.isBalanceAdjustment()).isTrue();
        assertThat(result.isValidated()).isTrue();
        assertThat(result.getPayee()).isEqualTo("Ajustement de solde");
        assertThat(result.getDate()).isEqualTo(date);
    }

    @Test
    void adjustAccountBalance_refuseSansEcartOuALaDateFuture() {
        Compte compte = compte(1L);
        LocalDate date = LocalDate.of(2026, 11, 1);
        when(accountService.findById(1L)).thenReturn(compte);
        when(transactionRepository.sumAmountByAccountIdAsOf(1L, date)).thenReturn(new BigDecimal("100.00"));
        BigDecimal memeSolde = new BigDecimal("100.00");
        LocalDate demain = LocalDate.now().plusDays(1);

        assertThatThrownBy(() -> operationsCompte.adjustAccountBalance(1L, date, memeSolde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aucun ajustement");
        assertThatThrownBy(() -> operationsCompte.adjustAccountBalance(1L, demain, memeSolde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("futur");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void createTransfer_refuseUnCompteDestinationArchive() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(accountService.findById(2L)).thenReturn(compteArchive(2L));

        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> operationsCompte.createTransfer(payload)).isInstanceOf(IllegalStateException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }
}

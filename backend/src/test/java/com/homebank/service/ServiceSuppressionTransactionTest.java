package com.homebank.service;

import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotLigneImportee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import com.homebank.utils.ModePaiement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.homebank.service.FabriqueTransactions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Verifie ServiceSuppressionTransaction : suppression unitaire, groupee et
 * des operations en attente, jumelle d'un virement emportee avec lui,
 * empreinte de suppression, annulation d'un import et nettoyage des tiers
 * devenus orphelins.
 */
@ExtendWith(MockitoExtension.class)
class ServiceSuppressionTransactionTest {

    @Mock
    private DepotTransaction transactionRepository;
    @Mock
    private DepotTransactionSupprimee deletedTransactionRepository;
    @Mock
    private DepotLigneImportee importedLineRepository;
    @Mock
    private ServiceTiers payeeService;

    private ServiceSuppressionTransaction suppressions;

    @BeforeEach
    void setUp() {
        suppressions = new ServiceSuppressionTransaction(transactionRepository, deletedTransactionRepository,
                new ElagageTiersOrphelins(payeeService), importedLineRepository);
    }

    /** Virement 1 -> 2 de 50 € le 10/01/2026 : debit (id 1) et credit (id 2) relies entre eux. */
    private Transaction[] virementDe50() {
        Transaction debit = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-50.00");
        debit.setId(1L);
        debit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setValidated(true);
        Transaction credit = transaction(compte(2L), null, LocalDate.of(2026, 1, 10), "50.00");
        credit.setId(2L);
        credit.setPaymentMode(ModePaiement.TRANSFER);
        credit.setValidated(true);
        debit.setTransferTransaction(credit);
        credit.setTransferTransaction(debit);
        return new Transaction[] {debit, credit};
    }

    private Transaction transactionAvecTiers(Long id, Tiers payee) {
        Transaction t = transaction(compte(1L), payee, LocalDate.of(2026, 3, 1), "-12.00");
        t.setId(id);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void delete_supprimeAussiLaTransactionJumelleDunVirementEtCasseLaReferenceMutuelleAvant() {
        Transaction debit = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-50.00");
        debit.setId(1L);
        debit.setPaymentMode(ModePaiement.TRANSFER);
        Transaction credit = transaction(compte(2L), null, LocalDate.of(2026, 1, 10), "50.00");
        credit.setId(2L);
        credit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setTransferTransaction(credit);
        credit.setTransferTransaction(debit);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(debit));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        suppressions.delete(1L);

        assertThat(debit.getTransferTransaction()).isNull();
        assertThat(credit.getTransferTransaction()).isNull();
        verify(transactionRepository).deleteAll(List.of(debit, credit));
        verify(deletedTransactionRepository, times(2)).save(any());
    }

    @Test
    void undoCreation_refuseUneSelectionVide() {
        assertThatThrownBy(() -> suppressions.undoCreation(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Aucune transaction sélectionnée.");
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void deleteAllPending_supprimeUnCoteDeVirementEnAttenteDontLaJumelleEstAussiEnAttente() {
        Transaction debit = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-50.00");
        debit.setId(1L);
        debit.setPaymentMode(ModePaiement.TRANSFER);
        Transaction credit = transaction(compte(2L), null, LocalDate.of(2026, 1, 10), "50.00");
        credit.setId(2L);
        credit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setTransferTransaction(credit);
        credit.setTransferTransaction(debit);
        when(transactionRepository.findByValidatedFalseWithTransferTransaction()).thenReturn(List.of(debit));
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L))).thenReturn(List.of(debit));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int count = suppressions.deleteAllPending(null);

        assertThat(count).isEqualTo(2);
        verify(transactionRepository).deleteAll(List.of(debit, credit));
    }

    @Test
    void deleteAllPending_ignoreUnCoteDeVirementEnAttenteDontLaJumelleEstDejaValidee() {
        // Cas limite : un cote d'un virement a ete manuellement remis "en attente"
        // (toggleValidated), sa jumelle restant validee. Le nettoyage global ne
        // doit pas emporter cette jumelle validee, que l'utilisateur n'a jamais
        // demande a supprimer (voir ServiceSuppressionTransaction#deleteAllPending).
        Transaction debit = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-50.00");
        debit.setId(1L);
        debit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setValidated(false);
        Transaction credit = transaction(compte(2L), null, LocalDate.of(2026, 1, 10), "50.00");
        credit.setId(2L);
        credit.setPaymentMode(ModePaiement.TRANSFER);
        credit.setValidated(true);
        debit.setTransferTransaction(credit);
        credit.setTransferTransaction(debit);
        when(transactionRepository.findByValidatedFalseWithTransferTransaction()).thenReturn(List.of(debit));

        int count = suppressions.deleteAllPending(null);

        assertThat(count).isZero();
        verify(transactionRepository, never()).deleteAll(anyList());
        verify(transactionRepository, never()).findAllByIdWithTransferTransaction(anyList());
    }

    @Test
    void deleteAllPending_avecCompte_neSupprimeQueLesTransactionsEnAttenteDeCeCompte() {
        Transaction pending = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-12.00");
        pending.setId(1L);
        when(transactionRepository.findPendingByAccountIdWithTransferTransaction(1L)).thenReturn(List.of(pending));
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L))).thenReturn(List.of(pending));

        int count = suppressions.deleteAllPending(1L);

        assertThat(count).isEqualTo(1);
        verify(transactionRepository).deleteAll(List.of(pending));
        verify(transactionRepository, never()).findByValidatedFalseWithTransferTransaction();
    }

    @Test
    void delete_sansTransactionActive_nettoieLAncienTiersImmediatement() {
        transactionAvecTiers(10L, payee(5L, "SUMUP CAFE"));

        suppressions.delete(10L);

        verify(payeeService).deleteIfOrphan(5L);
    }

    @Test
    void delete_virement_nettoieAussiLeTiersDeLaJumelle() {
        Transaction[] virement = virementDe50();
        virement[0].setPayee(payee(7L, "Virement vers Livret A"));
        virement[1].setPayee(payee(8L, "Virement depuis Compte courant"));
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(virement[0]));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        suppressions.delete(1L);

        verify(payeeService).deleteOrphans(List.of(7L, 8L));
        verify(payeeService, never()).deleteIfOrphan(any());
    }

    @Test
    void delete_echecDuNettoyageGroupe_reprendTiersParTiers() {
        Transaction[] virement = virementDe50();
        virement[0].setPayee(payee(7L, "Virement vers Livret A"));
        virement[1].setPayee(payee(8L, "Virement depuis Compte courant"));
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(virement[0]));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("contrainte")).when(payeeService).deleteOrphans(any());
        doThrow(new IllegalStateException("contrainte")).when(payeeService).deleteIfOrphan(7L);

        suppressions.delete(1L);

        verify(payeeService).deleteIfOrphan(7L);
        verify(payeeService).deleteIfOrphan(8L);
    }

    @Test
    void delete_echecDuNettoyageDuTiers_nEmpechePasLaSuppression() {
        Transaction t = transactionAvecTiers(10L, payee(5L, "SUMUP CAFE"));
        doThrow(new IllegalStateException("contrainte")).when(payeeService).deleteIfOrphan(5L);

        suppressions.delete(10L);

        verify(transactionRepository).deleteAll(List.of(t));
    }

    @Test
    void delete_dansUneTransaction_reporteLeNettoyageApresLeCommit() {
        transactionAvecTiers(10L, payee(5L, "SUMUP CAFE"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            suppressions.delete(10L);
            verify(payeeService, never()).deleteIfOrphan(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            verify(payeeService).deleteIfOrphan(5L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void undoCreation_supprimeSansEmpreinteDeSuppression() {
        Transaction importee = transaction(compte(1L), null, LocalDate.of(2026, 3, 1), "-12.00");
        importee.setId(5L);
        importee.setBankLabel("CB CARREFOUR");
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(5L))).thenReturn(List.of(importee));

        int deleted = suppressions.undoCreation(List.of(5L));

        assertThat(deleted).isEqualTo(1);
        verify(transactionRepository).deleteAll(List.of(importee));
        verify(deletedTransactionRepository, never()).save(any());
        // Import annule : sa ligne de releve redevient importable.
        verify(importedLineRepository).deleteByTransactionIds(List.of(5L));
    }
}

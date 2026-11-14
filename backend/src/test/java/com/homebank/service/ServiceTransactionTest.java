package com.homebank.service;

import com.homebank.dto.DemandeDivisionDTO;
import com.homebank.dto.DemandeVirementDTO;
import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.dto.TransactionBulkUpdateDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Categorie;
import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifie ServiceTransaction#findPeriodicSuggestions : la detection doit
 * rester stricte (au moins 3 occurrences ET intervalles reguliers), pour ne
 * pas suggerer a tort des depenses ponctuelles comme periodiques (voir
 * ideas.txt point 3).
 */
@ExtendWith(MockitoExtension.class)
class ServiceTransactionTest {

    @Mock
    private DepotTransaction transactionRepository;
    @Mock
    private DepotTransactionSupprimee deletedTransactionRepository;
    @Mock
    private ServiceCompte accountService;
    @Mock
    private ServiceCategorie categoryService;
    @Mock
    private ServiceTiers payeeService;

    private ServiceTransaction service;

    @BeforeEach
    void setUp() {
        service = new ServiceTransaction(transactionRepository, deletedTransactionRepository, accountService, categoryService, payeeService, new MappeurTransaction());
    }

    private static Tiers payee(Long id, String name) {
        Tiers p = new Tiers();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static Transaction transaction(Tiers payee, LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.setPayee(payee);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setPaymentMode(ModePaiement.NONE);
        t.setPeriodicExpense(false);
        return t;
    }

    private static Compte compte(Long id) {
        Compte c = new Compte();
        c.setId(id);
        return c;
    }

    private static Transaction transaction(Compte account, Tiers payee, LocalDate date, String amount) {
        Transaction t = transaction(payee, date, amount);
        t.setAccount(account);
        return t;
    }

    @Test
    void findPeriodicSuggestions_detecteUneSerieMensuelleReguliereDeMemeMontant() {
        Tiers netflix = payee(1L, "Netflix");
        List<Transaction> transactions = List.of(
                transaction(netflix, LocalDate.of(2026, 1, 5), "-15.99"),
                transaction(netflix, LocalDate.of(2026, 2, 5), "-15.99"),
                transaction(netflix, LocalDate.of(2026, 3, 5), "-15.99")
        );
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(transactions);

        List<SuggestionPeriodiqueDTO> suggestions = service.findPeriodicSuggestions();

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).getPayee()).isEqualTo("Netflix");
        assertThat(suggestions.get(0).getCount()).isEqualTo(3);
    }

    @Test
    void findPeriodicSuggestions_neSuggerePasUneSerieDeMemeMontantADatesIrregulieres() {
        Tiers amazon = payee(1L, "Amazon");
        List<Transaction> transactions = List.of(
                transaction(amazon, LocalDate.of(2026, 1, 5), "-35.00"),
                transaction(amazon, LocalDate.of(2026, 2, 28), "-35.00"),
                transaction(amazon, LocalDate.of(2026, 4, 17), "-35.00")
        );
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(transactions);

        List<SuggestionPeriodiqueDTO> suggestions = service.findPeriodicSuggestions();

        assertThat(suggestions).isEmpty();
    }

    @Test
    void findPeriodicSuggestions_neSuggerePasEnDessousDeTroisOccurrences() {
        Tiers netflix = payee(1L, "Netflix");
        List<Transaction> transactions = List.of(
                transaction(netflix, LocalDate.of(2026, 1, 5), "-15.99"),
                transaction(netflix, LocalDate.of(2026, 2, 5), "-15.99")
        );
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(transactions);

        List<SuggestionPeriodiqueDTO> suggestions = service.findPeriodicSuggestions();

        assertThat(suggestions).isEmpty();
    }

    @Test
    void findPeriodicSuggestions_detecteUneSerieTrimestrielleAvecToleranceDeMontant() {
        Tiers edf = payee(1L, "EDF");
        List<Transaction> transactions = List.of(
                transaction(edf, LocalDate.of(2025, 7, 10), "-120.00"),
                transaction(edf, LocalDate.of(2025, 10, 12), "-124.00"),
                transaction(edf, LocalDate.of(2026, 1, 9), "-128.00")
        );
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(transactions);

        List<SuggestionPeriodiqueDTO> suggestions = service.findPeriodicSuggestions();

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).getPayee()).isEqualTo("EDF");
    }

    @Test
    void findPeriodicSuggestions_ignoreLesTransactionsDejaMarqueesPeriodiquesEtLesVirements() {
        Tiers netflix = payee(1L, "Netflix");
        Transaction t1 = transaction(netflix, LocalDate.of(2026, 1, 5), "-15.99");
        t1.setPeriodicExpense(true);
        Transaction t2 = transaction(netflix, LocalDate.of(2026, 2, 5), "-15.99");
        t2.setPaymentMode(ModePaiement.TRANSFER);
        Transaction t3 = transaction(netflix, LocalDate.of(2026, 3, 5), "-15.99");
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(t1, t2, t3));

        List<SuggestionPeriodiqueDTO> suggestions = service.findPeriodicSuggestions();

        assertThat(suggestions).isEmpty();
    }

    // --- splitTransaction : la somme des deux parties doit toujours egaler le montant d'origine ---

    @Test
    void splitTransaction_leveUneExceptionSurUnVirement() {
        Transaction original = virementDe50()[0];
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("-30.00"));

        assertThatThrownBy(() -> service.splitTransaction(1L, payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitTransaction_leveUneExceptionSiLeMontantDeLaPremierePartieEstNulOuZero() {
        Transaction original = transaction(compte(1L), null, LocalDate.of(2026, 1, 1), "-100.00");
        original.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.splitTransaction(1L, payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitTransaction_leveUneExceptionSiLeSigneDiffereDuMontantDorigine() {
        Transaction original = transaction(compte(1L), null, LocalDate.of(2026, 1, 1), "-100.00");
        original.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("30.00")); // positif alors que l'original est negatif

        assertThatThrownBy(() -> service.splitTransaction(1L, payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitTransaction_leveUneExceptionSiLaPremierePartieEgaleOuDepasseLeMontantDorigineEnValeurAbsolue() {
        Transaction original = transaction(compte(1L), null, LocalDate.of(2026, 1, 1), "-100.00");
        original.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("-100.00"));

        assertThatThrownBy(() -> service.splitTransaction(1L, payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitTransaction_diviseEnDeuxPartiesDontLaSommeEgaleLeMontantDorigine() {
        Tiers edf = payee(1L, "EDF");
        Compte account = compte(1L);
        Transaction original = transaction(account, edf, LocalDate.of(2026, 1, 15), "-100.00");
        original.setId(1L);
        original.setValidated(true);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("-30.00"));
        payload.setFirstMemo("Part fixe");
        payload.setSecondMemo("Part variable");

        List<TransactionDTO> result = service.splitTransaction(1L, payload);

        assertThat(result).hasSize(2);
        TransactionDTO first = result.get(0);
        TransactionDTO second = result.get(1);
        assertThat(first.getAmount()).isEqualByComparingTo("-30.00");
        assertThat(second.getAmount()).isEqualByComparingTo("-70.00");
        assertThat(first.getAmount().add(second.getAmount())).isEqualByComparingTo("-100.00");
        assertThat(second.getPayee()).isEqualTo("EDF");
        assertThat(second.getAccountId()).isEqualTo(1L);
        assertThat(second.isValidated()).isTrue();
        // Montant du releve conserve pour la detection de doublons a l'import.
        assertThat(original.importKeyAmount()).isEqualByComparingTo("-100.00");
    }

    @Test
    void splitTransaction_secondeDivision_conserveLeMontantDuReleveDOrigine() {
        Transaction original = transaction(compte(1L), payee(1L, "EDF"), LocalDate.of(2026, 1, 15), "-30.00");
        original.setId(1L);
        original.setSplitFromAmount(new BigDecimal("-100.00"));
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("-10.00"));
        service.splitTransaction(1L, payload);

        assertThat(original.importKeyAmount()).isEqualByComparingTo("-100.00");
    }

    // --- createTransfer ---

    @Test
    void createTransfer_leveUneExceptionSiUnCompteManque() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.createTransfer(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTransfer_leveUneExceptionSiMemeCompteSourceEtDestination() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(1L);
        payload.setAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.createTransfer(payload)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createTransfer_leveUneExceptionSiMontantNulOuNegatifOuZero() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createTransfer(payload)).isInstanceOf(IllegalArgumentException.class);
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

        assertThatThrownBy(() -> service.createTransfer(payload))
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

        assertThat(service.createTransfer(payload)).hasSize(2);
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

        List<TransactionDTO> result = service.createTransfer(payload);

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

    @Test
    void toggleValidated_basculeLesDeuxCotesDunVirementEnsemble() {
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
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(debit));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = service.toggleValidated(1L);

        assertThat(result.isValidated()).isFalse();
        assertThat(debit.isValidated()).isFalse();
        assertThat(credit.isValidated()).isFalse();
        verify(transactionRepository).save(credit);
        verify(transactionRepository).save(debit);
    }

    @Test
    void toggleValidated_conserveLeFonctionnementPourUneTransactionNormale() {
        Transaction normal = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-50.00");
        normal.setId(1L);
        normal.setValidated(false);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(normal));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = service.toggleValidated(1L);

        assertThat(result.isValidated()).isTrue();
        assertThat(normal.isValidated()).isTrue();
        verify(transactionRepository, times(1)).save(normal);
    }

    // --- update/delete/bulkUpdate sur un virement : les deux transactions liees ne doivent jamais se desynchroniser ---

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

    private static TransactionDTO.TransactionDTOBuilder modificationVirement(Long accountId, String amount) {
        return TransactionDTO.builder()
                .accountId(accountId)
                .date(LocalDate.of(2026, 1, 15))
                .amount(new BigDecimal(amount))
                .memo("Épargne janvier")
                .paymentMode(ModePaiement.TRANSFER)
                .validated(false);
    }

    @Test
    void update_virement_repercuteDateMontantMemoEtValidationSurLAutreCote() {
        Transaction[] virement = virementDe50();
        Transaction debit = virement[0];
        Transaction credit = virement[1];
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(debit));
        when(accountService.findById(1L)).thenReturn(debit.getAccount());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = service.update(1L, modificationVirement(1L, "-80.00").build());

        assertThat(result.getAmount()).isEqualByComparingTo("-80.00");
        assertThat(result.getTransferTransactionId()).isEqualTo(2L);
        assertThat(credit.getAmount()).isEqualByComparingTo("80.00");
        assertThat(credit.getDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(credit.getMemo()).isEqualTo("Épargne janvier");
        assertThat(credit.isValidated()).isFalse();
        assertThat(credit.getAccount().getId()).isEqualTo(2L);
        verify(transactionRepository).save(credit);
    }

    @Test
    void update_virement_refuseDePlacerLesDeuxCotesSurLeMemeCompte() {
        Transaction[] virement = virementDe50();
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(virement[0]));
        when(accountService.findById(2L)).thenReturn(virement[1].getAccount());

        assertThatThrownBy(() -> service.update(1L, modificationVirement(2L, "-50.00").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differents");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void update_virement_refuseUnMontantNul() {
        Transaction[] virement = virementDe50();
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(virement[0]));
        when(accountService.findById(1L)).thenReturn(virement[0].getAccount());

        assertThatThrownBy(() -> service.update(1L, modificationVirement(1L, "0").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nul");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void update_virement_refuseUnCompteDUneAutreDevise() {
        Transaction[] virement = virementDe50();
        Compte dollar = compte(3L);
        dollar.setCurrency("USD");
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(virement[0]));
        when(accountService.findById(3L)).thenReturn(dollar);

        assertThatThrownBy(() -> service.update(1L, modificationVirement(3L, "-50.00").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("devises différentes");
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

        service.delete(1L);

        assertThat(debit.getTransferTransaction()).isNull();
        assertThat(credit.getTransferTransaction()).isNull();
        verify(transactionRepository).deleteAll(List.of(debit, credit));
        verify(deletedTransactionRepository, times(2)).save(any());
    }

    /**
     * Seul un cote de virement INTERNE (lie a sa jumelle) garde son mode
     * TRANSFER ; « Virement » s'applique a une transaction ordinaire comme
     * n'importe quel autre mode de paiement.
     */
    @Test
    void bulkUpdate_modeDePaiement_ignoreSeulementLesVirementsInternes() {
        Transaction normal = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-20.00");
        normal.setId(10L);
        normal.setPaymentMode(ModePaiement.NONE);
        Transaction transferLeg = virementDe50()[0];
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(10L, 1L))).thenReturn(List.of(normal, transferLeg));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        TransactionBulkUpdateDTO payload = new TransactionBulkUpdateDTO();
        payload.setIds(List.of(10L, 1L));
        payload.setPaymentModeProvided(true);
        payload.setPaymentMode(ModePaiement.CARD);

        service.bulkUpdate(payload);

        assertThat(normal.getPaymentMode()).isEqualTo(ModePaiement.CARD);
        assertThat(transferLeg.getPaymentMode()).isEqualTo(ModePaiement.TRANSFER);
    }

    @Test
    void bulkUpdate_validationDUnCoteDeVirement_valideAussiLAutreCote() {
        Transaction[] virement = virementDe50();
        Transaction debit = virement[0];
        Transaction credit = virement[1];
        debit.setValidated(false);
        credit.setValidated(false);
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L))).thenReturn(List.of(debit));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        TransactionBulkUpdateDTO payload = new TransactionBulkUpdateDTO();
        payload.setIds(List.of(1L));
        payload.setValidatedProvided(true);
        payload.setValidated(true);

        service.bulkUpdate(payload);

        assertThat(debit.isValidated()).isTrue();
        assertThat(credit.isValidated()).isTrue();
    }

    // --- deleteAllPending ---

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

        int count = service.deleteAllPending();

        assertThat(count).isEqualTo(2);
        verify(transactionRepository).deleteAll(List.of(debit, credit));
    }

    @Test
    void deleteAllPending_ignoreUnCoteDeVirementEnAttenteDontLaJumelleEstDejaValidee() {
        // Cas limite : un cote d'un virement a ete manuellement remis "en attente"
        // (toggleValidated), sa jumelle restant validee. Le nettoyage global ne
        // doit pas emporter cette jumelle validee, que l'utilisateur n'a jamais
        // demande a supprimer (voir ServiceTransaction#deleteAllPending).
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

        int count = service.deleteAllPending();

        assertThat(count).isZero();
        verify(transactionRepository, never()).deleteAll(anyList());
        verify(transactionRepository, never()).findAllByIdWithTransferTransaction(anyList());
    }

    // --- adjustAccountBalance ---

    @Test
    void adjustAccountBalance_creeUnAjustementValideDuMontantDeLEcart() {
        Compte placement = compte(3L);
        placement.setInitialBalance(new BigDecimal("15000.00"));
        LocalDate date = LocalDate.of(2026, 11, 1);
        when(accountService.findById(3L)).thenReturn(placement);
        when(transactionRepository.sumAmountByAccountIdAsOf(3L, date)).thenReturn(new BigDecimal("1666.74"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = service.adjustAccountBalance(3L, date, new BigDecimal("17250.00"));

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

        assertThatThrownBy(() -> service.adjustAccountBalance(1L, date, memeSolde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aucun ajustement");
        assertThatThrownBy(() -> service.adjustAccountBalance(1L, demain, memeSolde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("futur");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void splitTransaction_refuseUnAjustementDeSolde() {
        Transaction ajustement = transaction(compte(1L), null, LocalDate.of(2026, 1, 5), "-120.00");
        ajustement.setId(1L);
        ajustement.setBalanceAdjustment(true);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(ajustement));
        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("-20.00"));

        assertThatThrownBy(() -> service.splitTransaction(1L, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ajustement");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    /** Annulation d'une suppression : l'ajustement est recréé avec son marqueur, pas comme une dépense. */
    @Test
    void create_conserveLeMarqueurDAjustementDeSolde() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = service.create(TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(new BigDecimal("-120.00"))
                .balanceAdjustment(true).build());

        assertThat(result.isBalanceAdjustment()).isTrue();
    }

    @Test
    void update_neModifieJamaisLeMarqueurDAjustementDeSolde() {
        Transaction normale = transaction(compte(1L), null, LocalDate.of(2026, 1, 5), "-12.00");
        normale.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(normale));
        when(accountService.findById(1L)).thenReturn(normale.getAccount());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(1L, TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(new BigDecimal("-12.00"))
                .balanceAdjustment(true).build());

        assertThat(normale.isBalanceAdjustment()).isFalse();
    }

    @Test
    void findDuplicateGroups_ignoreLesAjustementsDeSolde() {
        Compte account = compte(1L);
        Transaction a1 = transaction(account, null, LocalDate.of(2026, 1, 5), "50.00");
        a1.setId(1L);
        a1.setBalanceAdjustment(true);
        Transaction a2 = transaction(account, null, LocalDate.of(2026, 1, 5), "50.00");
        a2.setId(2L);
        a2.setBalanceAdjustment(true);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(a1, a2));

        assertThat(service.findDuplicateGroups()).isEmpty();
    }

    // --- findDuplicateGroups ---

    @Test
    void findDuplicateGroups_regroupeCommeCertainMemeCompteDateMontantEtTiers() {
        Compte account = compte(1L);
        Tiers carrefour = payee(1L, "Carrefour");
        Transaction t1 = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        t1.setId(1L);
        Transaction t2 = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        t2.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(t1, t2));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).isCertain()).isTrue();
        assertThat(groups.get(0).getCount()).isEqualTo(2);
    }

    private static Tiers payeeAvecMotif(Long id, String name, String pattern) {
        Tiers p = payee(id, name);
        p.setPattern(pattern);
        return p;
    }

    /** Tiers renommé (« Carrefour ») et libellé brut de la banque qu'il reconnaît : probablement la même opération. */
    @Test
    void findDuplicateGroups_regroupeCommeProbableMemeCompteDateMontantMaisTiersDifferent() {
        Compte account = compte(1L);
        Transaction t1 = transaction(account, payeeAvecMotif(1L, "Carrefour", "Carrefour"), LocalDate.of(2026, 1, 5), "-45.50");
        t1.setId(1L);
        Tiers brut = payeeAvecMotif(2L, "Prelevement CB Carrefour Paris", "Prelevement CB Carrefour Paris");
        Transaction t2 = transaction(account, brut, LocalDate.of(2026, 1, 5), "-45.50");
        t2.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(t1, t2));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).isCertain()).isFalse();
        assertThat(groups.get(0).isReimbursement()).isFalse();
    }

    /** Deux commerçants distincts déjà reconnus, même jour et même montant : deux vraies opérations. */
    @Test
    void findDuplicateGroups_neSignalePasDeuxTiersDistinctsReconnusMemeJourMemeMontant() {
        Compte account = compte(1L);
        Transaction pharmacie = transaction(account, payeeAvecMotif(1L, "Pharmacie", "Pharmacie"),
                LocalDate.of(2026, 11, 12), "-30.00");
        pharmacie.setId(1L);
        Transaction docteur = transaction(account, payeeAvecMotif(2L, "Docteur Trillot", "DR TRILLOT"),
                LocalDate.of(2026, 11, 12), "-30.00");
        docteur.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(pharmacie, docteur));

        assertThat(service.findDuplicateGroups()).isEmpty();
    }

    @Test
    void findDuplicateGroups_signaleUneOperationSansTiersEtUneAvecTiersMemeJourMemeMontant() {
        Compte account = compte(1L);
        Transaction sansTiers = transaction(account, null, LocalDate.of(2026, 1, 5), "-12.00");
        sansTiers.setId(1L);
        Transaction avecTiers = transaction(account, payeeAvecMotif(2L, "Boulangerie", "Boulangerie"),
                LocalDate.of(2026, 1, 5), "-12.00");
        avecTiers.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(sansTiers, avecTiers));

        assertThat(service.findDuplicateGroups()).singleElement()
                .satisfies(g -> assertThat(g.isCertain()).isFalse());
    }

    @Test
    void findDuplicateGroups_ligneImporteeRessaisieALaMainMemeTiers_estUnDoublonCertainListeUneSeuleFois() {
        Compte account = compte(1L);
        Tiers carrefour = payee(1L, "Carrefour");
        Transaction importee = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        importee.setId(1L);
        importee.setBankLabel("ACHAT CB CARREFOUR 04.01.26 CARTE NUMERO 697");
        Transaction manuelle = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        manuelle.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(importee, manuelle));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.isCertain()).isTrue();
            assertThat(g.getCount()).isEqualTo(2);
        });
    }

    @Test
    void findDuplicateGroups_tiersDistinctsMaisMemeLibelleBancaire_estUnDoublonProbable() {
        Compte account = compte(1L);
        Transaction renommee = transaction(account, payee(1L, "Vinted"), LocalDate.of(2026, 1, 5), "-19.90");
        renommee.setId(1L);
        renommee.setBankLabel("ACHAT CB PAYPAL .ryckew 03.01.26");
        Transaction brute = transaction(account, payee(2L, "Boulangerie"), LocalDate.of(2026, 1, 5), "-19.90");
        brute.setId(2L);
        brute.setBankLabel("ACHAT CB PAYPAL .ryckew 03.01.26");
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(renommee, brute));

        assertThat(service.findDuplicateGroups()).singleElement()
                .satisfies(g -> assertThat(g.isCertain()).isFalse());
    }

    @Test
    void create_conserveLeLibelleBancaireDUneTransactionImporteeRestauree() {
        Compte account = compte(1L);
        when(accountService.findById(1L)).thenReturn(account);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(new BigDecimal("-45.50"))
                .bankLabel("ACHAT CB CARREFOUR 04.01.26").build();

        assertThat(service.create(payload).getBankLabel()).isEqualTo("ACHAT CB CARREFOUR 04.01.26");
    }

    @Test
    void findDuplicateGroups_detectePaireRembourseeMemeTiersMontantOppose() {
        Compte account = compte(1L);
        Tiers ami = payee(1L, "Remboursement Ami");
        Transaction depense = transaction(account, ami, LocalDate.of(2026, 1, 5), "-50.00");
        depense.setId(1L);
        Transaction remboursement = transaction(account, ami, LocalDate.of(2026, 1, 20), "50.00");
        remboursement.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(depense, remboursement));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).isReimbursement()).isTrue();
        assertThat(groups.get(0).isCertain()).isFalse();
    }

    // --- applyPayload (via create/update) : la categorie du payload est appliquee telle quelle ---

    @Test
    void create_neReprendPasLaCategorieParDefautDuTiersQuandLePayloadNenFournitAucune() {
        Categorie categorieParDefautDuTiers = new Categorie();
        categorieParDefautDuTiers.setId(9L);
        Tiers carrefour = payee(1L, "Carrefour");
        carrefour.setCategory(categorieParDefautDuTiers);
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(payeeService.resolveOrCreate("Carrefour", new BigDecimal("-10.00"))).thenReturn(carrefour);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L)
                .date(LocalDate.of(2026, 1, 5))
                .amount(new BigDecimal("-10.00"))
                .payee("Carrefour")
                .categoryId(null)
                .build();

        TransactionDTO result = service.create(payload);

        assertThat(result.getCategoryId()).isNull();
    }

    @Test
    void update_effaceLaCategorieSiLePayloadNenFournitPlusAucune() {
        Categorie ancienneCategorie = new Categorie();
        ancienneCategorie.setId(5L);
        Transaction existing = transaction(compte(1L), null, LocalDate.of(2026, 1, 5), "-10.00");
        existing.setId(1L);
        existing.setCategory(ancienneCategorie);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L)
                .date(LocalDate.of(2026, 1, 5))
                .amount(new BigDecimal("-10.00"))
                .categoryId(null)
                .build();

        TransactionDTO result = service.update(1L, payload);

        assertThat(result.getCategoryId()).isNull();
    }

    @Test
    void create_refuseUneTransactionSansDateOuSansMontantAvantDeCreerLeTiers() {
        TransactionDTO sansDate = TransactionDTO.builder()
                .accountId(1L).amount(new BigDecimal("-10.00")).payee("Nouveau tiers").build();
        TransactionDTO sansMontant = TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).payee("Nouveau tiers").build();

        assertThatThrownBy(() -> service.create(sansDate)).hasMessageContaining("date");
        assertThatThrownBy(() -> service.create(sansMontant)).hasMessageContaining("montant");
        verifyNoInteractions(payeeService);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // --- Compte archive : plus de nouvelle transaction ---

    private static Compte compteArchive(Long id) {
        Compte c = compte(id);
        c.setName("Ancien livret");
        c.setArchived(true);
        return c;
    }

    @Test
    void create_refuseUneTransactionSurUnCompteArchive() {
        when(accountService.findById(1L)).thenReturn(compteArchive(1L));

        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L)
                .date(LocalDate.of(2026, 1, 5))
                .amount(new BigDecimal("-10.00"))
                .build();

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archivé");
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void update_autoriseLaModificationDUneTransactionDejaSurUnCompteArchive() {
        Compte archive = compteArchive(1L);
        Transaction existing = transaction(archive, null, LocalDate.of(2026, 1, 5), "-10.00");
        existing.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountService.findById(1L)).thenReturn(archive);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L)
                .date(LocalDate.of(2026, 1, 5))
                .amount(new BigDecimal("-12.00"))
                .build();

        assertThat(service.update(1L, payload).getAmount()).isEqualByComparingTo("-12.00");
    }

    @Test
    void update_refuseDeDeplacerUneTransactionVersUnCompteArchive() {
        Transaction existing = transaction(compte(2L), null, LocalDate.of(2026, 1, 5), "-10.00");
        existing.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountService.findById(1L)).thenReturn(compteArchive(1L));

        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L)
                .date(LocalDate.of(2026, 1, 5))
                .amount(new BigDecimal("-10.00"))
                .build();

        assertThatThrownBy(() -> service.update(1L, payload)).isInstanceOf(IllegalStateException.class);
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

        assertThatThrownBy(() -> service.createTransfer(payload)).isInstanceOf(IllegalStateException.class);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // --- sanitizeMemo ---

    @Test
    void sanitizeMemo_remplaceNullLitteralEtVideParNull() {
        assertThat(ServiceTransaction.sanitizeMemo(null)).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("   ")).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("(NULL)")).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("null")).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("  Courses  ")).isEqualTo("Courses");
    }

    // --- search : choix du filtre de GET /api/transactions ---

    private static final LocalDate DEBUT = LocalDate.of(2026, 1, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 1, 31);

    @Test
    void search_sansParametre_renvoieToutesLesTransactions() {
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of());

        assertThat(service.search(null, null, null, null, null)).isEmpty();
        verify(transactionRepository).findAllByOrderByDateDescIdDesc();
    }

    @Test
    void search_compteEtPeriode_filtreSurLesDeux() {
        Transaction t = transaction(compte(7L), payee(1L, "Loyer"), LocalDate.of(2026, 1, 10), "-800");
        when(transactionRepository.findByAccountIdAndDateBetween(7L, DEBUT, FIN)).thenReturn(List.of(t));

        assertThat(service.search(7L, DEBUT, FIN, null, null)).hasSize(1);
        verify(transactionRepository, never()).findByAccount_IdOrderByDateDescIdDesc(any());
    }

    @Test
    void search_periodeSeule_filtreSurLaPeriode() {
        when(transactionRepository.findByDateBetween(DEBUT, FIN)).thenReturn(List.of());

        service.search(null, DEBUT, FIN, null, null);

        verify(transactionRepository).findByDateBetween(DEBUT, FIN);
    }

    @Test
    void search_montant_utiliseLeFiltreParMontant() {
        when(transactionRepository.findByAmountGreaterThanOrderByDateDescIdDesc(new BigDecimal("100"))).thenReturn(List.of());

        service.search(null, null, null, ">", new BigDecimal("100"));

        verify(transactionRepository).findByAmountGreaterThanOrderByDateDescIdDesc(new BigDecimal("100"));
    }

    @Test
    void search_periodeIncomplete_estRefuseeAuLieuDeToutRenvoyer() {
        assertThatThrownBy(() -> service.search(null, DEBUT, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date de fin");
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void search_periodeInversee_estRefusee() {
        assertThatThrownBy(() -> service.search(null, FIN, DEBUT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postérieure");
    }

    @Test
    void search_operateurSansMontant_estRefuse() {
        assertThatThrownBy(() -> service.search(null, null, null, ">", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opérateur et un montant");
    }

    @Test
    void search_montantEtPeriode_estRefuse() {
        assertThatThrownBy(() -> service.search(null, DEBUT, FIN, "=", BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("combinée");
        verifyNoInteractions(transactionRepository);
    }

    // --- nettoyage des tiers orphelins : jamais dans la transaction de la suppression ---

    private Transaction transactionAvecTiers(Long id, Tiers payee) {
        Transaction t = transaction(compte(1L), payee, LocalDate.of(2026, 3, 1), "-12.00");
        t.setId(id);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void delete_sansTransactionActive_nettoieLAncienTiersImmediatement() {
        transactionAvecTiers(10L, payee(5L, "SUMUP CAFE"));

        service.delete(10L);

        verify(payeeService).deleteIfOrphan(5L);
    }

    @Test
    void delete_virement_nettoieAussiLeTiersDeLaJumelle() {
        Transaction[] virement = virementDe50();
        virement[0].setPayee(payee(7L, "Virement vers Livret A"));
        virement[1].setPayee(payee(8L, "Virement depuis Compte courant"));
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(virement[0]));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.delete(1L);

        verify(payeeService).deleteIfOrphan(7L);
        verify(payeeService).deleteIfOrphan(8L);
    }

    @Test
    void delete_echecDuNettoyageDuTiers_nEmpechePasLaSuppression() {
        Transaction t = transactionAvecTiers(10L, payee(5L, "SUMUP CAFE"));
        doThrow(new IllegalStateException("contrainte")).when(payeeService).deleteIfOrphan(5L);

        service.delete(10L);

        verify(transactionRepository).deleteAll(List.of(t));
    }

    @Test
    void delete_dansUneTransaction_reporteLeNettoyageApresLeCommit() {
        transactionAvecTiers(10L, payee(5L, "SUMUP CAFE"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(10L);
            verify(payeeService, never()).deleteIfOrphan(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            verify(payeeService).deleteIfOrphan(5L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // --- bulkUpdate : changement de tiers en masse ---

    private TransactionBulkUpdateDTO bulkPayee(String payee, Long... ids) {
        TransactionBulkUpdateDTO payload = new TransactionBulkUpdateDTO();
        payload.setIds(List.of(ids));
        payload.setPayeeProvided(true);
        payload.setPayee(payee);
        return payload;
    }

    @Test
    void bulkUpdate_tiers_remplaceLeTiersEtNettoieLesAnciensUneSeuleFoisChacun() {
        Tiers sumup = payee(5L, "SUMUP CAFE");
        Tiers cafe = payee(6L, "Cafe du coin");
        Categorie courses = new Categorie();
        Transaction t1 = transaction(compte(1L), sumup, LocalDate.of(2026, 2, 1), "-3.00");
        t1.setId(1L);
        t1.setCategory(courses);
        Transaction t2 = transaction(compte(1L), sumup, LocalDate.of(2026, 2, 2), "-4.00");
        t2.setId(2L);
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L, 2L))).thenReturn(List.of(t1, t2));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(payeeService.resolveOrCreate(eq("Cafe du coin"), any())).thenReturn(cafe);

        service.bulkUpdate(bulkPayee("Cafe du coin", 1L, 2L));

        assertThat(t1.getPayee()).isEqualTo(cafe);
        assertThat(t2.getPayee()).isEqualTo(cafe);
        // Champ non demande : laisse tel quel.
        assertThat(t1.getCategory()).isSameAs(courses);
        verify(payeeService, times(1)).deleteIfOrphan(5L);
    }

    @Test
    void bulkUpdate_tiersVide_retireLeTiersSansEnCreer() {
        Tiers sumup = payee(5L, "SUMUP CAFE");
        Transaction t1 = transaction(compte(1L), sumup, LocalDate.of(2026, 2, 1), "-3.00");
        t1.setId(1L);
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L))).thenReturn(List.of(t1));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.bulkUpdate(bulkPayee("  ", 1L));

        assertThat(t1.getPayee()).isNull();
        verify(payeeService, never()).resolveOrCreate(any(), any());
        verify(payeeService).deleteIfOrphan(5L);
    }

    @Test
    void bulkUpdate_memeTiers_neDeclenchePasDeNettoyage() {
        Tiers cafe = payee(6L, "Cafe du coin");
        Transaction t1 = transaction(compte(1L), cafe, LocalDate.of(2026, 2, 1), "-3.00");
        t1.setId(1L);
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L))).thenReturn(List.of(t1));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(payeeService.resolveOrCreate(eq("Cafe du coin"), any())).thenReturn(cafe);

        service.bulkUpdate(bulkPayee("Cafe du coin", 1L));

        verify(payeeService, never()).deleteIfOrphan(any());
    }

    // --- marquages definitifs : "pas un doublon", "suggestion periodique ignoree" ---

    @Test
    void markAsNotDuplicate_marqueLesTransactionsDemandees() {
        Transaction t1 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-3.00");
        Transaction t2 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-3.00");
        when(transactionRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(t1, t2));

        service.markAsNotDuplicate(List.of(1L, 2L));

        assertThat(t1.isDuplicateIgnored()).isTrue();
        assertThat(t2.isDuplicateIgnored()).isTrue();
        verify(transactionRepository).saveAll(List.of(t1, t2));
    }

    @Test
    void ignorePeriodicSuggestion_marqueLesTransactionsDemandees() {
        Transaction t1 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-9.99");
        when(transactionRepository.findAllById(List.of(1L))).thenReturn(List.of(t1));

        service.ignorePeriodicSuggestion(List.of(1L));

        assertThat(t1.isPeriodicSuggestionIgnored()).isTrue();
        verify(transactionRepository).saveAll(List.of(t1));
    }

    @Test
    void marquagesDefinitifs_refusentUneSelectionVide() {
        assertThatThrownBy(() -> service.markAsNotDuplicate(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ignorePeriodicSuggestion(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactionRepository);
    }
}

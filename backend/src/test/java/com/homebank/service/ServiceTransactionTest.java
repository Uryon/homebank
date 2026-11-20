package com.homebank.service;

import com.homebank.dto.DemandeAnnulationDivisionDTO;
import com.homebank.dto.DemandeDivisionDTO;
import com.homebank.dto.DemandeVirementDTO;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.homebank.service.FabriqueTransactions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifie ServiceTransaction : saisie, division, virements (les deux cotes
 * restent toujours synchronises), edition et suppression en masse, ajustement
 * de solde et recherche. Detection des periodiques et des doublons : voir
 * ServiceSuggestionPeriodiqueTest et ServiceDoublonTest.
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
    void splitTransaction_arrondiLaPremierePartieAuCentimePourConserverLeTotal() {
        Transaction original = transaction(compte(1L), null, LocalDate.of(2026, 1, 1), "-20.00");
        original.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("-10.005"));

        List<TransactionDTO> result = service.splitTransaction(1L, payload);

        assertThat(result.get(0).getAmount()).isEqualByComparingTo("-10.01");
        assertThat(result.get(1).getAmount()).isEqualByComparingTo("-9.99");
    }

    @Test
    void splitTransaction_refuseUnePremierePartieNulleUneFoisArrondie() {
        Transaction original = transaction(compte(1L), null, LocalDate.of(2026, 1, 1), "-20.00");
        original.setId(1L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(original));

        DemandeDivisionDTO payload = new DemandeDivisionDTO();
        payload.setFirstAmount(new BigDecimal("-0.004"));

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
    void createTransfer_refuseUnMontantNulUneFoisArrondi() {
        DemandeVirementDTO payload = new DemandeVirementDTO();
        payload.setFromAccountId(1L);
        payload.setToAccountId(2L);
        payload.setAmount(new BigDecimal("0.004"));

        assertThatThrownBy(() -> service.createTransfer(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positif");
    }

    private TransactionDTO saisie(String amount) {
        return TransactionDTO.builder()
                .accountId(1L)
                .date(LocalDate.of(2026, 1, 1))
                .amount(new BigDecimal(amount))
                .build();
    }

    @Test
    void create_enregistreEtRenvoieLeMontantAuCentime() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = service.create(saisie("-12.345"));

        assertThat(result.getAmount()).isEqualByComparingTo("-12.35");
    }

    @Test
    void create_refuseUnMontantNulUneFoisArrondi() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        TransactionDTO payload = saisie("-0.004");

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nul");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void update_refuseDePasserAZeroUneLigneImportee() {
        Transaction importee = transaction(compte(1L), null, LocalDate.of(2026, 1, 1), "-10.00");
        importee.setId(1L);
        importee.setBankLabel("CARTE");
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(importee));
        when(accountService.findById(1L)).thenReturn(importee.getAccount());
        TransactionDTO payload = saisie("0.00");

        assertThatThrownBy(() -> service.update(1L, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nul");
    }

    @Test
    void create_refuseUnMontantTropGrandAvantToutEnregistrement() {
        TransactionDTO payload = saisie("1E30");

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Le montant");
        verify(transactionRepository, never()).save(any());
    }

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

        List<TransactionDTO> result = service.createTransfer(payload);

        assertThat(result).extracting(TransactionDTO::isValidated).containsExactly(false, false);
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
                .hasMessageContaining("différents");
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

    @Test
    void bulkUpdate_categorieEtMarqueurs_seulsLesChampsFournisSontModifies() {
        Transaction premiere = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-20.00");
        premiere.setId(10L);
        premiere.setExceptional(true);
        Transaction seconde = transaction(compte(1L), null, LocalDate.of(2026, 1, 11), "-35.00");
        seconde.setId(11L);
        Categorie courses = new Categorie();
        courses.setId(5L);
        when(categoryService.findById(5L)).thenReturn(courses);
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(10L, 11L))).thenReturn(List.of(premiere, seconde));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        TransactionBulkUpdateDTO payload = new TransactionBulkUpdateDTO();
        payload.setIds(List.of(10L, 11L));
        payload.setCategoryIdProvided(true);
        payload.setCategoryId(5L);
        payload.setPeriodicExpenseProvided(true);
        payload.setPeriodicExpense(true);
        // « Exceptionnelle » non fournie : chaque transaction garde sa valeur.
        payload.setExceptional(false);

        service.bulkUpdate(payload);

        assertThat(List.of(premiere, seconde)).allSatisfy(t -> {
            assertThat(t.getCategory()).isSameAs(courses);
            assertThat(t.isPeriodicExpense()).isTrue();
        });
        assertThat(premiere.isExceptional()).isTrue();
        assertThat(seconde.isExceptional()).isFalse();
        verify(categoryService, times(1)).findById(5L);
    }

    @Test
    void bulkUpdate_categorieFournieVide_retireLaCategorieSansLaChercher() {
        Transaction t = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-20.00");
        t.setId(10L);
        t.setCategory(new Categorie());
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(10L))).thenReturn(List.of(t));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        TransactionBulkUpdateDTO payload = new TransactionBulkUpdateDTO();
        payload.setIds(List.of(10L));
        payload.setCategoryIdProvided(true);
        payload.setCategoryId(null);

        service.bulkUpdate(payload);

        assertThat(t.getCategory()).isNull();
        verify(categoryService, never()).findById(any());
    }

    @Test
    void bulkUpdate_refuseUneSelectionVide() {
        TransactionBulkUpdateDTO payload = new TransactionBulkUpdateDTO();
        payload.setIds(List.of());

        assertThatThrownBy(() -> service.bulkUpdate(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Aucune transaction sélectionnée.");
        verifyNoInteractions(transactionRepository);
    }

    // --- Bascules « dépense périodique » et « exceptionnelle » (cases à cocher de la liste) ---

    @Test
    void togglePeriodicExpense_basculeDansLesDeuxSensEtEnregistre() {
        Transaction t = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-15.00");
        t.setId(10L);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(t));
        when(transactionRepository.save(t)).thenReturn(t);

        assertThat(service.togglePeriodicExpense(10L).isPeriodicExpense()).isTrue();
        assertThat(service.togglePeriodicExpense(10L).isPeriodicExpense()).isFalse();
        verify(transactionRepository, times(2)).save(t);
    }

    @Test
    void toggleExceptional_basculeDansLesDeuxSensSansToucherAuxAutresMarqueurs() {
        Transaction t = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-3200.00");
        t.setId(10L);
        t.setPeriodicExpense(true);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(t));
        when(transactionRepository.save(t)).thenReturn(t);

        assertThat(service.toggleExceptional(10L).isExceptional()).isTrue();
        assertThat(t.isPeriodicExpense()).isTrue();
        assertThat(service.toggleExceptional(10L).isExceptional()).isFalse();
    }

    @Test
    void bascules_transactionIntrouvable_signaleeSansRienEnregistrer() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.togglePeriodicExpense(99L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.toggleExceptional(99L)).isInstanceOf(NoSuchElementException.class);
        verify(transactionRepository, never()).save(any());
    }

    // --- Contrôles de saisie côté serveur (le frontend les fait aussi, mais l'API doit les garantir) ---

    @Test
    void adjustAccountBalance_exigeUneDateEtUnSoldeReel() {
        LocalDate hier = LocalDate.now().minusDays(1);

        assertThatThrownBy(() -> service.adjustAccountBalance(1L, null, new BigDecimal("100.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La date est obligatoire.");
        assertThatThrownBy(() -> service.adjustAccountBalance(1L, hier, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Le solde réel est obligatoire.");
        verifyNoInteractions(accountService, transactionRepository);
    }

    @Test
    void undoCreation_refuseUneSelectionVide() {
        assertThatThrownBy(() -> service.undoCreation(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Aucune transaction sélectionnée.");
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void undoSplit_exigeLaTransactionDOrigineEtUneDeuxiemePartieDistincte() {
        DemandeAnnulationDivisionDTO sansOrigine = new DemandeAnnulationDivisionDTO();
        sansOrigine.setSecondPartId(2L);
        DemandeAnnulationDivisionDTO memeTransaction = new DemandeAnnulationDivisionDTO();
        memeTransaction.setOriginal(TransactionDTO.builder().build());
        memeTransaction.setSecondPartId(1L);

        assertThatThrownBy(() -> service.undoSplit(1L, sansOrigine)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.undoSplit(1L, memeTransaction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La transaction d'origine et sa 2e partie sont obligatoires.");
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void verifierChampsObligatoires_exigeUnCompte() {
        TransactionDTO sansCompte = TransactionDTO.builder()
                .date(LocalDate.of(2026, 1, 10))
                .amount(new BigDecimal("-10.00"))
                .build();

        assertThatThrownBy(() -> ServiceTransaction.verifierChampsObligatoires(sansCompte))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Le compte est obligatoire");
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

        int count = service.deleteAllPending(null);

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

        int count = service.deleteAllPending(null);

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

        int count = service.deleteAllPending(1L);

        assertThat(count).isEqualTo(1);
        verify(transactionRepository).deleteAll(List.of(pending));
        verify(transactionRepository, never()).findByValidatedFalseWithTransferTransaction();
    }

    // --- validateAllPending ---

    @Test
    void validateAllPending_sansCompte_valideToutEnUneRequete() {
        when(transactionRepository.validateAllPending()).thenReturn(7);

        assertThat(service.validateAllPending(null)).isEqualTo(7);
        verify(transactionRepository, never()).findPendingByAccountIdWithTransferTransaction(any());
    }

    @Test
    void validateAllPending_avecCompte_valideAussiLaJumelleDUnVirementSurUnAutreCompte() {
        Transaction debit = transaction(compte(1L), null, LocalDate.of(2026, 1, 10), "-50.00");
        debit.setId(1L);
        debit.setPaymentMode(ModePaiement.TRANSFER);
        Transaction credit = transaction(compte(2L), null, LocalDate.of(2026, 1, 10), "50.00");
        credit.setId(2L);
        credit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setTransferTransaction(credit);
        credit.setTransferTransaction(debit);
        Transaction courses = transaction(compte(1L), null, LocalDate.of(2026, 1, 12), "-30.00");
        courses.setId(3L);
        when(transactionRepository.findPendingByAccountIdWithTransferTransaction(1L)).thenReturn(List.of(debit, courses));

        int count = service.validateAllPending(1L);

        assertThat(count).isEqualTo(2);
        assertThat(debit.isValidated()).isTrue();
        assertThat(credit.isValidated()).isTrue();
        assertThat(courses.isValidated()).isTrue();
        verify(transactionRepository, never()).validateAllPending();
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

    /** Ces champs ne se saisissent pas : seule une restauration les reprend (voir ServiceRestaurationTransaction). */
    @Test
    void create_ignoreLesChampsReservesALaRestauration() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionDTO result = service.create(TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(new BigDecimal("-120.00"))
                .balanceAdjustment(true).bankLabel("ACHAT CB CARREFOUR 04.01.26").build());

        assertThat(result.isBalanceAdjustment()).isFalse();
        assertThat(result.getBankLabel()).isNull();
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

    @Test
    void create_sansModeDePaiement_enregistreAucun() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        TransactionDTO sansMode = TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(new BigDecimal("-10.00")).build();

        assertThat(service.create(sansMode).getPaymentMode()).isEqualTo(ModePaiement.NONE);
    }

    @Test
    void create_refuseUneAnneeSurDeuxChiffresSansCreerDeTiers() {
        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(26, 11, 17)).amount(new BigDecimal("-10.00"))
                .payee("Boulangerie").build();

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("année saisie : 26");
        verify(payeeService, never()).resolveOrCreate(any(), any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_refuseUnMontantNul() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(BigDecimal.ZERO).payee("Boulangerie").build();

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Le montant ne peut pas être nul.");
        verify(payeeService, never()).resolveOrCreate(any(), any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void update_transactionDejaANul_resteModifiableSansChangerLeMontant() {
        Transaction existante = transaction(compte(1L), null, LocalDate.of(2026, 3, 1), "0.00");
        existante.setId(7L);
        when(transactionRepository.findById(7L)).thenReturn(Optional.of(existante));
        when(accountService.findById(1L)).thenReturn(existante.getAccount());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(7L, TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 3, 1)).amount(BigDecimal.ZERO).memo("frais offerts").build());

        assertThat(existante.getMemo()).isEqualTo("frais offerts");
    }

    @Test
    void create_refuseUnMemoTropLongAvecUnMessageClair() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(new BigDecimal("-10.00"))
                .memo("m".repeat(300)).build();

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Le mémo ne peut pas dépasser 255 caractères");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_refuseUnTiersTropLongSansLeCreer() {
        when(accountService.findById(1L)).thenReturn(compte(1L));
        TransactionDTO payload = TransactionDTO.builder()
                .accountId(1L).date(LocalDate.of(2026, 1, 5)).amount(new BigDecimal("-10.00"))
                .payee("p".repeat(300)).build();

        assertThatThrownBy(() -> service.create(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Le nom du tiers ne peut pas dépasser 255 caractères");
        verify(payeeService, never()).resolveOrCreate(any(), any());
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
    void undoCreation_supprimeSansEmpreinteDeSuppression() {
        Transaction importee = transaction(compte(1L), null, LocalDate.of(2026, 3, 1), "-12.00");
        importee.setId(5L);
        importee.setBankLabel("CB CARREFOUR");
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(5L))).thenReturn(List.of(importee));

        int deleted = service.undoCreation(List.of(5L));

        assertThat(deleted).isEqualTo(1);
        verify(transactionRepository).deleteAll(List.of(importee));
        verify(deletedTransactionRepository, never()).save(any());
    }

    // --- undoSplit : retour a la transaction d'avant division, en une operation ---

    private Transaction[] partiesDeDivision(String premiere, String seconde) {
        Compte compte = compte(1L);
        Transaction first = transaction(compte, null, LocalDate.of(2026, 3, 1), premiere);
        first.setId(1L);
        first.setSplitFromAmount(new BigDecimal("-80.00"));
        Transaction second = transaction(compte, null, LocalDate.of(2026, 3, 1), seconde);
        second.setId(2L);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(first));
        when(transactionRepository.findById(2L)).thenReturn(Optional.of(second));
        return new Transaction[]{first, second};
    }

    private static DemandeAnnulationDivisionDTO annulationDivision(BigDecimal splitFromAmount) {
        DemandeAnnulationDivisionDTO request = new DemandeAnnulationDivisionDTO();
        request.setSecondPartId(2L);
        request.setOriginal(TransactionDTO.builder().accountId(1L).date(LocalDate.of(2026, 3, 1))
                .amount(new BigDecimal("-80.00")).memo("origine").splitFromAmount(splitFromAmount).build());
        return request;
    }

    @Test
    void undoSplit_rendLeMontantEtSupprimeLaSecondePartieSansEmpreinte() {
        Transaction[] parties = partiesDeDivision("-30.00", "-50.00");
        when(accountService.findById(1L)).thenReturn(parties[0].getAccount());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.undoSplit(1L, annulationDivision(null));

        assertThat(parties[0].getAmount()).isEqualByComparingTo("-80.00");
        assertThat(parties[0].getMemo()).isEqualTo("origine");
        assertThat(parties[0].getSplitFromAmount()).isNull();
        verify(transactionRepository).delete(parties[1]);
        verify(deletedTransactionRepository, never()).save(any());
    }

    @Test
    void undoSplit_refuseDesPartiesModifieesEntreTemps() {
        Transaction[] parties = partiesDeDivision("-30.00", "-20.00");
        DemandeAnnulationDivisionDTO request = annulationDivision(null);

        assertThatThrownBy(() -> service.undoSplit(1L, request)).isInstanceOf(IllegalStateException.class);

        verify(transactionRepository, never()).delete(parties[1]);
        assertThat(parties[0].getAmount()).isEqualByComparingTo("-30.00");
    }

    // --- update : le libelle calcule d'une transaction sans tiers n'est pas un nom de tiers ---

    private TransactionDTO.TransactionDTOBuilder payloadDepuis(Transaction t) {
        return TransactionDTO.builder()
                .accountId(t.getAccount().getId())
                .date(t.getDate())
                .amount(t.getAmount())
                .paymentMode(t.getPaymentMode());
    }

    @Test
    void update_virementAvecSonLibelleCalcule_neCreePasDeTiers() {
        Transaction[] virement = virementDe50();
        Transaction debit = virement[0];
        debit.getAccount().setCurrency("EUR");
        virement[1].getAccount().setCurrency("EUR");
        String libelle = MappeurTransaction.payeeLabel(debit);
        when(transactionRepository.findById(debit.getId())).thenReturn(Optional.of(debit));
        when(accountService.findById(debit.getAccount().getId())).thenReturn(debit.getAccount());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(debit.getId(), payloadDepuis(debit).payee(libelle).memo("corrige").build());

        assertThat(debit.getPayee()).isNull();
        verify(payeeService, never()).resolveOrCreate(any(), any());
    }

    @Test
    void update_ajustementAvecSonLibelleCalcule_neCreePasDeTiers() {
        Compte compte = compte(1L);
        Transaction ajustement = transaction(compte, null, LocalDate.of(2026, 3, 1), "120.00");
        ajustement.setId(8L);
        ajustement.setBalanceAdjustment(true);
        when(transactionRepository.findById(8L)).thenReturn(Optional.of(ajustement));
        when(accountService.findById(1L)).thenReturn(compte);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(8L, payloadDepuis(ajustement).payee("Ajustement de solde").build());

        assertThat(ajustement.getPayee()).isNull();
        verify(payeeService, never()).resolveOrCreate(any(), any());
    }

    @Test
    void update_tiersInchange_neRelancePasLaReconnaissance() {
        Compte compte = compte(1L);
        Tiers tiers = payee(4L, "Carrefour Market");
        Transaction achat = transaction(compte, tiers, LocalDate.of(2026, 3, 1), "-12.00");
        achat.setId(9L);
        when(transactionRepository.findById(9L)).thenReturn(Optional.of(achat));
        when(accountService.findById(1L)).thenReturn(compte);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(9L, payloadDepuis(achat).payee("Carrefour Market").memo("note").build());

        assertThat(achat.getPayee()).isSameAs(tiers);
        verify(payeeService, never()).resolveOrCreate(any(), any());
    }

    @Test
    void bulkUpdate_chargeLesTiersUneSeuleFois() {
        Transaction premiere = transaction(compte(1L), null, LocalDate.of(2026, 3, 1), "-1.00");
        premiere.setId(1L);
        Transaction seconde = transaction(compte(1L), null, LocalDate.of(2026, 3, 2), "-2.00");
        seconde.setId(2L);
        Tiers tiers = payee(4L, "Regroupe");
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L, 2L))).thenReturn(List.of(premiere, seconde));
        when(transactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payeeService.findAllForMatching()).thenReturn(List.of());
        when(payeeService.resolveOrCreate(eq("Regroupe"), any(), anyList())).thenReturn(tiers);
        TransactionBulkUpdateDTO demande = new TransactionBulkUpdateDTO();
        demande.setIds(List.of(1L, 2L));
        demande.setPayeeProvided(true);
        demande.setPayee("Regroupe");

        service.bulkUpdate(demande);

        assertThat(seconde.getPayee()).isSameAs(tiers);
        verify(payeeService, org.mockito.Mockito.times(1)).findAllForMatching();
    }

    @Test
    void update_virementAvecUnVraiTiers_leRattacheToujours() {
        Transaction[] virement = virementDe50();
        Transaction debit = virement[0];
        debit.getAccount().setCurrency("EUR");
        virement[1].getAccount().setCurrency("EUR");
        Tiers proprietaire = payee(4L, "Propriétaire");
        when(transactionRepository.findById(debit.getId())).thenReturn(Optional.of(debit));
        when(accountService.findById(debit.getAccount().getId())).thenReturn(debit.getAccount());
        when(payeeService.resolveOrCreate(eq("Propriétaire"), any())).thenReturn(proprietaire);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(debit.getId(), payloadDepuis(debit).payee("Propriétaire").build());

        assertThat(debit.getPayee()).isSameAs(proprietaire);
    }

    @Test
    void bulkUpdate_tiersParId_affecteCeTiersSansReconnaissanceParTexte() {
        Tiers ancien = payee(5L, "Carrefour Market");
        Tiers recree = payee(9L, "Carrefour City");
        Transaction t1 = transaction(compte(1L), ancien, LocalDate.of(2026, 2, 1), "-3.00");
        t1.setId(1L);
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L))).thenReturn(List.of(t1));
        when(transactionRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(payeeService.findEntityById(9L)).thenReturn(recree);
        TransactionBulkUpdateDTO payload = bulkPayee(null, 1L);
        payload.setPayeeId(9L);

        service.bulkUpdate(payload);

        assertThat(t1.getPayee()).isSameAs(recree);
        verify(payeeService, never()).resolveOrCreate(any(), any());
    }

    @Test
    void bulkUpdate_transactionIntrouvable_refuseSansRienModifier() {
        Transaction t1 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-3.00");
        t1.setId(1L);
        when(transactionRepository.findAllByIdWithTransferTransaction(List.of(1L, 99L))).thenReturn(List.of(t1));

        assertThatThrownBy(() -> service.bulkUpdate(bulkPayee("Cafe du coin", 1L, 99L)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("[99]");
        verify(transactionRepository, never()).saveAll(anyList());
        verify(payeeService, never()).resolveOrCreate(any(), any());
    }

    @Test
    void bulkUpdate_tiersTropLong_refuseAvantDeCreerLeTiers() {
        assertThatThrownBy(() -> service.bulkUpdate(bulkPayee("x".repeat(300), 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255 caractères");
        verify(transactionRepository, never()).findAllByIdWithTransferTransaction(any());
        verify(payeeService, never()).resolveOrCreate(any(), any());
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
        when(payeeService.resolveOrCreate(eq("Cafe du coin"), any(), anyList())).thenReturn(cafe);

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
        when(payeeService.resolveOrCreate(eq("Cafe du coin"), any(), anyList())).thenReturn(cafe);

        service.bulkUpdate(bulkPayee("Cafe du coin", 1L));

        verify(payeeService, never()).deleteIfOrphan(any());
    }

}

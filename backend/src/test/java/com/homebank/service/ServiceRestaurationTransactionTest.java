package com.homebank.service;

import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.model.TransactionSupprimee;
import com.homebank.repository.DepotTransaction;
import com.homebank.repository.DepotTransactionSupprimee;
import com.homebank.utils.ModePaiement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.homebank.service.FabriqueTransactions.compte;
import static com.homebank.service.FabriqueTransactions.payee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Une suppression annulee doit rendre les transactions exactement comme elles etaient. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceRestaurationTransactionTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);

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

    private ServiceRestaurationTransaction service;
    private final List<Transaction> enregistrees = new ArrayList<>();
    private final Compte compte1 = compte(1L);
    private final Compte compte2 = compte(2L);

    @BeforeEach
    void setUp() {
        service = new ServiceRestaurationTransaction(transactionRepository, deletedTransactionRepository,
                accountService, categoryService, payeeService, new MappeurTransaction());
        AtomicLong prochainId = new AtomicLong(100);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(prochainId.getAndIncrement());
            enregistrees.add(t);
            return t;
        });
        when(accountService.findById(1L)).thenReturn(compte1);
        when(accountService.findById(2L)).thenReturn(compte2);
        when(payeeService.findAllForMatching()).thenReturn(new ArrayList<>());
        when(deletedTransactionRepository.findByAccountId(any())).thenReturn(List.of());
    }

    private static TransactionDTO.TransactionDTOBuilder instantane(long id, long compteId, String montant) {
        return TransactionDTO.builder().id(id).accountId(compteId).date(DATE)
                .amount(new BigDecimal(montant)).paymentMode(ModePaiement.CARD);
    }

    private static TransactionSupprimee empreinte(long compteId, String montant, String tiers, String libelle) {
        TransactionSupprimee e = new TransactionSupprimee();
        e.setAccountId(compteId);
        e.setDate(DATE);
        e.setAmount(new BigDecimal(montant));
        e.setPayeeName(tiers);
        e.setBankLabel(libelle);
        return e;
    }

    @Test
    void restore_reprendTousLesChampsYComprisCeuxJamaisSaisis() {
        service.restore(List.of(instantane(5L, 1L, "-30.00")
                .memo("courses").validated(true).periodicExpense(true).exceptional(true)
                .bankLabel("CB CARREFOUR").duplicateIgnored(true).periodicSuggestionIgnored(true)
                .splitFromAmount(new BigDecimal("-80.00")).build()));

        Transaction t = enregistrees.getFirst();
        assertThat(t.getMemo()).isEqualTo("courses");
        assertThat(t.getPaymentMode()).isEqualTo(ModePaiement.CARD);
        assertThat(t.isValidated()).isTrue();
        assertThat(t.isPeriodicExpense()).isTrue();
        assertThat(t.isExceptional()).isTrue();
        assertThat(t.getBankLabel()).isEqualTo("CB CARREFOUR");
        assertThat(t.isDuplicateIgnored()).isTrue();
        assertThat(t.isPeriodicSuggestionIgnored()).isTrue();
        assertThat(t.getSplitFromAmount()).isEqualByComparingTo("-80.00");
    }

    @Test
    void restore_accepteUnCompteArchiveEtUnMontantNul() {
        compte1.setArchived(true);

        service.restore(List.of(instantane(5L, 1L, "0.00").build()));

        assertThat(enregistrees.getFirst().getAccount()).isSameAs(compte1);
    }

    @Test
    void restore_ajustementSansTiers_neCreeAucunTiersDepuisSonLibelle() {
        service.restore(List.of(instantane(5L, 1L, "120.00").payee("Ajustement de solde").balanceAdjustment(true).build()));

        Transaction t = enregistrees.getFirst();
        assertThat(t.getPayee()).isNull();
        assertThat(t.isBalanceAdjustment()).isTrue();
        verify(payeeService, never()).resolveOrCreate(any(), any(), anyList());
    }

    @Test
    void restore_reutiliseLeTiersDOrigineSansPasserParLesMotifs() {
        Tiers origine = payee(7L, "Carrefour");
        when(payeeService.findAllForMatching()).thenReturn(new ArrayList<>(List.of(origine)));

        service.restore(List.of(instantane(5L, 1L, "-3.00").payeeId(7L).payee("Carrefour").build()));

        assertThat(enregistrees.getFirst().getPayee()).isSameAs(origine);
        verify(payeeService, never()).resolveOrCreate(any(), any(), anyList());
        verify(payeeService, never()).findOrCreateByExactName(any(), anyList());
    }

    @Test
    void restore_recreeUneSeuleFoisUnTiersDisparuPartageParPlusieursTransactions() {
        Tiers recree = payee(9L, "Kiosque");
        when(payeeService.findOrCreateByExactName(eq("Kiosque"), anyList())).thenReturn(recree);

        service.restore(List.of(
                instantane(5L, 1L, "-3.00").payeeId(7L).payee("Kiosque").build(),
                instantane(6L, 1L, "-4.00").payeeId(7L).payee("Kiosque").build()));

        assertThat(enregistrees).extracting(Transaction::getPayee).containsOnly(recree);
        verify(payeeService, times(1)).findOrCreateByExactName(eq("Kiosque"), anyList());
        // Jamais par les motifs : un autre tiers dont le motif contient ce nom l'aurait capte.
        verify(payeeService, never()).resolveOrCreate(any(), any(), anyList());
    }

    @Test
    void restore_garderOrdreDesAnciensIds() {
        service.restore(List.of(
                instantane(9L, 1L, "-9.00").build(),
                instantane(4L, 1L, "-4.00").build()));

        assertThat(enregistrees).extracting(t -> t.getAmount().intValue()).containsExactly(-4, -9);
    }

    @Test
    void restore_relieLesDeuxCotesDUnVirementEtGardeLeursChampsPropres() {
        List<TransactionDTO> restaurees = service.restore(List.of(
                instantane(2L, 1L, "-50.00").transferTransactionId(3L).paymentMode(ModePaiement.TRANSFER).exceptional(true).build(),
                instantane(3L, 2L, "50.00").transferTransactionId(2L).paymentMode(ModePaiement.TRANSFER).build()));

        Transaction debit = enregistrees.getFirst();
        Transaction credit = enregistrees.get(1);
        assertThat(debit.getTransferTransaction()).isSameAs(credit);
        assertThat(credit.getTransferTransaction()).isSameAs(debit);
        assertThat(debit.isExceptional()).isTrue();
        assertThat(credit.isExceptional()).isFalse();
        assertThat(restaurees.getFirst().getTransferTransactionId()).isEqualTo(credit.getId());
    }

    @Test
    void restore_refuseUnVirementIncoherent() {
        List<TransactionDTO> incoherent = List.of(
                instantane(2L, 1L, "-50.00").transferTransactionId(3L).build(),
                instantane(3L, 2L, "40.00").transferTransactionId(2L).build());

        assertThatThrownBy(() -> service.restore(incoherent)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_coteSansJumelle_estRecreeSansAppariement() {
        service.restore(List.of(instantane(2L, 1L, "-50.00").transferTransactionId(3L).build()));

        assertThat(enregistrees.getFirst().getTransferTransaction()).isNull();
    }

    @Test
    void restore_retireUneSeuleEmpreinteParTransaction() {
        TransactionSupprimee premiere = empreinte(1L, "-3.00", "Kiosque", null);
        TransactionSupprimee seconde = empreinte(1L, "-3.00", "Kiosque", null);
        when(deletedTransactionRepository.findByAccountId(1L)).thenReturn(List.of(premiere, seconde));

        service.restore(List.of(instantane(5L, 1L, "-3.00").payeeId(7L).payee("Kiosque").build()));

        verify(deletedTransactionRepository).deleteAll(List.of(premiere));
    }

    @Test
    void restore_transactionDivisee_retrouveSonEmpreinteParLeMontantDuReleve() {
        TransactionSupprimee empreinte = empreinte(1L, "-80.00", null, "CB CARREFOUR");
        when(deletedTransactionRepository.findByAccountId(1L)).thenReturn(List.of(empreinte));

        service.restore(List.of(instantane(5L, 1L, "-30.00")
                .splitFromAmount(new BigDecimal("-80.00")).bankLabel("CB CARREFOUR").build()));

        verify(deletedTransactionRepository).deleteAll(List.of(empreinte));
    }

    @Test
    void restore_sansEmpreinteCorrespondante_neSupprimeRien() {
        when(deletedTransactionRepository.findByAccountId(1L)).thenReturn(List.of(empreinte(1L, "-99.00", null, null)));

        service.restore(List.of(instantane(5L, 1L, "-3.00").build()));

        verify(deletedTransactionRepository, never()).deleteAll(anyList());
    }

    @Test
    void restore_refuseUneListeVideOuUnInstantaneSansId() {
        List<TransactionDTO> vide = List.of();
        List<TransactionDTO> sansId = List.of(TransactionDTO.builder().accountId(1L).date(DATE).amount(BigDecimal.ONE).build());

        assertThatThrownBy(() -> service.restore(vide)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.restore(sansId)).isInstanceOf(IllegalArgumentException.class);
    }
}

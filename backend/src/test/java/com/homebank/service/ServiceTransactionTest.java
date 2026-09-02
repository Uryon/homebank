package com.homebank.service;

import com.homebank.dto.DemandeDivisionDTO;
import com.homebank.dto.DemandeVirementDTO;
import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.dto.SuggestionPeriodiqueDTO;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        Transaction original = transaction(compte(1L), null, LocalDate.of(2026, 1, 1), "-100.00");
        original.setId(1L);
        original.setPaymentMode(ModePaiement.TRANSFER);
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
        original.setTags("maison");
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
        assertThat(second.getTags()).isEqualTo("maison");
        assertThat(second.isValidated()).isTrue();
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

    @Test
    void findDuplicateGroups_regroupeCommeProbableMemeCompteDateMontantMaisTiersDifferent() {
        Compte account = compte(1L);
        Transaction t1 = transaction(account, payee(1L, "Carrefour"), LocalDate.of(2026, 1, 5), "-45.50");
        t1.setId(1L);
        Transaction t2 = transaction(account, payee(2L, "Prelevement CB Carrefour Paris"), LocalDate.of(2026, 1, 5), "-45.50");
        t2.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(t1, t2));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).isCertain()).isFalse();
        assertThat(groups.get(0).isReimbursement()).isFalse();
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

    // --- sanitizeMemo ---

    @Test
    void sanitizeMemo_remplaceNullLitteralEtVideParNull() {
        assertThat(ServiceTransaction.sanitizeMemo(null)).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("   ")).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("(NULL)")).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("null")).isNull();
        assertThat(ServiceTransaction.sanitizeMemo("  Courses  ")).isEqualTo("Courses");
    }
}

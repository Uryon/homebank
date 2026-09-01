package com.homebank.service;

import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.mapper.MappeurTransaction;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
}

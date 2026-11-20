package com.homebank.service;

import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.ModePaiement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static com.homebank.service.FabriqueTransactions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifie la detection des transactions periodiques : elle doit rester
 * stricte (au moins 3 occurrences ET intervalles reguliers), pour ne pas
 * suggerer a tort des depenses ponctuelles comme periodiques.
 */
@ExtendWith(MockitoExtension.class)
class ServiceSuggestionPeriodiqueTest {

    @Mock
    private DepotTransaction transactionRepository;

    private ServiceSuggestionPeriodique service;

    @BeforeEach
    void setUp() {
        service = new ServiceSuggestionPeriodique(transactionRepository, new MappeurTransaction());
    }

    @Test
    void findPeriodicSuggestions_neMelangePasDepensesEtRemboursementsDuMemeTiers() {
        Tiers mutuelle = payee(1L, "Mutuelle");
        List<Transaction> transactions = List.of(
                transaction(mutuelle, LocalDate.of(2026, 1, 5), "-50.00"),
                transaction(mutuelle, LocalDate.of(2026, 2, 5), "-50.00"),
                transaction(mutuelle, LocalDate.of(2026, 3, 5), "-50.00"),
                transaction(mutuelle, LocalDate.of(2026, 1, 20), "50.00"),
                transaction(mutuelle, LocalDate.of(2026, 2, 20), "50.00"),
                transaction(mutuelle, LocalDate.of(2026, 3, 20), "50.00"));
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(transactions);

        List<SuggestionPeriodiqueDTO> suggestions = service.findPeriodicSuggestions();

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions).extracting(s -> s.getAverageAmount().intValue())
                .containsExactlyInAnyOrder(-50, 50);
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

    @Test
    void ignorePeriodicSuggestion_marqueLesTransactionsDemandees() {
        Transaction t1 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-9.99");
        when(transactionRepository.findAllById(List.of(1L))).thenReturn(List.of(t1));

        service.ignorePeriodicSuggestion(List.of(1L));

        assertThat(t1.isPeriodicSuggestionIgnored()).isTrue();
        verify(transactionRepository).saveAll(List.of(t1));
    }

    @Test
    void unignorePeriodicSuggestion_permetDeReproposerLaSuggestion() {
        Transaction t1 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-9.99");
        t1.setPeriodicSuggestionIgnored(true);
        when(transactionRepository.findAllById(List.of(1L))).thenReturn(List.of(t1));

        service.unignorePeriodicSuggestion(List.of(1L));

        assertThat(t1.isPeriodicSuggestionIgnored()).isFalse();
        verify(transactionRepository).saveAll(List.of(t1));
    }

    @Test
    void refuseUneSelectionVide() {
        assertThatThrownBy(() -> service.ignorePeriodicSuggestion(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactionRepository);
    }
}

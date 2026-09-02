package com.homebank.service;

import com.homebank.dto.LigneAmortissementDTO;
import com.homebank.dto.PretDTO;
import com.homebank.model.Pret;
import com.homebank.repository.DepotPret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifie ServicePret, qui n'etait couvert par aucun test jusqu'ici : le
 * calcul de l'echeancier d'amortissement (interets/capital/capital restant
 * du mois par mois) et son ajustement de la derniere echeance pour tomber
 * pile a zero, sans lequel des ecarts d'arrondi s'accumuleraient sur 15-25 ans.
 */
@ExtendWith(MockitoExtension.class)
class ServicePretTest {

    @Mock
    private DepotPret loanRepository;

    private ServicePret service;

    @BeforeEach
    void setUp() {
        service = new ServicePret(loanRepository);
    }

    private static Pret pret(String principal, String annualRate, int termMonths,
                              LocalDate startDate, String monthlyPayment) {
        Pret p = new Pret();
        p.setPrincipal(new BigDecimal(principal));
        p.setAnnualRate(new BigDecimal(annualRate));
        p.setTermMonths(termMonths);
        p.setStartDate(startDate);
        p.setMonthlyPayment(new BigDecimal(monthlyPayment));
        return p;
    }

    @Test
    void findById_leveNoSuchElementExceptionSiLePretEstIntrouvable() {
        when(loanRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void create_reprendTousLesChampsDuPayload() {
        PretDTO payload = PretDTO.builder()
                .name("Pret immobilier")
                .principal(new BigDecimal("200000.00"))
                .annualRate(new BigDecimal("3.250"))
                .termMonths(240)
                .startDate(LocalDate.of(2024, 1, 1))
                .monthlyPayment(new BigDecimal("1150.00"))
                .monthlyInsurance(new BigDecimal("25.00"))
                .notes("Residence principale")
                .build();
        when(loanRepository.save(any(Pret.class))).thenAnswer(inv -> inv.getArgument(0));

        Pret result = service.create(payload);

        assertThat(result.getName()).isEqualTo("Pret immobilier");
        assertThat(result.getPrincipal()).isEqualByComparingTo("200000.00");
        assertThat(result.getAnnualRate()).isEqualByComparingTo("3.250");
        assertThat(result.getTermMonths()).isEqualTo(240);
        assertThat(result.getMonthlyInsurance()).isEqualByComparingTo("25.00");
    }

    // --- computeSchedule : calcul mois par mois ---

    @Test
    void computeSchedule_calculeInteretsEtCapitalRembourseSurLaPremiereEcheance() {
        // 1000.00 a 12%/an -> 1% par mois -> interets du 1er mois = 10.00
        Pret loan = pret("1000.00", "12.000", 1, LocalDate.of(2026, 1, 1), "100.00");

        List<LigneAmortissementDTO> schedule = service.computeSchedule(loan);

        assertThat(schedule).hasSize(1);
        LigneAmortissementDTO row = schedule.get(0);
        assertThat(row.getInterest()).isEqualByComparingTo("10.00");
        assertThat(row.getPrincipal()).isEqualByComparingTo("90.00");
        assertThat(row.getRemainingBalance()).isEqualByComparingTo("910.00");
        assertThat(row.getPayment()).isEqualByComparingTo("100.00");
    }

    @Test
    void computeSchedule_ajusteLaDerniereEcheancePourTomberPileAZeroEtArreteLeSoldeAZero() {
        // Taux 0% pour isoler l'ajustement de la derniere echeance : 1000.00
        // rembourse par mensualites de 300.00, la 4e depasserait le capital
        // restant (100.00) si elle n'etait pas ecretee.
        Pret loan = pret("1000.00", "0.000", 10, LocalDate.of(2026, 1, 1), "300.00");

        List<LigneAmortissementDTO> schedule = service.computeSchedule(loan);

        // La boucle s'arrete des que le capital est solde, meme si termMonths=10.
        assertThat(schedule).hasSize(4);
        LigneAmortissementDTO derniere = schedule.get(3);
        assertThat(derniere.getPayment()).isEqualByComparingTo("100.00");
        assertThat(derniere.getPrincipal()).isEqualByComparingTo("100.00");
        assertThat(derniere.getRemainingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void computeSchedule_marquePaidSelonQueLEcheanceEstDejaPasseeOuFuture() {
        LocalDate today = LocalDate.now();
        // Demarre il y a 2 mois : les echeances des mois 1 (il y a 2 mois) et
        // 2 (il y a 1 mois) sont donc passees, celle du mois 4 (dans 1 mois) future.
        Pret loan = pret("1000.00", "0.000", 4, today.minusMonths(2), "100.00");

        List<LigneAmortissementDTO> schedule = service.computeSchedule(loan);

        assertThat(schedule.get(0).isPaid()).isTrue();
        assertThat(schedule.get(1).isPaid()).isTrue();
        assertThat(schedule.get(3).isPaid()).isFalse();
    }

    // --- indicateurs derives de l'echeancier ---

    private static LigneAmortissementDTO ligne(int month, LocalDate date, String interest, String remaining, boolean paid) {
        return LigneAmortissementDTO.builder()
                .month(month)
                .date(date)
                .interest(new BigDecimal(interest))
                .remainingBalance(new BigDecimal(remaining))
                .paid(paid)
                .build();
    }

    @Test
    void currentRemainingBalance_renvoieLeSoldeDeLaDerniereEcheanceDejaPayee() {
        Pret loan = pret("1000.00", "0.000", 3, LocalDate.of(2020, 1, 1), "300.00");
        List<LigneAmortissementDTO> schedule = List.of(
                ligne(1, LocalDate.of(2020, 1, 1), "0.00", "700.00", true),
                ligne(2, LocalDate.of(2020, 2, 1), "0.00", "400.00", true),
                ligne(3, LocalDate.of(2020, 3, 1), "0.00", "100.00", false)
        );

        BigDecimal result = service.currentRemainingBalance(loan, schedule);

        assertThat(result).isEqualByComparingTo("400.00");
    }

    @Test
    void currentRemainingBalance_renvoieLeCapitalInitialSiAucuneEcheanceEncorePayee() {
        Pret loan = pret("1000.00", "0.000", 2, LocalDate.now().plusYears(1), "500.00");
        List<LigneAmortissementDTO> schedule = List.of(
                ligne(1, LocalDate.now().plusYears(1), "0.00", "500.00", false)
        );

        BigDecimal result = service.currentRemainingBalance(loan, schedule);

        assertThat(result).isEqualByComparingTo("1000.00");
    }

    @Test
    void totalInterest_sommeLesInteretsDeToutesLesEcheances() {
        List<LigneAmortissementDTO> schedule = List.of(
                ligne(1, LocalDate.of(2020, 1, 1), "10.00", "900.00", true),
                ligne(2, LocalDate.of(2020, 2, 1), "9.00", "800.00", true)
        );

        assertThat(service.totalInterest(schedule)).isEqualByComparingTo("19.00");
    }

    @Test
    void payoffDate_renvoieLaDateDeLaDerniereEcheance() {
        List<LigneAmortissementDTO> schedule = List.of(
                ligne(1, LocalDate.of(2020, 1, 1), "10.00", "900.00", true),
                ligne(2, LocalDate.of(2020, 2, 1), "9.00", "0.00", true)
        );

        assertThat(service.payoffDate(schedule)).isEqualTo(LocalDate.of(2020, 2, 1));
    }

    @Test
    void payoffDate_renvoieNullSiLEcheancierEstVide() {
        assertThat(service.payoffDate(List.of())).isNull();
    }
}

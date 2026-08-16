package com.homebank.service;

import com.homebank.dto.AmortizationRowDTO;
import com.homebank.model.Loan;
import com.homebank.repository.LoanRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class LoanService {

    private static final int SCALE = 2;
    private static final int RATE_DIVISION_SCALE = 10;

    private final LoanRepository loanRepository;

    public LoanService(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public List<Loan> findAll() {
        return loanRepository.findAll();
    }

    public Loan findById(Long id) {
        return loanRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Pret introuvable : " + id));
    }

    public Loan create(Loan payload) {
        payload.setId(null);
        return loanRepository.save(payload);
    }

    public Loan update(Long id, Loan payload) {
        Loan existing = findById(id);
        existing.setName(payload.getName());
        existing.setPrincipal(payload.getPrincipal());
        existing.setAnnualRate(payload.getAnnualRate());
        existing.setTermMonths(payload.getTermMonths());
        existing.setStartDate(payload.getStartDate());
        existing.setMonthlyPayment(payload.getMonthlyPayment());
        existing.setMonthlyInsurance(payload.getMonthlyInsurance());
        existing.setNotes(payload.getNotes());
        return loanRepository.save(existing);
    }

    public void delete(Long id) {
        loanRepository.deleteById(id);
    }

    /**
     * Calcule l'echeancier complet d'un pret, mois par mois, a partir de ses
     * parametres (capital, taux annuel, mensualite, date de depart). Chaque
     * mois : interets = capital restant du * taux mensuel, capital rembourse
     * = mensualite - interets, nouveau capital restant du = ancien - capital
     * rembourse. La derniere echeance est ajustee pour tomber pile a zero
     * (evite les ecarts d'arrondi cumules sur 15-25 ans).
     */
    public List<AmortizationRowDTO> computeSchedule(Loan loan) {
        List<AmortizationRowDTO> rows = new ArrayList<>();
        BigDecimal monthlyRate = loan.getAnnualRate()
                .divide(BigDecimal.valueOf(1200), RATE_DIVISION_SCALE, RoundingMode.HALF_UP);

        BigDecimal remaining = loan.getPrincipal();
        LocalDate today = LocalDate.now();

        for (int i = 1; i <= loan.getTermMonths() && remaining.compareTo(BigDecimal.ZERO) > 0; i++) {
            LocalDate dueDate = loan.getStartDate().plusMonths(i - 1L);
            BigDecimal interest = remaining.multiply(monthlyRate).setScale(SCALE, RoundingMode.HALF_UP);

            BigDecimal payment = loan.getMonthlyPayment();
            BigDecimal principalPaid = payment.subtract(interest);

            // Derniere echeance (ou mensualite qui depasserait le capital
            // restant a cause des arrondis) : on solde exactement.
            if (principalPaid.compareTo(remaining) >= 0) {
                principalPaid = remaining;
                payment = principalPaid.add(interest);
            }

            remaining = remaining.subtract(principalPaid);

            rows.add(AmortizationRowDTO.builder()
                    .month(i)
                    .date(dueDate)
                    .payment(payment)
                    .interest(interest)
                    .principal(principalPaid)
                    .remainingBalance(remaining)
                    .paid(!dueDate.isAfter(today))
                    .build());
        }

        return rows;
    }

    public BigDecimal currentRemainingBalance(Loan loan, List<AmortizationRowDTO> schedule) {
        return schedule.stream()
                .filter(AmortizationRowDTO::isPaid)
                .reduce((a, b) -> b)
                .map(AmortizationRowDTO::getRemainingBalance)
                .orElse(loan.getPrincipal());
    }

    public BigDecimal totalInterest(List<AmortizationRowDTO> schedule) {
        return schedule.stream()
                .map(AmortizationRowDTO::getInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public LocalDate payoffDate(List<AmortizationRowDTO> schedule) {
        if (schedule.isEmpty()) return null;
        return schedule.get(schedule.size() - 1).getDate();
    }
}

package com.homebank.service;

import com.homebank.dto.LigneAmortissementDTO;
import com.homebank.dto.PretDTO;
import com.homebank.model.Pret;
import com.homebank.repository.DepotPret;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ServicePret {

    private static final int SCALE = 2;
    private static final int RATE_DIVISION_SCALE = 10;

    private final DepotPret loanRepository;

    public ServicePret(DepotPret loanRepository) {
        this.loanRepository = loanRepository;
    }

    public List<Pret> findAll() {
        return loanRepository.findAll();
    }

    public Pret findById(Long id) {
        return loanRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Pret introuvable : " + id));
    }

    public Pret create(PretDTO payload) {
        Pret loan = new Pret();
        applyPayload(loan, payload);
        return loanRepository.save(loan);
    }

    public Pret update(Long id, PretDTO payload) {
        Pret existing = findById(id);
        applyPayload(existing, payload);
        return loanRepository.save(existing);
    }

    private void applyPayload(Pret loan, PretDTO payload) {
        loan.setName(payload.getName());
        loan.setPrincipal(payload.getPrincipal());
        loan.setAnnualRate(payload.getAnnualRate());
        loan.setTermMonths(payload.getTermMonths());
        loan.setStartDate(payload.getStartDate());
        loan.setMonthlyPayment(payload.getMonthlyPayment());
        loan.setMonthlyInsurance(payload.getMonthlyInsurance());
        loan.setNotes(payload.getNotes());
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
    public List<LigneAmortissementDTO> computeSchedule(Pret loan) {
        List<LigneAmortissementDTO> rows = new ArrayList<>();
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

            rows.add(LigneAmortissementDTO.builder()
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

    public BigDecimal currentRemainingBalance(Pret loan, List<LigneAmortissementDTO> schedule) {
        return schedule.stream()
                .filter(LigneAmortissementDTO::isPaid)
                .reduce((a, b) -> b)
                .map(LigneAmortissementDTO::getRemainingBalance)
                .orElse(loan.getPrincipal());
    }

    public BigDecimal totalInterest(List<LigneAmortissementDTO> schedule) {
        return schedule.stream()
                .map(LigneAmortissementDTO::getInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public LocalDate payoffDate(List<LigneAmortissementDTO> schedule) {
        if (schedule.isEmpty()) return null;
        return schedule.get(schedule.size() - 1).getDate();
    }
}

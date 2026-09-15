package com.homebank.service;

import com.homebank.dto.LigneAmortissementDTO;
import com.homebank.dto.PretDTO;
import com.homebank.model.Pret;
import com.homebank.repository.DepotPret;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
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
        validate(payload, null);
        applyPayload(loan, payload);
        return loanRepository.save(loan);
    }

    public Pret update(Long id, PretDTO payload) {
        Pret existing = findById(id);
        validate(payload, existing);
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

    /**
     * Validation cote service (le DTO n'a pas d'annotations Bean Validation) :
     * sans elle, un principal/mensualite negatif ou nul, ou une mensualite ne
     * couvrant meme pas les interets du premier mois, passait silencieusement
     * (au mieux un 500 generique via la contrainte JPA @NotNull de Pret pour
     * un champ manquant, au pire un echeancier absurde ou jamais solde pour
     * une mensualite trop faible).
     * <p>
     * `existing` (null en creation) permet de ne pas re-verifier la couverture
     * des interets du premier mois quand capital/taux/mensualite ne changent
     * pas : sans ca, un pret deja en base avant l'ajout de cette regle (ou
     * cree avec des valeurs limites) deviendrait impossible a modifier meme
     * pour un champ sans rapport (nom, notes...).
     */
    private void validate(PretDTO payload, Pret existing) {
        if (payload.getName() == null || payload.getName().isBlank()) {
            throw new IllegalArgumentException("Le nom du pret est obligatoire");
        }
        if (payload.getPrincipal() == null || payload.getPrincipal().signum() <= 0) {
            throw new IllegalArgumentException("Le capital emprunte doit etre positif");
        }
        if (payload.getAnnualRate() == null || payload.getAnnualRate().signum() < 0) {
            throw new IllegalArgumentException("Le taux annuel ne peut pas etre negatif");
        }
        if (payload.getTermMonths() == null || payload.getTermMonths() <= 0) {
            throw new IllegalArgumentException("La duree du pret doit etre d'au moins un mois");
        }
        if (payload.getStartDate() == null) {
            throw new IllegalArgumentException("La date de premiere mensualite est obligatoire");
        }
        if (payload.getMonthlyPayment() == null || payload.getMonthlyPayment().signum() <= 0) {
            throw new IllegalArgumentException("La mensualite doit etre positive");
        }

        if (existing != null
                && existing.getPrincipal().compareTo(payload.getPrincipal()) == 0
                && existing.getAnnualRate().compareTo(payload.getAnnualRate()) == 0
                && existing.getMonthlyPayment().compareTo(payload.getMonthlyPayment()) == 0) {
            return;
        }

        BigDecimal monthlyRate = payload.getAnnualRate()
                .divide(BigDecimal.valueOf(1200), RATE_DIVISION_SCALE, RoundingMode.HALF_UP);
        BigDecimal firstMonthInterest = payload.getPrincipal().multiply(monthlyRate).setScale(SCALE, RoundingMode.HALF_UP);
        if (payload.getMonthlyPayment().compareTo(firstMonthInterest) <= 0) {
            throw new IllegalArgumentException(
                    "La mensualite (" + payload.getMonthlyPayment() + ") ne couvre meme pas les interets du "
                            + "premier mois (" + firstMonthInterest + ") : le capital ne serait jamais rembourse");
        }
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
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

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

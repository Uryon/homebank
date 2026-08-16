package com.homebank.mapper;

import com.homebank.dto.AmortizationRowDTO;
import com.homebank.dto.LoanDTO;
import com.homebank.model.Loan;
import com.homebank.service.LoanService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LoanMapper {

    private final LoanService loanService;

    public LoanMapper(LoanService loanService) {
        this.loanService = loanService;
    }

    /** DTO simple (liste) : inclut les indicateurs calcules (capital restant du, fin, total interets). */
    public LoanDTO toDTO(Loan loan) {
        List<AmortizationRowDTO> schedule = loanService.computeSchedule(loan);
        return LoanDTO.builder()
                .id(loan.getId())
                .name(loan.getName())
                .principal(loan.getPrincipal())
                .annualRate(loan.getAnnualRate())
                .termMonths(loan.getTermMonths())
                .startDate(loan.getStartDate())
                .monthlyPayment(loan.getMonthlyPayment())
                .monthlyInsurance(loan.getMonthlyInsurance())
                .notes(loan.getNotes())
                .currentRemainingBalance(loanService.currentRemainingBalance(loan, schedule))
                .payoffDate(loanService.payoffDate(schedule))
                .totalInterest(loanService.totalInterest(schedule))
                .build();
    }
}

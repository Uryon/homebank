package com.homebank.mapper;

import com.homebank.dto.LigneAmortissementDTO;
import com.homebank.dto.PretDTO;
import com.homebank.model.Pret;
import com.homebank.service.ServicePret;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MappeurPret {

    private final ServicePret loanService;

    public MappeurPret(ServicePret loanService) {
        this.loanService = loanService;
    }

    /** DTO simple (liste) : inclut les indicateurs calcules (capital restant du, fin, total interets). */
    public PretDTO toDTO(Pret loan) {
        List<LigneAmortissementDTO> schedule = loanService.computeSchedule(loan);
        return PretDTO.builder()
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

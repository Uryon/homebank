package com.homebank.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class LoanScheduleDTO {

    private LoanDTO loan;
    private List<AmortizationRowDTO> rows;
}

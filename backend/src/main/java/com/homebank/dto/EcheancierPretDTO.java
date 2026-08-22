package com.homebank.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class EcheancierPretDTO {

    private PretDTO loan;
    private List<LigneAmortissementDTO> rows;
}

package com.homebank.dto;

import lombok.Getter;
import lombok.Setter;

/** Annulation d'une division (voir ServiceTransaction#undoSplit). */
@Getter
@Setter
public class DemandeAnnulationDivisionDTO {
    /** 2e partie creee par la division, a supprimer. */
    private Long secondPartId;
    /** Transaction telle qu'elle etait avant la division. */
    private TransactionDTO original;
}

package com.homebank.dto;

import java.util.List;

/** Bilan du nettoyage des faux tiers, avec de quoi l'annuler (voir ServiceNettoyageTiers). */
public record ResultatNettoyageTiersDTO(int detachedTransactions, int deletedPayees, List<TiersNettoye> payees) {

    /** Tiers traite : nom et motif permettent de le recreer s'il a ete supprime. */
    public record TiersNettoye(Long payeeId, String name, String pattern, List<Long> transactionIds) {
    }
}

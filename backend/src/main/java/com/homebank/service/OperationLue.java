package com.homebank.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Operation lue dans le fichier, pas encore comparee a l'existant.
 * `origin` situe la ligne dans les messages ; `error` est renseigne si
 * elle est illisible.
 */
record OperationLue(String origin, LocalDate date, BigDecimal amount, String payeeText,
                            String memo, String categoryName, String error) {
    static OperationLue enErreur(String origin, String error) {
        return new OperationLue(origin, null, null, null, null, null, error);
    }
}

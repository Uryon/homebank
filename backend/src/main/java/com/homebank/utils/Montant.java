package com.homebank.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Montants tels que les stockent les colonnes numeric(19,2). Les ramener au
 * centime des leur reception evite que la base les arrondisse apres coup, une
 * fois les controles passes (montant nul, somme des parties d'une division).
 */
public final class Montant {

    private static final int CENTIMES = 2;
    private static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    private Montant() {
    }

    /**
     * Montant arrondi au centime, null si absent (controle laisse a l'appelant).
     * Refuse (400, voir GlobalExceptionHandler) ce que la colonne ne peut pas
     * contenir ; `libelle` commence la phrase du message, ex : "Le montant".
     */
    public static BigDecimal auCentime(BigDecimal montant, String libelle) {
        if (montant == null) return null;
        // Compare avant setScale : "1E999999" y developperait un million de chiffres.
        if (montant.abs().compareTo(MAX) > 0) {
            throw new IllegalArgumentException(libelle + " est trop élevé.");
        }
        return montant.setScale(CENTIMES, RoundingMode.HALF_UP);
    }
}

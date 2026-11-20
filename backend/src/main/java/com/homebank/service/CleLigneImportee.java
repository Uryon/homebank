package com.homebank.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Cle d'une ligne de releve dans le registre des lignes importees (voir
 * LigneImportee) : date, montant et libelle de la banque, espaces normalises
 * et casse ignoree (un meme releve retelecharge peut differer sur ces points).
 */
record CleLigneImportee(LocalDate date, BigDecimal amount, String label) {

    /** Null si la ligne n'a pas de libelle : rien a reconnaitre. */
    static CleLigneImportee of(LocalDate date, BigDecimal amount, String label) {
        if (date == null || amount == null || label == null || label.isBlank()) return null;
        return new CleLigneImportee(date, amount.setScale(2, RoundingMode.HALF_UP),
                MotifsTiers.normalizeWhitespace(label).toLowerCase(Locale.ROOT));
    }
}

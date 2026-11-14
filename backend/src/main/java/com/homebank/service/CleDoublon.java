package com.homebank.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Cle de deduplication (date+montant+tiers canonique, insensible a la casse),
 * partagee entre ServiceImport#isAlreadyKnown (detection a l'import) et
 * ServiceTransaction#findDuplicateGroups (detection sur les donnees deja en
 * base, pour l'onglet "Doublons") : montant toujours ramene a l'echelle 2
 * (comme la colonne SQL "amount", numeric(19,2)) pour qu'un texte source sans
 * decimales ("150") et un montant deja enregistre ("150.00") soient bien
 * reconnus comme identiques.
 */
public record CleDoublon(LocalDate date, BigDecimal amount, String canonicalPayee) {
    public static CleDoublon of(LocalDate date, BigDecimal amount, String canonicalPayee) {
        return new CleDoublon(
                date,
                amount.setScale(2, RoundingMode.HALF_UP),
                canonicalPayee != null ? canonicalPayee.trim().toLowerCase(Locale.ROOT) : "");
    }
}

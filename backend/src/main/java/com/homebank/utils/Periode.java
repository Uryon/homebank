package com.homebank.utils;

import java.time.LocalDate;

/**
 * Regles communes aux periodes (from/to) recues par l'API : recherche de
 * transactions, rapports du tableau de bord.
 */
public final class Periode {

    private Periode() {
    }

    /**
     * Refuse (400, voir GlobalExceptionHandler) une periode dont le debut est
     * posterieur a la fin, plutot que de renvoyer une liste vide qui laisserait
     * croire a une periode sans aucune transaction.
     */
    public static void verifierOrdre(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Période invalide : la date de début est postérieure à la date de fin.");
        }
    }

    static final int ANNEE_MIN = 1900;
    static final int ANNEE_MAX = 2099;

    /**
     * Refuse une date dont l'annee sort de [1900, 2099] : typiquement une
     * annee tapee sur deux chiffres dans un champ date (« 17/11/26 » donne
     * l'an 26 dans Chrome). Acceptee telle quelle, la transaction disparaissait
     * des listes et rapports, rangee deux mille ans en arriere.
     * `libelle` commence la phrase du message, ex : "La date".
     */
    public static void verifierAnnee(LocalDate date, String libelle) {
        if (date != null && (date.getYear() < ANNEE_MIN || date.getYear() > ANNEE_MAX)) {
            throw new IllegalArgumentException(libelle + " doit être comprise entre " + ANNEE_MIN + " et " + ANNEE_MAX
                    + " (année saisie : " + date.getYear() + ").");
        }
    }
}

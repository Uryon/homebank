package com.homebank.utils;

/**
 * Longueur maximale des champs texte libres (noms, memos, notes) : colonnes
 * VARCHAR(255) par defaut de Hibernate. Sans controle prealable, un texte plus
 * long n'echouait qu'a l'insertion SQL, avec le message generique de
 * DataIntegrityViolationException (« une donnée obligatoire est manquante »),
 * sans rapport avec la cause.
 */
public final class LongueurTexte {

    public static final int MAX = 255;

    /** Taille de la colonne bank_label (voir Transaction#bankLabel). */
    public static final int MAX_LIBELLE_BANCAIRE = 500;

    private LongueurTexte() {
    }

    /**
     * Refuse (400, voir GlobalExceptionHandler) un texte saisi trop long.
     * `libelle` commence la phrase du message, ex : "Le mémo".
     */
    public static void verifier(String valeur, String libelle) {
        if (valeur != null && valeur.length() > MAX) {
            throw new IllegalArgumentException(libelle + " ne peut pas dépasser " + MAX
                    + " caractères (" + valeur.length() + " saisis).");
        }
    }

    /**
     * Coupe a la longueur maximale un texte qui n'a pas ete saisi par
     * l'utilisateur (ex : libelle ou memo d'un releve importe) : mieux vaut un
     * texte tronque qu'une ligne de releve rejetee.
     */
    public static String tronquer(String valeur) {
        if (valeur == null || valeur.length() <= MAX) return valeur;
        return valeur.substring(0, MAX).stripTrailing();
    }

    /**
     * Libelle bancaire conserve sur une transaction importee (ou restauree par
     * « Annuler ») : espaces retires, null si vide, tronque a la taille de sa
     * colonne.
     */
    public static String libelleBancaire(String texte) {
        if (texte == null || texte.isBlank()) return null;
        String trimmed = texte.trim();
        return trimmed.length() > MAX_LIBELLE_BANCAIRE ? trimmed.substring(0, MAX_LIBELLE_BANCAIRE) : trimmed;
    }
}

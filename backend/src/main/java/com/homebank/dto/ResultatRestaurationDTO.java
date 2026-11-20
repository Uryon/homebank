package com.homebank.dto;

import java.util.Map;

/**
 * Issue d'une restauration (voir ServiceSauvegarde#restaurer).
 *
 * @param lignes            nombre de lignes restaurées par table
 * @param sauvegardeAvant   fichier des données remplacées, écrit juste avant
 *                          (null si aucun dossier de sauvegarde n'est utilisable) :
 *                          permet d'annuler la restauration
 */
public record ResultatRestaurationDTO(Map<String, Integer> lignes, String sauvegardeAvant) {
}

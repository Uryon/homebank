package com.homebank.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Sauvegarde complète des données (voir ServiceSauvegarde) : fichier JSON
 * téléchargeable ou écrit dans le dossier de sauvegarde, restaurable tel quel.
 *
 * @param application toujours « homebank » : refuse un fichier JSON d'une autre origine
 * @param version     format du fichier (voir ServiceSauvegarde#VERSION_FORMAT)
 * @param creeLe      date et heure de la sauvegarde
 * @param tables      lignes de chaque table, par nom de table puis nom de colonne
 */
public record SauvegardeDTO(String application, int version, LocalDateTime creeLe,
                            Map<String, List<Map<String, Object>>> tables) {
}

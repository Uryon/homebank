package com.homebank.dto;

import java.time.LocalDateTime;

/**
 * État des sauvegardes automatiques, pour la page Importer.
 *
 * @param dossier  dossier des sauvegardes (chemin absolu)
 * @param derniere date de la plus récente sauvegarde du dossier, null si aucune
 * @param nombre   nombre de sauvegardes conservées dans le dossier
 */
public record InfoSauvegardeDTO(String dossier, LocalDateTime derniere, int nombre) {
}

package com.homebank.dto;

import java.time.LocalDateTime;

/** Date et heure du dernier import de fichier reussi, null si aucun (voir ServiceDernierImport). */
public record DernierImportDTO(LocalDateTime dateTime) {
}

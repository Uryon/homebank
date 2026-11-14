package com.homebank.dto;

/** Nombre de transactions validees par un rapprochement (voir ServiceRapprochement#validatePendingUpTo). */
public record ValidationRapprochementDTO(int validatedCount) {
}

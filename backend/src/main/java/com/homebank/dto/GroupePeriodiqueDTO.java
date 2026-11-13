package com.homebank.dto;

import java.math.BigDecimal;
import java.util.List;

/** Transactions periodiques d'un meme signe (depenses ou revenus), une par tiers, et leur total. */
public record GroupePeriodiqueDTO(List<ElementPeriodiqueDTO> items, BigDecimal total) {
}

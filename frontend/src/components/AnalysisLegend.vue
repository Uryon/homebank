<template>
  <div class="analysis-legend">
    <span>{{ description }}</span>
    <span class="analysis-legend-keys">
      <span v-if="hasForecast" class="analysis-legend-key">
        <span class="legend-sample forecast-sample">0,00</span> prévisionnel
      </span>
      <span class="analysis-legend-key">
        <span class="legend-swatch favorable" aria-hidden="true"></span> mois inhabituel, favorable
      </span>
      <span class="analysis-legend-key">
        <span class="legend-swatch" aria-hidden="true"></span> mois inhabituel, défavorable
      </span>
      <span class="analysis-legend-key analysis-legend-hint">Cliquez sur un montant pour voir ses transactions</span>
    </span>
  </div>
</template>

<script setup>
/**
 * Légende des tableaux mensuels du tableau de bord (résumé, dépenses et
 * revenus par catégorie) : une ligne compacte au lieu du même long
 * paragraphe répété sous chaque titre. Les pastilles reprennent les styles
 * réels des cases (italique prévisionnel, encadré vert ou corail).
 */
defineProps({
  description: { type: String, required: true },
  hasForecast: { type: Boolean, default: false }
})
</script>

<style scoped>
.analysis-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px 18px;
  color: var(--text-muted);
  font-size: 13px;
  margin-bottom: 14px;
}
.analysis-legend-keys {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 14px;
  font-size: 12px;
}
.analysis-legend-key {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.analysis-legend-hint { color: var(--text-faint); }
/* Consigne de clic sans objet dans l'export PDF. */
@media print {
  .analysis-legend-hint { display: none; }
}
.forecast-sample {
  font-style: italic;
  font-variant-numeric: tabular-nums;
  color: var(--text-muted);
}
.legend-swatch {
  width: 14px;
  height: 10px;
  border-radius: 3px;
  box-shadow: inset 0 0 0 2px var(--coral);
}
.legend-swatch.favorable { box-shadow: inset 0 0 0 2px var(--mint); }
</style>

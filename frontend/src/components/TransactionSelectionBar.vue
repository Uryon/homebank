<template>
  <!-- Barre flottante en bas de l'écran : reste visible même quand on coche
       des lignes loin dans la liste. -->
  <Transition name="bulk-bar">
    <div v-if="count" class="bulk-bar" role="region" aria-label="Actions sur la sélection">
      <span class="bulk-count" aria-live="polite">{{ pluralize(count, 'sélectionnée', 'sélectionnées') }}</span>
      <span class="bulk-total" :class="total >= 0 ? 'positive' : 'negative'">
        {{ formatAmount(total) }}
      </span>
      <span class="bulk-separator" aria-hidden="true" />
      <button class="btn" @click="$emit('edit')"><Icon name="edit" size="sm" /> Modifier</button>
      <button class="btn danger" :disabled="deleting" @click="$emit('delete')">
        <Icon name="trash" size="sm" /> {{ deleting ? 'Suppression…' : 'Supprimer' }}
      </button>
      <button class="bulk-close" type="button" title="Désélectionner tout (Échap)" aria-label="Désélectionner tout" @click="$emit('clear')">
        <Icon name="x" size="sm" />
      </button>
    </div>
  </Transition>
</template>

<script setup>
/** Actions sur les transactions cochées (nombre, somme, modifier, supprimer, tout désélectionner). */
import Icon from './Icon.vue'
import { formatAmount, pluralize } from '../services/format'

defineProps({
  /** Nombre de transactions sélectionnées ; 0 masque la barre. */
  count: { type: Number, required: true },
  /** Somme des montants sélectionnés. */
  total: { type: Number, required: true },
  deleting: { type: Boolean, default: false }
})

defineEmits(['edit', 'delete', 'clear'])
</script>

<style scoped>
/* Flottante, centrée en bas de l'écran, sombre dans les deux thèmes (même
   teinte que la carte « Solde total »). */
.bulk-bar {
  position: fixed;
  left: calc(50% + 126px); /* centrée sur la zone de contenu (sidebar de 252 px) */
  bottom: 22px;
  z-index: 55;
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: calc(100vw - 32px);
  padding: 8px 8px 8px 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 14px;
  background: var(--hero-bg);
  color: #FFFFFF;
  box-shadow: 0 14px 40px rgba(8, 15, 30, 0.35);
  transform: translateX(-50%);
}
.bulk-count { font-size: 13.5px; font-weight: 650; white-space: nowrap; }
.bulk-total {
  padding: 3px 9px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.08);
  font-size: 13px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.bulk-total.positive { color: var(--hero-positive); }
.bulk-total.negative { color: var(--hero-negative); }
.bulk-separator { width: 1px; align-self: stretch; margin: 2px 2px; background: rgba(255, 255, 255, 0.14); }
.bulk-bar .btn {
  min-height: 34px;
  padding: 6px 12px;
  border-color: rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.1);
  color: #FFFFFF;
  box-shadow: none;
  white-space: nowrap;
}
.bulk-bar .btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.18); }
.bulk-bar .btn.danger { color: var(--hero-negative); }
.bulk-bar .btn.danger:hover:not(:disabled) { background: rgba(255, 155, 145, 0.16); }
.bulk-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: rgba(255, 255, 255, 0.7);
}
.bulk-close:hover { background: rgba(255, 255, 255, 0.12); color: #FFFFFF; }
.bulk-bar-enter-active, .bulk-bar-leave-active { transition: opacity 0.16s ease, transform 0.16s ease; }
.bulk-bar-enter-from, .bulk-bar-leave-to { opacity: 0; transform: translate(-50%, 10px); }
@media (prefers-reduced-motion: reduce) {
  .bulk-bar-enter-active, .bulk-bar-leave-active { transition: none; }
}
@media (max-width: 1250px) { .bulk-bar { left: calc(50% + 112px); } } /* sidebar de 224 px */
@media (max-width: 760px) { .bulk-bar { left: 50%; } }                /* sidebar devenue barre du haut */
@media (max-width: 700px) {
  .bulk-bar {
    left: 50%;
    bottom: 12px;
    flex-wrap: wrap;
    justify-content: center;
  }
  .bulk-separator { display: none; }
}
</style>

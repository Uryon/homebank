<template>
  <div v-if="visible" class="cell-popover" :style="{ left: x + 'px', top: y + 'px' }" @mouseenter="$emit('stay')" @mouseleave="$emit('leave')">
    <div class="cell-popover-title">{{ title }}</div>

    <div v-if="loading" class="cell-popover-loading">Chargement…</div>

    <template v-else>
      <div v-if="!rows.length" class="cell-popover-empty">Aucune transaction.</div>
      <ul v-else class="cell-popover-rows">
        <li v-for="row in rows" :key="row.id" class="cell-popover-row">
          <span class="cell-popover-date">{{ formatDate(row.date) }}</span>
          <span class="cell-popover-payee">{{ row.payee || 'Sans nom' }}</span>
          <span class="cell-popover-amount" :class="row.amount >= 0 ? 'positive' : 'negative'">
            {{ formatAmount(row.amount) }}
          </span>
        </li>
      </ul>

      <div v-if="rows.length" class="cell-popover-total">
        <span>Total</span>
        <span :class="total >= 0 ? 'positive' : 'negative'">{{ formatAmount(total) }}</span>
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * Popover en lecture seule affichant le detail des transactions derriere une
 * cellule d'un tableau du tableau de bord (voir Dashboard.vue#scheduleHover).
 * Purement presentationnel : la logique de declenchement (delai de 2s,
 * appel API, positionnement) vit dans le composant appelant.
 */
import { formatAmount, formatDate } from '../services/format'

defineProps({
  visible: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  x: { type: Number, default: 0 },
  y: { type: Number, default: 0 },
  title: { type: String, default: '' },
  rows: { type: Array, default: () => [] },
  total: { type: Number, default: 0 }
})

defineEmits(['stay', 'leave'])
</script>

<style scoped>
.cell-popover {
  position: fixed;
  z-index: 100;
  width: 320px;
  max-height: 320px;
  overflow-y: auto;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: 0 12px 28px rgba(23, 32, 46, 0.22);
  padding: 12px 14px;
}

.cell-popover-title {
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .04em;
  color: var(--text-faint);
  margin-bottom: 8px;
}

.cell-popover-loading,
.cell-popover-empty {
  font-size: 13px;
  color: var(--text-muted);
  padding: 8px 0;
}

.cell-popover-rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.cell-popover-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.cell-popover-date {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-faint);
  flex-shrink: 0;
}

.cell-popover-payee {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-popover-amount {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.cell-popover-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
  font-weight: 700;
  font-size: 13px;
}

.positive { color: var(--mint); }
.negative { color: var(--coral); }
</style>

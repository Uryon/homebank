<template>
  <div class="modal-overlay" @click.self="$emit('cancel')">
    <div v-dialog="() => $emit('cancel')" class="modal cleanup-modal" role="dialog" aria-modal="true" aria-labelledby="payee-cleanup-title">
      <h2 id="payee-cleanup-title">Nettoyer les faux tiers</h2>
      <p class="cleanup-intro">
        Ces tiers copient un libellé automatique. Leurs virements et ajustements perdront ce tiers et
        afficheront le libellé à jour. Montants, dates, catégories et soldes ne changent pas.
      </p>

      <ul class="cleanup-list">
        <li v-for="item in items" :key="item.payeeId">
          <label class="cleanup-item">
            <input v-model="selectedIds" type="checkbox" :value="item.payeeId" />
            <span class="cleanup-item-body">
              <strong>{{ item.name }}</strong>
              <span class="cleanup-meta">
                {{ pluralize(item.transactionsToDetach, 'transaction détachée', 'transactions détachées') }}.
                {{ cleanupOutcome(item) }}
              </span>
              <span v-for="(example, index) in item.examples" :key="index" class="cleanup-example">
                <span class="cleanup-example-date">{{ formatDate(example.date) }}</span>
                <span class="cleanup-example-amount">{{ formatAmount(example.amount) }}</span>
                <span class="cleanup-example-label">{{ example.newLabel }}</span>
              </span>
              <span v-if="item.transactionsToDetach > item.examples.length" class="cleanup-meta">
                et {{ item.transactionsToDetach - item.examples.length }} de plus
              </span>
            </span>
          </label>
        </li>
      </ul>

      <div class="modal-actions">
        <button class="btn secondary" @click="$emit('cancel')">Fermer</button>
        <button class="btn" :disabled="confirming || !selectedIds.length" @click="$emit('confirm', selectedIds)">
          {{ confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { formatAmount, formatDate, pluralize } from '../services/format'
import { cleanupOutcome, selectedTransactionCount } from '../services/payeeCleanup'

const props = defineProps({
  items: { type: Array, required: true },
  confirming: { type: Boolean, default: false }
})

defineEmits(['confirm', 'cancel'])

const selectedIds = ref(props.items.map(item => item.payeeId))

const confirmLabel = computed(() => {
  if (props.confirming) return 'Nettoyage…'
  const count = selectedTransactionCount(props.items, selectedIds.value)
  return `Nettoyer ${pluralize(count, 'transaction', 'transactions')}`
})
</script>

<style scoped>
.cleanup-modal { width: 620px; }
.cleanup-intro { color: var(--text-muted); font-size: 14px; line-height: 1.45; margin-bottom: 14px; }
.cleanup-list { list-style: none; margin: 0; padding: 0; border: 1px solid var(--line); border-radius: var(--radius-md); }
.cleanup-list li + li { border-top: 1px solid var(--line); }
.cleanup-item { display: flex; align-items: flex-start; gap: 10px; padding: 12px; cursor: pointer; }
.cleanup-item input { flex: none; margin-top: 3px; }
.cleanup-item-body { display: flex; flex-direction: column; gap: 3px; min-width: 0; color: var(--text-primary); }
.cleanup-item-body strong { overflow-wrap: anywhere; }
.cleanup-meta { color: var(--text-muted); font-size: 13px; }
.cleanup-example { display: flex; flex-wrap: wrap; gap: 2px 10px; font-size: 13px; }
.cleanup-example-date { color: var(--text-muted); }
.cleanup-example-amount { font-variant-numeric: tabular-nums; }
.cleanup-example-label { overflow-wrap: anywhere; }
</style>

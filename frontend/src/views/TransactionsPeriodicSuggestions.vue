<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Suggestions de dépenses/revenus périodiques détectées automatiquement</div>
      </div>
    </div>

    <TransactionsSubNav />

    <div v-if="suggestions.length" class="suggestion-list">
      <div v-for="group in suggestions" :key="groupKey(group)" class="suggestion-card">
        <div class="suggestion-main">
          <div class="suggestion-payee">{{ group.payee }}</div>
          <div class="suggestion-meta">
            {{ group.count }} occurrences · {{ formatAmount(group.averageAmount) }} en moyenne
            <span v-if="group.averageIntervalDays">· tous les ~{{ group.averageIntervalDays }} jours</span>
          </div>
          <div class="suggestion-occurrences">
            <span v-for="t in group.transactions" :key="t.id" class="occurrence-chip">
              {{ formatDate(t.date) }} · {{ formatAmount(t.amount) }}
            </span>
          </div>
        </div>

        <div class="suggestion-actions">
          <button class="btn secondary" :disabled="marking === groupKey(group)" @click="markPeriodic(group)">
            {{ marking === groupKey(group) ? 'Marquage…' : '✓ Marquer périodique' }}
          </button>
          <button class="btn secondary" @click="ignoreGroup(group)">Ignorer</button>
        </div>
      </div>
    </div>

    <div v-else class="empty-state">
      🎉 Aucune suggestion pour l'instant — HomeBank recherche les tiers dont le montant se répète
      (à ±10% près) sur au moins 3 transactions non encore marquées périodiques.
    </div>
  </div>
</template>

<script setup>
/**
 * Suggestions calculees cote backend (TransactionService#findPeriodicSuggestions) :
 * meme tiers + montant proche, au moins 3 occurrences, pas deja marquees
 * periodiques. "Ignorer" est purement local a la session : la suggestion
 * reapparaitra a la prochaine visite tant qu'elle n'a pas ete acceptee.
 */
import { ref, computed, onMounted } from 'vue'
import { TransactionsAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { formatAmount, formatDate } from '../services/format'
import TransactionsSubNav from './TransactionsSubNav.vue'

const rawSuggestions = ref([])
const ignoredKeys = ref(new Set())
const marking = ref(null)

function groupKey(group) {
  return group.payee + '|' + (group.transactions[0]?.id ?? group.averageAmount)
}

const suggestions = computed(() =>
  rawSuggestions.value.filter(g => !ignoredKeys.value.has(groupKey(g)))
)

async function load() {
  rawSuggestions.value = await TransactionsAPI.periodicSuggestions()
}

function ignoreGroup(group) {
  ignoredKeys.value = new Set([...ignoredKeys.value, groupKey(group)])
}

async function markPeriodic(group) {
  marking.value = groupKey(group)
  try {
    await Promise.all(group.transactions.map(t => TransactionsAPI.togglePeriodicExpense(t.id)))
    await dataStore.refreshTransactions()
    await load()
  } finally {
    marking.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.suggestion-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.suggestion-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.suggestion-main {
  min-width: 0;
  flex: 1;
}

.suggestion-payee {
  font-weight: 700;
  font-size: 15px;
}

.suggestion-meta {
  color: var(--text-muted);
  font-size: 13px;
  margin-top: 2px;
}

.suggestion-occurrences {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.occurrence-chip {
  font-size: 12px;
  font-family: var(--font-mono);
  color: var(--text-faint);
  background: var(--ink-800);
  border-radius: 999px;
  padding: 2px 9px;
}

.suggestion-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

@media (max-width: 720px) {
  .suggestion-card {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>

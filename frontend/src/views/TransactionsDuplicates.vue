<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Doublons potentiels détectés — même compte, date, montant et tiers (ou même identifiant fourni par la banque)</div>
      </div>
    </div>

    <TransactionsSubNav />

    <div v-if="groups.length" class="duplicate-list">
      <div v-for="group in groups" :key="group.transactions[0].id" class="duplicate-card">
        <div class="duplicate-header">
          <div class="duplicate-summary">
            <strong>{{ group.transactions[0].payee || 'Sans tiers' }}</strong>
            · {{ formatAmount(group.transactions[0].amount) }}
            · {{ formatDate(group.transactions[0].date) }}
          </div>
          <div class="duplicate-count">{{ group.transactions.length }} occurrences identiques</div>
        </div>

        <div class="duplicate-rows">
          <div v-for="t in group.transactions" :key="t.id" class="duplicate-row">
            <div class="duplicate-row-info">
              <span class="duplicate-row-account">{{ accountName(t.accountId) }}</span>
              <span v-if="t.memo" class="duplicate-row-memo">{{ t.memo }}</span>
              <span class="duplicate-row-status" :class="t.validated ? 'validated' : 'pending'">
                {{ t.validated ? 'Validée' : 'En attente' }}
              </span>
            </div>
            <button class="btn danger" :disabled="removing === t.id" @click="remove(t)">
              {{ removing === t.id ? 'Suppression…' : 'Supprimer' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-else class="empty-state">
      🎉 Aucun doublon détecté pour l'instant.
    </div>
  </div>
</template>

<script setup>
/**
 * Groupes calcules cote backend (TransactionService#findDuplicateGroups) :
 * meme compte, et soit meme identifiant externe fourni par la banque, soit
 * meme date + montant + tiers canonique (insensible a la casse) — memes
 * criteres qu'a la detection de doublons a l'import (ImportService#isDuplicate).
 * L'utilisateur choisit lui-meme quelle(s) occurrence(s) supprimer : on ne
 * decide jamais automatiquement laquelle garder.
 */
import { ref, computed, onMounted } from 'vue'
import { TransactionsAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { formatAmount, formatDate } from '../services/format'
import TransactionsSubNav from './TransactionsSubNav.vue'

const groups = ref([])
const removing = ref(null)

// Map (id -> compte) precalculee, pour eviter un scan lineaire de accounts
// par ligne affichee (meme principe que accountsById dans Transactions.vue).
const accountsById = computed(() => new Map(dataStore.state.accounts.map(a => [a.id, a])))

function accountName(accountId) {
  return accountsById.value.get(accountId)?.name ?? '—'
}

async function load() {
  groups.value = await TransactionsAPI.duplicates()
}

async function remove(transaction) {
  if (!confirm('Supprimer cette transaction en doublon ?')) return
  removing.value = transaction.id
  try {
    await TransactionsAPI.remove(transaction.id)
    await dataStore.refreshTransactions()
    await load()
  } finally {
    removing.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.duplicate-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.duplicate-card {
  padding: 14px 16px;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

.duplicate-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.duplicate-summary {
  font-size: 15px;
}

.duplicate-count {
  color: var(--text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.duplicate-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.duplicate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  background: var(--ink-800);
  border-radius: var(--radius-sm);
}

.duplicate-row-info {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-size: 13px;
  color: var(--text-muted);
}

.duplicate-row-account {
  font-weight: 600;
  color: var(--text-primary);
}

.duplicate-row-memo {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.duplicate-row-status {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: .04em;
  padding: 2px 8px;
  border-radius: 999px;
  flex-shrink: 0;
}

.duplicate-row-status.validated {
  color: var(--mint);
  background: var(--mint-dim);
}

.duplicate-row-status.pending {
  color: var(--coral);
  background: var(--coral-dim);
}

@media (max-width: 720px) {
  .duplicate-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>

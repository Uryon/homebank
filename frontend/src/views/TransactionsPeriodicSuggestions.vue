<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Suggestions de dépenses/revenus périodiques détectées automatiquement</div>
      </div>
    </div>

    <TransactionsSubNav />

    <div v-if="loading" class="empty-state" role="status">Recherche des dépenses et revenus périodiques…</div>
    <div v-else-if="loadError" class="empty-state" role="alert">
      <p>Impossible de charger les suggestions. Vos transactions n'ont pas été modifiées.</p>
      <button class="btn secondary" type="button" @click="load">Réessayer</button>
    </div>

    <div v-else-if="suggestions.length" class="suggestion-list">
      <div v-for="group in suggestions" :key="groupKey(group)" class="suggestion-card">
        <div class="suggestion-main">
          <div class="suggestion-payee">{{ group.payee }}</div>
          <div class="suggestion-meta">
            {{ group.count }} occurrences ·
            <span class="amount" :class="group.averageAmount < 0 ? 'negative' : 'positive'">{{ formatAmount(group.averageAmount) }}</span> en moyenne
            <span v-if="group.averageIntervalDays">· {{ formatFrequency(group.averageIntervalDays) }}</span>
          </div>
          <div class="suggestion-occurrences">
            <span v-for="t in group.transactions" :key="t.id" class="occurrence-chip">
              {{ formatDate(t.date) }} · {{ formatAmount(t.amount) }}
            </span>
          </div>
        </div>

        <div class="suggestion-actions">
          <button class="btn secondary" type="button" :disabled="isBusy(group)" @click="markPeriodic(group)">
            <Icon name="check" size="sm" />
            {{ marking === groupKey(group) ? 'Marquage…' : 'Marquer périodique' }}
          </button>
          <button
            class="btn secondary"
            type="button"
            :disabled="isBusy(group)"
            title="Ne plus proposer ce groupe de transactions"
            @click="ignoreGroup(group)"
          >
            {{ ignoring === groupKey(group) ? 'Masquage…' : 'Ignorer' }}
          </button>
        </div>
      </div>
    </div>

    <div v-else class="empty-state">
      Aucune suggestion pour l'instant — HomeBank recherche les tiers dont le montant se répète
      (à ±10% près) sur au moins 3 transactions non encore marquées périodiques.
    </div>

    <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
  </div>
</template>

<script setup>
/**
 * Suggestions calculees cote backend (TransactionService#findPeriodicSuggestions) :
 * meme tiers + montant proche, au moins 3 occurrences, pas deja marquees
 * periodiques. "Ignorer" marque durablement les transactions concernees
 * (voir Transaction#periodicSuggestionIgnored / TransactionsAPI.ignorePeriodicSuggestion) :
 * une suggestion refusee ne revient plus a la prochaine visite.
 */
import { ref, onMounted } from 'vue'
import { TransactionsAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { formatAmount, formatDate, formatFrequency, pluralize } from '../services/format'
import { alertApiError } from '../services/errors'
import TransactionsSubNav from './TransactionsSubNav.vue'
import Icon from '../components/Icon.vue'
import UndoBar from '../components/UndoBar.vue'
import { notify } from '../services/feedback'
import { useLastAction } from '../services/lastAction'

/** Annulation du dernier marquage ou masquage (voir services/lastAction.js). */
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

const suggestions = ref([])
const marking = ref(null)
const ignoring = ref(null)
// true jusqu'au premier chargement : évite d'afficher « Aucune suggestion » avant la réponse.
const loading = ref(true)
const loadError = ref(false)

function groupKey(group) {
  return group.payee + '|' + (group.transactions[0]?.id ?? group.averageAmount)
}

/** Une action en cours sur le groupe bloque ses deux boutons (pas de double requête). */
function isBusy(group) {
  const key = groupKey(group)
  return marking.value === key || ignoring.value === key
}

/** Les rechargements après une action restent silencieux : la liste reste affichée. */
async function load({ silent = false } = {}) {
  if (!silent) loading.value = true
  loadError.value = false
  try {
    suggestions.value = await TransactionsAPI.periodicSuggestions()
  } catch (e) {
    loadError.value = true
    alertApiError('Erreur lors du chargement des suggestions', e)
  } finally {
    loading.value = false
  }
}

async function ignoreGroup(group) {
  ignoring.value = groupKey(group)
  const ids = group.transactions.map(t => t.id)
  try {
    await TransactionsAPI.ignorePeriodicSuggestion(ids)
    await load({ silent: true })
    notify(`Suggestion « ${group.payee} » masquée.`, 'success')
    setLastAction('Suggestion masquée', async () => {
      await TransactionsAPI.unignorePeriodicSuggestion(ids)
      await load({ silent: true })
    })
  } catch (e) {
    alertApiError('Erreur lors du masquage de la suggestion', e)
  } finally {
    ignoring.value = null
  }
}

async function markPeriodic(group) {
  marking.value = groupKey(group)
  try {
    // Marquage explicite en une requête (et non une bascule par transaction,
    // qui démarquerait une transaction du groupe déjà marquée périodique).
    const ids = group.transactions.map(t => t.id)
    await TransactionsAPI.bulkUpdate({ ids, periodicExpenseProvided: true, periodicExpense: true })
    await dataStore.refreshTransactions()
    await load({ silent: true })
    notify(`${pluralize(ids.length, 'transaction marquée', 'transactions marquées')} périodique${ids.length > 1 ? 's' : ''}.`, 'success')
    // Une suggestion ne porte que sur des transactions pas encore périodiques
    // (voir ServiceSuggestionPeriodique) : annuler les remet toutes à faux.
    setLastAction('Marquage périodique', async () => {
      await TransactionsAPI.bulkUpdate({ ids, periodicExpenseProvided: true, periodicExpense: false })
      await dataStore.refreshTransactions()
      await load({ silent: true })
    })
  } catch (e) {
    alertApiError('Erreur lors du marquage en dépense périodique', e)
  } finally {
    marking.value = null
  }
}

onMounted(() => load())
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
  font-variant-numeric: tabular-nums; /* espace fine des milliers visible */
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

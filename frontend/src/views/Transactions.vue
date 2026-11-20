<template>
  <div class="transactions-page" :style="stickyOffsetStyle">
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Historique des opérations — cliquez sur une ligne pour la modifier</div>
      </div>

      <div class="header-actions">
        <button
          class="btn secondary"
          :disabled="!sortedTransactions.length"
          :title="`Télécharger les ${sortedTransactions.length} transactions affichées au format CSV de HomeBank (réimportable)`"
          @click="exportCsv"
        >
          Exporter en CSV
        </button>
        <button class="btn" @click="openCreate" :disabled="!accounts.length">
          <Icon name="plus" size="sm" /> Nouvelle transaction
        </button>
      </div>
    </div>

    <TransactionsSubNav />

    <AccountSummaryBar
      v-if="selectedAccount"
      :account="selectedAccount"
      :balance="selectedAccountBalance"
      :pending-count="pendingCount"
      :oldest-pending-date="oldestPendingDate"
      :has-transactions="transactions.length > 0"
      @show-pending="pendingOnly = true"
    />

    <div ref="toolbarEl" class="toolbar transactions-toolbar">
      <div class="form-field">
        <label for="tx-filter-account">Compte</label>
        <select id="tx-filter-account" v-model="filterAccount">
          <option :value="null">Tous les comptes</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ accountOptionLabel(a) }}</option>
        </select>
      </div>

      <div class="form-field">
        <span class="form-field-label">Période</span>
        <MonthYearFilter
          :month="filterMonth"
          :year="filterYear"
          :available-years="availableYears"
          @update:month="filterMonth = $event"
          @update:year="filterYear = $event"
        />
      </div>

      <div class="toolbar-divider" aria-hidden="true"></div>

      <div class="toolbar-actions">
        <button
          class="chip"
          :class="{ active: pendingOnly }"
          :aria-pressed="pendingOnly"
          @click="pendingOnly = !pendingOnly"
          :title="pendingOnly ? 'Revenir à toutes les transactions' : 'N\'afficher que les transactions à traiter'"
        >
          À traiter <span class="chip-count">{{ pendingCount }}</span>
        </button>

        <button
          class="chip"
          :class="{ active: uncategorizedOnly }"
          :aria-pressed="uncategorizedOnly"
          @click="uncategorizedOnly = !uncategorizedOnly"
          :title="uncategorizedOnly ? 'Revenir à toutes les transactions' : 'N\'afficher que les transactions sans catégorie'"
        >
          Sans catégorie <span class="chip-count">{{ uncategorizedCount }}</span>
        </button>

        <!-- Actions en masse affichées seulement s'il y a des transactions à traiter. -->
        <template v-if="pendingCount > 0">
          <button
            class="btn"
            :disabled="validating"
            @click="validateAll"
          >
            {{ validating ? 'Validation…' : `Tout valider (${pendingCount})` }}
          </button>

          <button
            class="btn danger"
            :disabled="deletingPending"
            @click="deletePending"
          >
            {{ deletingPending ? 'Suppression…' : `Supprimer les non validées` }}
          </button>
        </template>
      </div>

      <div v-if="drillDown" class="drill-down-filter" role="status">
        <span class="form-field-label">Depuis le tableau de bord</span>
        <span class="chip active drill-down-chip">
          {{ drillDown.label }}
          <button type="button" class="drill-down-clear" title="Retirer ce filtre" aria-label="Retirer le filtre du tableau de bord" @click="clearDrillDown">
            <Icon name="x" size="sm" />
          </button>
        </span>
        <span v-if="drillDownHasSmoothed" class="drill-down-note">
          Le tableau de bord lisse les catégories annuelles sur 12 mois : le total ici peut donc différer.
        </span>
      </div>

      <div class="form-field search-field">
        <label for="tx-search" title="Tiers, catégorie, mémo ou montant. Séparez plusieurs critères par point-virgule pour tous les exiger à la fois (ET), ex : Bouygues;29,99. Entre guillemets (&quot;CAF&quot;), le critère doit correspondre exactement au lieu de simplement le contenir. Ou &gt; / &lt; / = un montant seul.">Rechercher</label>
        <input id="tx-search" v-model="searchQuery" placeholder="Tiers, catégorie, mémo, montant… ex : > 100" />
        <div v-if="amountQuery" class="text-hint">
          {{ pluralize(filteredTransactions.length, 'transaction trouvée', 'transactions trouvées') }}
        </div>
      </div>
    </div>

    <TransactionSelectionBar
      :count="selectedIds.size"
      :total="selectedTotal"
      :deleting="deletingSelection"
      @edit="openBulkEdit"
      @delete="deleteSelected"
      @clear="clearSelection"
    />

    <div class="table-scroll" v-if="filteredTransactions.length">
      <table v-row-nav="'tr[data-transaction-id]'" class="transactions-table" :class="{ 'with-balance': showBalance }">
        <colgroup>
          <col class="col-select" />
          <col class="col-status" />
          <col class="col-date" />
          <col class="col-account" />
          <col class="col-payee" />
          <col class="col-category" />
          <col class="col-memo" />
          <col class="col-amount" />
          <col class="col-amount" />
          <col v-if="showBalance" class="col-amount" />
          <col class="col-monthly" />
          <col class="col-monthly" />
          <col class="col-actions" />
        </colgroup>
        <thead>
          <tr>
            <th class="num">
              <input
                type="checkbox"
                :checked="allPagedSelected"
                title="Sélectionner/désélectionner toutes les transactions actuellement affichées"
                aria-label="Sélectionner/désélectionner toutes les transactions actuellement affichées"
                @change="toggleSelectAllPaged"
              />
            </th>
            <th title="Statut de validation (rapprochement bancaire)"><span class="sr-only">Statut</span></th>
            <th :aria-sort="ariaSort('date')">
              <button type="button" class="sort-header" title="Trier par date" @click="toggleSort('date')">
                Date <Icon :name="sortIcon('date')" size="sm" />
              </button>
            </th>
            <th>Compte</th>
            <th :aria-sort="ariaSort('payee')">
              <button type="button" class="sort-header" title="Trier par tiers (A→Z)" @click="toggleSort('payee')">
                Tiers <Icon :name="sortIcon('payee')" size="sm" />
              </button>
            </th>
            <th>Catégorie</th>
            <th>Mémo</th>
            <th class="num" :aria-sort="ariaSort('expense')">
              <button type="button" class="sort-header" title="Trier par dépense (la plus grosse en premier)" @click="toggleSort('expense')">
                Dépense <Icon :name="sortIcon('expense')" size="sm" />
              </button>
            </th>
            <th class="num" :aria-sort="ariaSort('income')">
              <button type="button" class="sort-header" title="Trier par revenu (le plus gros en premier)" @click="toggleSort('income')">
                Revenu <Icon :name="sortIcon('income')" size="sm" />
              </button>
            </th>
            <th v-if="showBalance" class="num" title="Solde du compte après chaque opération, dans l'ordre chronologique (solde initial compris)">Solde</th>
            <th class="num th-icon" title="Dépense périodique récurrente">
              <Icon name="refresh" size="sm" /><span class="sr-only">Récurrent</span>
            </th>
            <th class="num th-icon" title="Transaction exceptionnelle : exclue des tableaux de bord (ex : achat d'une voiture)">
              <Icon name="eyeOff" size="sm" /><span class="sr-only">Exception</span>
            </th>
            <th></th>
          </tr>
        </thead>

        <tbody>
          <template v-for="(t, index) in pagedTransactions" :key="t.id">
          <!-- Intertitre à chaque changement de mois (liste triée par date décroissante) -->
          <tr v-if="monthStartsAt(index)" class="month-row">
            <th :colspan="showBalance ? 13 : 12" scope="rowgroup">
              <span class="month-row-label">{{ monthHeading(t.date) }}</span>
              <span class="month-row-meta">
                {{ monthSummary(t.date).count }} transaction{{ monthSummary(t.date).count > 1 ? 's' : '' }}
                · solde
                <span class="amount" :class="monthSummary(t.date).total >= 0 ? 'positive' : 'negative'">{{ formatAmount(monthSummary(t.date).total) }}</span>
              </span>
            </th>
          </tr>
          <TransactionRow
            :transaction="t"
            :is-editing="editingId === t.id"
            :accounts="accounts"
            :payees="payees"
            :flat-categories="flatCategories"
            :accounts-by-id="accountsById"
            :categories-by-id="categoriesById"
            :payee-index="payeeByNormalizedName"
            :today-iso="todayIso"
            :selected="selectedIds.has(t.id)"
            :show-balance="showBalance"
            :balance-after="runningBalances?.get(t.id) ?? null"
            @start-edit="startEdit(t)"
            @cancel-edit="cancelEdit"
            @confirm-edit="confirmEdit"
            @toggle-validated="toggleValidated"
            @toggle-periodic="togglePeriodic"
            @toggle-exceptional="toggleExceptional"
            @toggle-select="toggleSelect"
            @remove="remove"
            @split="openSplit"
            @duplicate="duplicateTransaction"
          />
          </template>
        </tbody>
      </table>
    </div>

    <div v-if="filteredTransactions.length" class="scroll-status">
      <span class="text-hint">
        {{ pagedTransactions.length }} / {{ pluralize(filteredTransactions.length, 'transaction affichée', 'transactions affichées') }}
      </span>
      <!-- Sentinelle observée pour charger le lot suivant en approchant du bas (voir loadMoreObserver) : rootMargin la declenche avant d'etre reellement visible, pour un chargement fluide pendant le scroll. -->
      <div v-if="hasMoreToShow" ref="loadMoreSentinel" class="load-more-sentinel">Chargement…</div>
    </div>

    <div v-else-if="!transactionsLoaded && loadFailed" class="empty-state">
      Transactions indisponibles tant que le serveur ne répond pas.
    </div>
    <SkeletonRows v-else-if="!transactionsLoaded" label="Chargement des transactions…" :columns="['3%', '10%', '10%', '16%', '20%', '18%', '9%']" :rows="10" />
    <div v-else class="empty-state">
      <span>{{
        !accounts.length
          ? "Créez d'abord un compte pour ajouter des transactions."
          : searchQuery.trim()
            ? "Aucune transaction ne correspond à cette recherche."
            : 'Aucune transaction pour ce filtre.'
      }}</span>
      <button v-if="accounts.length && searchQuery.trim()" type="button" class="btn secondary" @click="searchQuery = ''">Effacer la recherche</button>
    </div>

    <TransactionCreateModal
      v-if="newTransactionDraft"
      :draft="newTransactionDraft"
      :accounts="entryAccounts"
      :payees="payees"
      :payee-index="payeeByNormalizedName"
      :categories="flatCategories"
      @close="newTransactionDraft = null"
      @submit="save"
    />

    <TransactionBulkEditModal
      v-if="showBulkEditModal"
      :count-label="selectedCountLabel"
      :categories="flatCategories"
      :payees="payees"
      :saving="bulkSaving"
      @close="showBulkEditModal = false"
      @submit="saveBulkEdit"
    />

    <TransactionSplitModal
      v-if="splitTransaction"
      :transaction="splitTransaction"
      :currency="splitCurrency"
      :categories="flatCategories"
      :saving="splitSaving"
      :error="splitError"
      @close="closeSplitModal"
      @submit="saveSplit"
    />

    <UndoBar :action="lastAction" :undoing="undoing" :raised="selectedIds.size > 0" @undo="runLastAction" />
  </div>
</template>

<script setup>
import { notify, askConfirm } from '../services/feedback'
import { useLastAction } from '../services/lastAction'
import UndoBar from '../components/UndoBar.vue'
import { ref, onMounted, onBeforeUnmount, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  TransactionsAPI
} from '../services/api'
import { dataStore } from '../services/dataStore'
import { previousTransactionPayload, restorationSnapshots, runOnePerPayee, transactionPayloadFrom } from '../services/undoRestore'
import { parseAmountQuery } from '../services/search'
import { buildSearchIndex, filterByStatusAndPeriod, filterBySearch } from '../services/transactionFilters'
import { flattenCategories, needsCategory } from '../services/categories'
import TransactionRow from '../components/TransactionRow.vue'
import TransactionCreateModal from '../components/TransactionCreateModal.vue'
import TransactionSelectionBar from '../components/TransactionSelectionBar.vue'
import TransactionBulkEditModal from '../components/TransactionBulkEditModal.vue'
import TransactionSplitModal from '../components/TransactionSplitModal.vue'
import { neighborRowId, restoreFocusToRow } from '../services/rowKeyboard'
import { idsBetween } from '../services/selection'
import { DEFAULT_TRANSACTION_SORT, TRANSACTION_SORT_DIRECTIONS, isChronologicalSort, sortTransactions } from '../services/transactionSort'
import { ariaSortOf, nextSort, sortIconOf } from '../services/tableSort'
import MonthYearFilter from '../components/MonthYearFilter.vue'
import SkeletonRows from '../components/SkeletonRows.vue'
import { formatMonthHeading, monthKey, startsNewMonth, summarizeByMonth } from '../services/monthGroups'
import Icon from '../components/Icon.vue'
import TransactionsSubNav from './TransactionsSubNav.vue'
import AccountSummaryBar from '../components/AccountSummaryBar.vue'
import { formatAmount, formatDate, pluralize, toLocalIsoDate } from '../services/format'
import { downloadCsv, safeFileName, transactionsToCsv } from '../services/csvExport'
import { findIdenticalTransaction } from '../services/duplicateEntry'
import { accountOptionLabel, activeAccounts, runningBalanceById } from '../services/accounts'
import { alertApiError, apiErrorMessage } from '../services/errors'
import { bulkTouchedFields, bulkUpdatePayload } from '../services/bulkEdit'
import { useProgressiveList } from '../services/progressiveList'
import { DRILL_DOWN_QUERY_KEYS, drillDownMatcher, hasSmoothedContribution, parseDrillDown } from '../services/drillDown'

const route = useRoute()

/*
 * Barre de filtres collée en haut pendant le défilement (CSS, voir
 * .transactions-toolbar) : l'en-tête du tableau et les intertitres de mois,
 * eux aussi collants, se placent juste en dessous grâce à la variable
 * --tx-toolbar-height. Sa hauteur varie (passage sur deux lignes en écran
 * étroit) : un ResizeObserver la tient à jour, sans aucun calcul au scroll.
 */
const toolbarEl = ref(null)
const toolbarHeight = ref(0)
const stickyOffsetStyle = computed(() => ({ '--tx-toolbar-height': `${toolbarHeight.value}px` }))
let toolbarResizeObserver = null

onMounted(() => {
  if (!toolbarEl.value || typeof ResizeObserver === 'undefined') return
  toolbarResizeObserver = new ResizeObserver(([entry]) => {
    toolbarHeight.value = entry.target.offsetHeight
  })
  toolbarResizeObserver.observe(toolbarEl.value)
})
onBeforeUnmount(() => toolbarResizeObserver?.disconnect())
const router = useRouter()

const filterAccount = ref(null)
const filterMonth = ref(null)
const filterYear = ref(null)
const pendingOnly = ref(false)
const uncategorizedOnly = ref(false)
// Critères reçus du tableau de bord (clic sur un montant), voir services/drillDown.js.
const drillDown = ref(null)
const searchQuery = ref('')

// Donnees issues du cache partage (deja precharge au demarrage de l'app,
// voir App.vue / dataStore.js) : s'affichent instantanement sans attendre
// de nouvel appel reseau.
const accounts = computed(() => dataStore.state.accounts)
// Comptes proposés pour une nouvelle transaction : jamais un compte archivé
// (refusé par le backend, voir ServiceCompte#ensureNotArchived).
const entryAccounts = computed(() => activeAccounts(accounts.value))
const transactionsLoaded = computed(() => dataStore.state.loaded.transactions && dataStore.state.loaded.accounts)
const loadFailed = computed(() => dataStore.state.loadFailed)
const categories = computed(() => dataStore.state.categories)
const payees = computed(() => dataStore.state.payees)

/**
 * Liste courante : le cache partagé (toutes les transactions, déjà triées
 * comme le backend trie la liste d'un compte), filtré ici sur le compte
 * choisi. Une seule source de données : une modification faite sous filtre
 * met aussi à jour la sidebar et le tableau de bord, et changer de compte
 * n'attend aucun appel réseau. Recalculé seulement quand le cache ou le
 * compte change, jamais à la frappe.
 */
const transactions = computed(() => {
  const all = dataStore.state.transactions
  const accountId = filterAccount.value
  return accountId === null ? all : all.filter(t => t.accountId === accountId)
})

/** Années distinctes présentes dans la liste courante (compte filtré ou non), pour peupler le filtre Année. Recalculé uniquement quand la liste change, pas à chaque frappe. */
const availableYears = computed(() => {
  const years = new Set()
  for (const t of transactions.value) {
    if (t.date) years.add(Number(t.date.slice(0, 4)))
  }
  return Array.from(years).sort((a, b) => b - a)
})

/** Annulation de la dernière action (voir services/lastAction.js et UndoBar.vue). */
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

/** Copies d'un lot de transactions sur le point d'etre supprimees, pour « Annuler » (voir services/undoRestore.js). */
function snapshotsBeforeDeletion(transactionsToDelete) {
  return restorationSnapshots(transactionsToDelete, dataStore.state.transactions)
}

function offerRestore(label, snapshots) {
  setLastAction(label, async () => {
    await TransactionsAPI.restore(snapshots)
    await load()
  }, { retryable: true })
}

/** Valeurs de départ de la fenêtre « Nouvelle transaction » (TransactionCreateModal.vue), null fenêtre fermée. */
const newTransactionDraft = ref(null)

// --- Édition inline ---
// L'etat du formulaire d'edition (editForm) vit desormais localement dans
// TransactionRow.vue : seul l'id de la ligne en cours d'edition reste ici,
// pour garantir qu'une seule ligne est editee a la fois. Voir le commentaire
// en tete de TransactionRow.vue pour le detail de l'optimisation.
const editingId = ref(null)

/**
 * Sélection multiple (checkboxes) pour l'édition en masse ci-dessous. Un
 * `Set` reactif (plutot qu'un tableau) : `selectedIds.has(id)` passe en O(1)
 * par transaction affichee, sans scan lineaire a chaque rendu, meme sur un
 * grand historique. Persiste volontairement a travers les changements de
 * page/filtre (l'utilisateur peut ainsi selectionner sur plusieurs pages) ;
 * seul un clic explicite sur "Désélectionner tout" ou une édition en masse
 * reussie la vide.
 */
const selectedIds = ref(new Set())
const selectedCountLabel = computed(() => pluralize(selectedIds.value.size, 'transaction', 'transactions'))

/**
 * Derniere transaction cochee (voir toggleSelect) : utilisee pour recentrer
 * la page dessus quand la recherche se ferme (voir le watcher de
 * searchQuery plus bas et goToSelectedTransactionPage), pour qu'on retrouve
 * une transaction reperee pendant une recherche avec son contexte (les
 * transactions voisines) une fois la recherche fermee.
 */
const lastSelectedId = ref(null)

/**
 * Coche / décoche une transaction. Avec `range` (Maj+clic, Maj+Espace),
 * coche toutes les transactions affichées entre la dernière cochée et
 * celle-ci ; sans dernière cochée encore affichée, sélection simple.
 */
function toggleSelect(transaction, { range = false } = {}) {
  const rangeIds = range && lastSelectedId.value != null
    ? idsBetween(pagedTransactions.value, lastSelectedId.value, transaction.id)
    : null
  if (rangeIds) {
    for (const id of rangeIds) selectedIds.value.add(id)
    lastSelectedId.value = transaction.id
    return
  }
  if (selectedIds.value.has(transaction.id)) {
    selectedIds.value.delete(transaction.id)
    if (lastSelectedId.value === transaction.id) lastSelectedId.value = null
  } else {
    selectedIds.value.add(transaction.id)
    lastSelectedId.value = transaction.id
  }
}

function clearSelection() {
  selectedIds.value.clear()
  lastSelectedId.value = null
}

/**
 * Échap vide la sélection, sauf si Échap sert déjà ailleurs : saisie en
 * cours, fenêtre ouverte, ligne en édition (Échap annule alors l'édition).
 */
function onSelectionEscape(event) {
  if (event.key !== 'Escape' || !selectedIds.value.size || editingId.value !== null) return
  const target = event.target
  const typing = target instanceof HTMLElement && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) && target.type !== 'checkbox'
  if (typing || document.querySelector('.modal-overlay, .feedback-overlay, .palette-overlay')) return
  clearSelection()
}

onMounted(() => window.addEventListener('keydown', onSelectionEscape))
onBeforeUnmount(() => window.removeEventListener('keydown', onSelectionEscape))

const allPagedSelected = computed(() =>
  pagedTransactions.value.length > 0 && pagedTransactions.value.every(t => selectedIds.value.has(t.id))
)

function toggleSelectAllPaged() {
  if (allPagedSelected.value) {
    for (const t of pagedTransactions.value) selectedIds.value.delete(t.id)
  } else {
    for (const t of pagedTransactions.value) selectedIds.value.add(t.id)
  }
}

/**
 * Montant par id sur la liste complete (tous comptes, voir dataStore) plutot
 * que sur `transactions` (potentiellement filtree par compte) : la selection
 * persiste a travers les filtres (voir selectedIds plus haut), le total doit
 * donc rester juste meme si une transaction selectionnee n'appartient plus
 * au compte actuellement filtre.
 */
const amountById = computed(() => {
  const map = new Map()
  for (const t of dataStore.state.transactions) map.set(t.id, Number(t.amount || 0))
  return map
})

/**
 * Transactions actuellement selectionnees, lues dans la liste globale (tous
 * comptes, voir dataStore) et non dans `transactions` (potentiellement
 * filtree par compte) : la selection persiste a travers les filtres, et
 * l'action de masse (backend) porte sur TOUS les ids selectionnes — un
 * instantane pris sur la seule liste filtree oubliait les autres comptes,
 * qui n'etaient alors jamais restaures par "Annuler".
 */
function selectedTransactionsSnapshot() {
  const byId = new Map(dataStore.state.transactions.map(t => [t.id, t]))
  return Array.from(selectedIds.value, id => byId.get(id)).filter(Boolean)
}

/** Somme des transactions selectionnees, affichee dans la barre de selection multiple. */
const selectedTotal = computed(() => {
  const amounts = amountById.value
  let sum = 0
  for (const id of selectedIds.value) sum += amounts.get(id) || 0
  return sum
})

/** Édition en masse des transactions sélectionnées (saisie : TransactionBulkEditModal.vue). */
const showBulkEditModal = ref(false)
const bulkSaving = ref(false)

function openBulkEdit() {
  showBulkEditModal.value = true
}

async function saveBulkEdit(bulkForm) {
  if (!selectedIds.value.size) return

  // Champs effectivement touches par cette modification en masse : reutilises
  // pour ne restaurer que ceux-la lors de l'annulation (voir plus bas), via
  // bulkUpdate. TransactionsAPI.update() reenverrait TOUS les champs (compte,
  // date, montant...) et, sur un cote de virement, les repercuterait sur sa
  // jumelle (voir ServiceTransaction#syncTransferTwin) : l'annulation doit
  // defaire la modification en masse, rien de plus.
  const touchedFields = bulkTouchedFields(bulkForm)
  const payload = bulkUpdatePayload(Array.from(selectedIds.value), touchedFields, bulkForm)

  const previousSnapshots = selectedTransactionsSnapshot()
    .map(t => ({ id: t.id, before: previousTransactionPayload(t) }))

  bulkSaving.value = true
  try {
    await TransactionsAPI.bulkUpdate(payload)
    showBulkEditModal.value = false
    clearSelection()
    await load()
    setLastAction(`Modification de ${pluralize(previousSnapshots.length, 'transaction', 'transactions')}`, async () => {
      await runOnePerPayee(
        previousSnapshots,
        ({ before }) => (touchedFields.payee ? before.payee : null),
        ({ id, before }) => TransactionsAPI.bulkUpdate(bulkUpdatePayload([id], touchedFields, before))
      )
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la modification en masse', e)
  } finally {
    bulkSaving.value = false
  }
}

function emptyForm() {
  return {
    accountId: null,
    date: toLocalIsoDate(),
    payee: '',
    // Vide plutôt que 0 : on tape directement le montant, sans effacer un zéro.
    amount: null,
    categoryId: null,
    paymentMode: 'NONE',
    memo: '',
    transferTransactionId: null,
    periodicExpense: false,
    exceptional: false,
    validated: true
  }
}

/**
 * Transactions en attente de la liste courante : celles du compte filtré, ou
 * de tous les comptes. « Tout valider » et « Supprimer les non validées »
 * portent sur ce même périmètre, pour que le nombre annoncé soit le bon.
 */
const pendingCount = computed(() =>
  transactions.value.filter(t => !t.validated).length
)

/** Compte filtré (en-tête AccountSummaryBar), null sur « Tous les comptes ». */
const selectedAccount = computed(() => (filterAccount.value != null ? accountsById.value.get(filterAccount.value) ?? null : null))
const selectedAccountBalance = computed(() => Number(dataStore.state.balances[filterAccount.value] ?? 0))

/** Date de la plus ancienne opération en attente du compte filtré (comparaison de dates ISO). */
const oldestPendingDate = computed(() => {
  let oldest = null
  for (const t of transactions.value) {
    if (!t.validated && t.date && (oldest === null || t.date < oldest)) oldest = t.date
  }
  return oldest
})

/** Précision ajoutée aux confirmations quand la liste est filtrée sur un compte. */
const pendingScopeLabel = computed(() => {
  const account = filterAccount.value !== null ? accountsById.value.get(filterAccount.value) : null
  return account ? ` du compte « ${account.name} »` : ''
})

const uncategorizedCount = computed(() =>
  transactions.value.filter(needsCategory).length
)

// Categories a plat { id, label, type, selectable } (voir flattenCategories),
// partagees par les autocompletes et l'index de recherche ci-dessous.
const flatCategories = computed(() => flattenCategories(categories.value))

/**
 * Libelle categorie ("Parent : Enfant", meme format que CategoryAutocomplete
 * — voir flattenCategories) par id, pour que la recherche retrouve aussi une
 * transaction par sa categorie (ex: "Traitements et salaires : Salaire"),
 * pas seulement par tiers/montant.
 */
const categoryLabelById = computed(() => {
  const map = new Map()
  for (const c of flatCategories.value) map.set(c.id, c.label)
  return map
})

/**
 * Nom "brut" de la categorie (juste "Salaire", sans le prefixe parent ni
 * l'icone) par id — distinct de categoryLabelById ci-dessus : une recherche
 * exacte ("Salaire" entre guillemets) doit matcher ce nom precis, alors que
 * le libelle complet ("Traitements et salaires : Salaire") ne serait jamais
 * strictement egal a ce que l'utilisateur tape.
 */
const categoryNameById = computed(() => {
  const map = new Map()
  for (const c of categories.value) map.set(c.id, c.name)
  return map
})

/** Champs de recherche normalisés, recalculés seulement quand la liste change (voir services/transactionFilters.js). */
const searchIndexById = computed(() =>
  buildSearchIndex(transactions.value, categoryLabelById.value, categoryNameById.value))

/** Statut, critères du tableau de bord et mois / année : étape séparée, jamais refaite à la frappe. */
const statusAndDateFiltered = computed(() => filterByStatusAndPeriod(transactions.value, {
  pendingOnly: pendingOnly.value,
  uncategorizedOnly: uncategorizedOnly.value,
  drillDownMatch: drillDown.value ? drillDownMatcher(transactions.value, drillDown.value, categoriesById.value) : null,
  month: filterMonth.value,
  year: filterYear.value
}))

/** Recherche texte (« Bouygues;29,99 », "CAF" exact) ou comparaison de montant (« > 100 »). */
const filteredTransactions = computed(() =>
  filterBySearch(statusAndDateFiltered.value, searchQuery.value, amountQuery.value, searchIndexById.value))

/**
 * Tri par clic sur les en-têtes Date / Tiers / Dépense / Revenu (voir
 * services/transactionSort.js). Calculé une fois par changement de filtre
 * ou de tri ; l'ordre par défaut (date décroissante) ne coûte rien.
 */
const sort = ref(DEFAULT_TRANSACTION_SORT)
const sortedTransactions = computed(() => sortTransactions(filteredTransactions.value, sort.value))

/**
 * Exporte les transactions affichées (filtres, recherche et tri compris,
 * toutes pages confondues) au format CSV de HomeBank, réimportable (voir
 * services/csvExport.js). Le nom du fichier reprend le compte filtré.
 */
function exportCsv() {
  if (!sortedTransactions.value.length) {
    notify('Aucune transaction à exporter avec les filtres actuels.', 'warning')
    return
  }
  const accountName = filterAccount.value != null ? accountsById.value.get(filterAccount.value)?.name : null
  const fileName = safeFileName(`transactions ${accountName ?? 'tous comptes'} ${toLocalIsoDate(new Date())}`) + '.csv'
  const csv = transactionsToCsv(sortedTransactions.value, { categoriesById: categoriesById.value, accountsById: accountsById.value })
  downloadCsv(fileName, csv)
  notify(`${pluralize(sortedTransactions.value.length, 'transaction exportée', 'transactions exportées')}.`, 'success')
}

function toggleSort(key) {
  sort.value = nextSort(sort.value, key, TRANSACTION_SORT_DIRECTIONS)
}

function ariaSort(key) {
  return ariaSortOf(sort.value, key)
}

function sortIcon(key) {
  return sortIconOf(sort.value, key)
}

/**
 * Chargement progressif au scroll cote client (voir services/progressiveList.js) :
 * avec un historique de plusieurs milliers de transactions, rendre TOUTES
 * les lignes filtrees a chaque frappe (un composant TransactionRow par
 * ligne) rendait la recherche inutilisable. Lots de 50 lignes (~2-3 ecrans) :
 * 100 lignes rendaient encore la frappe perceptiblement lente (50-100 ms
 * mesures sur ~4 000 transactions).
 */
const {
  visibleItems: pagedTransactions,
  hasMore: hasMoreToShow,
  sentinel: loadMoreSentinel,
  reset: resetVisibleTransactions,
  revealIndex: revealTransactionIndex
} = useProgressiveList(sortedTransactions, { pageSize: 50 })

/**
 * Intertitres de mois : totaux calculés sur TOUTE la liste filtrée (pas
 * seulement le lot affiché), une fois par changement de filtre — jamais
 * recalculés ligne par ligne au rendu.
 */
const monthSummaries = computed(() => summarizeByMonth(filteredTransactions.value))

/** Intertitre de mois avant la ligne `index` : seulement en ordre chronologique (sinon les mois s'entremêlent). */
function monthStartsAt(index) {
  return isChronologicalSort(sort.value) && startsNewMonth(pagedTransactions.value, index)
}

function monthSummary(isoDate) {
  return monthSummaries.value.get(monthKey(isoDate)) || { count: 0, total: 0 }
}

const monthHeading = formatMonthHeading

/**
 * Recherche par montant avec operateur (">", "<", "=") : filtree localement
 * sur les transactions deja chargees (cache partage), comme la recherche
 * texte — resultat immediat, sans appel reseau ni attente a chaque frappe.
 */
const amountQuery = computed(() => parseAmountQuery(searchQuery.value))

/**
 * Etend le lot rendu pour que la transaction cochee `id` (voir
 * lastSelectedId) fasse partie du lot actuellement rendu, puis fait defiler
 * la ligne correspondante jusqu'au centre de l'ecran une fois le DOM a jour
 * — pour la retrouver avec les transactions voisines visibles autour d'elle.
 * Si la transaction n'est plus dans la liste filtree courante (ex : filtre
 * "À traiter" actif et transaction validee), retombe simplement sur le
 * premier lot.
 */
function scrollToSelectedTransaction(id) {
  const index = sortedTransactions.value.findIndex(t => t.id === id)
  if (!revealTransactionIndex(index)) {
    resetVisibleTransactions()
    return
  }
  nextTick(() => {
    document.querySelector(`tr[data-transaction-id="${id}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

// Revenir au premier lot des qu'un filtre change le resultat, pour ne jamais
// rester bloque sur un decompte devenu incoherent avec la nouvelle liste —
// sauf quand la recherche vient tout juste de se fermer (texte redevenu
// vide) alors qu'une transaction est cochee : on se repositionne alors sur
// elle plutot que de revenir au debut (voir scrollToSelectedTransaction
// ci-dessus).
watch(
  [searchQuery, filterAccount, filterMonth, filterYear, pendingOnly, uncategorizedOnly, drillDown, sort],
  ([newSearch], [oldSearch]) => {
    const searchJustClosed = !!(oldSearch && oldSearch.trim()) && !(newSearch && newSearch.trim())
    if (searchJustClosed && lastSelectedId.value) {
      scrollToSelectedTransaction(lastSelectedId.value)
      return
    }
    resetVisibleTransactions()
  }
)

// Index Map (id -> objet) pour accounts/categories, precalcules une seule
// fois quand ces listes changent (rarement), et passes en prop a chaque
// TransactionRow : evite un scan lineaire de accounts/categories pour
// chaque ligne affichee (categoryLabel/accountName vivent desormais dans
// TransactionRow.vue et consomment ces Map en O(1)).
const accountsById = computed(() => new Map(accounts.value.map(a => [a.id, a])))

/**
 * Colonne « Solde » : seulement quand la liste est filtrée sur un compte (un
 * solde cumulé mélangeant plusieurs comptes n'aurait pas de sens). Calculé
 * sur tout l'historique du compte, indépendamment de la période, de la
 * recherche et du tri affichés ; recalculé seulement quand le cache ou le
 * compte change, jamais à la frappe.
 */
const showBalance = computed(() => filterAccount.value != null && accountsById.value.has(filterAccount.value))
const runningBalances = computed(() =>
  showBalance.value ? runningBalanceById(dataStore.state.transactions, accountsById.value.get(filterAccount.value)) : null
)
const categoriesById = computed(() => new Map(categories.value.map(c => [c.id, c])))

/**
 * Une transaction est "future" si sa date est strictement postérieure à
 * aujourd'hui : elle est prévue mais n'a pas encore réellement eu lieu
 * (comparaison de chaînes ISO "AAAA-MM-JJ", fiable sans passer par Date()).
 */
const todayIso = toLocalIsoDate()

/**
 * Recharge le cache partagé après une modification (l'affichage courant reste
 * visible pendant ce temps), soldes des comptes compris (voir dataStore.js).
 * Le filtre compte s'applique ensuite seul, voir `transactions`. Les tiers
 * suivent : une saisie peut en créer un, une suppression en retirer un devenu
 * orphelin, et l'autocomplétion doit le refléter tout de suite.
 */
function load() {
  return Promise.all([dataStore.refreshTransactions(), dataStore.refreshPayees()])
}

/**
 * Categorisation automatique par tiers : un tiers connu (page "Tiers") peut
 * avoir une seule categorie par defaut (Payee#category cote backend). Quand
 * l'utilisateur saisit/selectionne un tiers reconnu dans le formulaire, ce
 * champ est rempli avec la categorie par defaut du tiers — y COMPRIS si une
 * categorie etait deja renseignee (ex : deja pre-remplie a tort par un tiers
 * homonyme avant la desambiguisation par montant, voir
 * services/payee.js#findPayeeByName) : c'est le fait de choisir/changer explicitement le tiers qui
 * declenche la mise a jour, jamais un rafraichissement en arriere-plan.
 * Ce pre-remplissage est desormais la SEULE source de la categorie par
 * defaut du tiers : le backend (TransactionService#applyPayload) applique la
 * categorie du payload telle quelle, y compris null (transaction rendue non
 * categorisee) — il ne la reapplique plus depuis le tiers, sans quoi effacer
 * volontairement la categorie d'une transaction deja categorisee (bouton ✕ de
 * CategoryAutocomplete) etait silencieusement annule des que son tiers avait
 * une categorie par defaut.
 */
// Indexe les tiers par nom normalise, memoise (recalcule uniquement quand
// la liste des tiers change, pas a chaque frappe) : evite un scan lineaire
// de tous les tiers a chaque caractere tape dans le champ Tiers. Un nom peut
// correspondre a PLUSIEURS tiers (voir Tiers#amount : "Bouygues" a 10€ et a
// 20€ sont deux tiers distincts avec le meme nom affiche), d'ou le tableau
// plutot qu'un seul tiers par nom — voir services/payee.js#findPayeeByName,
// qui les departage par montant.
const payeeByNormalizedName = computed(() => {
  const map = new Map()
  for (const p of payees.value) {
    if (!p.name) continue
    const key = p.name.trim().toLowerCase()
    const list = map.get(key)
    if (list) list.push(p)
    else map.set(key, [p])
  }
  return map
})

/**
 * Compte proposé pour une nouvelle transaction : le compte filtré, quand on
 * consulte un compte précis (sauf s'il est archivé, donc fermé à la saisie),
 * sinon le premier compte actif.
 */
const defaultEntryAccountId = computed(() => {
  const accounts = entryAccounts.value
  return accounts.find(a => a.id === filterAccount.value)?.id ?? accounts[0]?.id ?? null
})

function openCreate() {
  newTransactionDraft.value = { ...emptyForm(), accountId: defaultEntryAccountId.value }
}

/**
 * Ouvre la création pré-remplie avec une transaction existante (tiers,
 * montant, catégorie, mode de paiement, mémo...), datée d'aujourd'hui : pour
 * une dépense qui revient sans être assez régulière pour une suggestion
 * périodique. Rien n'est créé avant « Enregistrer ». Le compte d'origine
 * est repris s'il est encore actif, sinon le compte par défaut.
 */
function duplicateTransaction(transaction) {
  if (transaction.transferTransactionId || transaction.balanceAdjustment) {
    notify('Un virement ou un ajustement de solde se crée depuis la page Comptes.', 'warning')
    return
  }
  const accountIsActive = entryAccounts.value.some(a => a.id === transaction.accountId)
  newTransactionDraft.value = {
    ...emptyForm(),
    accountId: accountIsActive ? transaction.accountId : defaultEntryAccountId.value,
    payee: transaction.payee || '',
    amount: transaction.amount,
    categoryId: transaction.categoryId,
    paymentMode: transaction.paymentMode || 'NONE',
    memo: transaction.memo || '',
    periodicExpense: !!transaction.periodicExpense,
    exceptional: !!transaction.exceptional
  }
}

/** Enregistre le formulaire validé par TransactionCreateModal.vue. */
async function save(form) {
  const payload = transactionPayloadFrom(form)
  // Double saisie probable : confirmation explicite plutôt que refus (deux
  // achats identiques le même jour restent possibles).
  const identical = findIdenticalTransaction(payload, dataStore.state.transactions)
  if (identical) {
    const label = `${identical.payee || 'sans tiers'}, ${formatAmount(identical.amount)}, ${formatDate(identical.date)}`
    const confirmed = await askConfirm(`Une transaction identique existe déjà sur ce compte (${label}). L'enregistrer quand même ?`, {
      title: 'Transaction en double ?',
      confirmLabel: 'Enregistrer quand même',
      destructive: false
    })
    if (!confirmed) return
  }

  try {
    const created = await TransactionsAPI.create(payload)
    notify('Transaction créée.', 'success')
    newTransactionDraft.value = null
    dataStore.showSavedTransaction(created)
    await load()
    setLastAction('Transaction créée', async () => {
      await TransactionsAPI.undoCreation([created.id])
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la création de la transaction', e)
  }
}

/* --- Édition inline : clic sur une ligne --- */

function startEdit(t) {
  // Un virement est modifiable : date, montant, mémo et validation sont
  // répercutés sur l'autre côté par le backend (voir ServiceTransaction#syncTransferTwin).
  editingId.value = t.id
}

function cancelEdit() {
  editingId.value = null
}

async function confirmEdit(payload) {
  if (!editingId.value) return
  const id = editingId.value
  const before = transactions.value.find(t => t.id === id)
  // editingId n'est efface qu'apres le succes de l'appel : sinon, un echec
  // (validation, reseau) fermait silencieusement l'edition sans que rien ne
  // soit enregistre ni explique a l'utilisateur, qui devait tout retaper
  // sans comprendre pourquoi. En laissant l'edition ouverte, TransactionRow
  // (qui garde editForm en etat local tant que isEditing reste vrai) ne
  // perd pas la saisie en cours.
  let saved
  try {
    saved = await TransactionsAPI.update(id, payload)
  } catch (e) {
    alertApiError('Erreur lors de la modification', e)
    return
  }
  editingId.value = null
  dataStore.showSavedTransaction(saved)
  await load()
  if (before) {
    const previousPayload = previousTransactionPayload(before)
    setLastAction('Transaction modifiée', async () => {
      await TransactionsAPI.update(id, previousPayload)
      await load()
    })
  }
}

async function remove(transaction) {
  if (!(await askConfirm('Supprimer cette transaction ?'))) return
  const snapshots = snapshotsBeforeDeletion([transaction])
  const nextFocusId = neighborRowId(pagedTransactions.value, transaction.id)
  try {
    await TransactionsAPI.remove(transaction.id)
    dataStore.hideDeletedTransactions([transaction.id])
    await load()
    if (nextFocusId != null) restoreFocusToRow(`tr[data-transaction-id="${nextFocusId}"]`)
    notify('Transaction supprimée.', 'success')
    offerRestore('Transaction supprimée', snapshots)
  } catch (e) {
    alertApiError('Erreur lors de la suppression de la transaction', e)
  }
}

/* --- Division d'une transaction en deux --- */

/** Transaction en cours de division (saisie : TransactionSplitModal.vue), null modale fermée. */
const splitTransaction = ref(null)
const splitSaving = ref(false)
/** Erreur renvoyée par le serveur, affichée dans la modale. */
const splitError = ref('')

/** Devise du compte de la transaction divisée, pour afficher ses montants dans la bonne monnaie. */
const splitCurrency = computed(() => accountsById.value.get(splitTransaction.value?.accountId)?.currency)

function openSplit(transaction) {
  // Seul un virement interne (lié à sa jumelle) est indivisible ; un paiement
  // par virement ordinaire (loyer…) se divise comme une autre dépense.
  if (transaction.transferTransactionId) {
    notify('Un virement entre comptes ne peut pas être divisé.', 'warning')
    return
  }
  if (transaction.balanceAdjustment) {
    notify('Un ajustement de solde ne peut pas être divisé.', 'warning')
    return
  }
  splitError.value = ''
  splitTransaction.value = transaction
}

function closeSplitModal() {
  splitTransaction.value = null
}

async function saveSplit(parts) {
  splitError.value = ''
  splitSaving.value = true
  // Transaction d'origine telle qu'avant la division, pour « Annuler ».
  const original = splitTransaction.value
  const originalState = { ...previousTransactionPayload(original), splitFromAmount: original.splitFromAmount ?? null }
  try {
    const [, second] = await TransactionsAPI.split(original.id, parts)
    closeSplitModal()
    await load()
    // Annuler : supprime la 2e partie et rend à la 1re son état d'origine.
    setLastAction('Transaction divisée', async () => {
      await TransactionsAPI.undoSplit(original.id, { secondPartId: second.id, original: originalState })
      await load()
    }, { retryable: true })
  } catch (e) {
    splitError.value = apiErrorMessage(e)
  } finally {
    splitSaving.value = false
  }
}

/**
 * Bascule d'un drapeau booleen d'une transaction (une bascule etant sa propre
 * inverse, l'annulation rejoue simplement le meme appel). Aucun solde ne
 * change : seule la transaction renvoyee est appliquee, sans retelecharger
 * toute la liste a chaque clic.
 */
async function toggleFlag(apiCall, transaction, label) {
  const toggle = async () => dataStore.applyTransactionUpdate(await apiCall(transaction.id))
  try {
    await toggle()
    setLastAction(label, toggle)
  } catch (e) {
    alertApiError('Erreur lors de la modification de la transaction', e)
  }
}

/** Bascule directement le statut "dépense périodique" sans passer par l'édition complète. */
function togglePeriodic(transaction) {
  return toggleFlag(TransactionsAPI.togglePeriodicExpense, transaction, 'Statut "dépense périodique" modifié')
}

/** Bascule directement le statut "exceptionnelle" sans passer par l'édition complète. */
function toggleExceptional(transaction) {
  return toggleFlag(TransactionsAPI.toggleExceptional, transaction, 'Statut "exceptionnelle" modifié')
}

/* --- Validation / rapprochement (façon "reconcile" YNAB) --- */

const validating = ref(false)

/** Bascule le statut validée/en attente d'une seule transaction. */
function toggleValidated(transaction) {
  return toggleFlag(TransactionsAPI.toggleValidated, transaction, 'Statut "validée" modifié')
}

/**
 * Valide en une fois toutes les transactions en attente : à utiliser une fois
 * que le solde du compte dans l'app correspond au solde réel de la banque,
 * comme le "reconcile" de YNAB. Permet ensuite de repérer d'un coup d'œil
 * les transactions ajoutées depuis la dernière validation (pastille orange).
 */
async function validateAll() {
  if (pendingCount.value === 0) return
  if (!(await askConfirm(`Valider ${pendingCount.value === 1 ? 'la transaction' : `les ${pendingCount.value} transactions`} en attente${pendingScopeLabel.value} ?`))) return
  validating.value = true
  // Même périmètre que pendingCount (compte filtré ou tous les comptes, voir
  // ServiceTransaction#validateAllPending). L'annulation remet ces ids en
  // attente ; bulkUpdate y joint la jumelle d'un virement sur un autre compte.
  const pendingIds = transactions.value.filter(t => !t.validated).map(t => t.id)
  try {
    await TransactionsAPI.validateAll(filterAccount.value ?? undefined)
    await load()
    notify(`${pluralize(pendingIds.length, 'transaction validée', 'transactions validées')}.`, 'success')
    setLastAction(`Validation de ${pluralize(pendingIds.length, 'transaction', 'transactions')}`, async () => {
      // Remise explicite « en attente » (et non une bascule par transaction) :
      // basculer un côté de virement bascule aussi sa jumelle, si bien que
      // deux bascules successives (une par côté, tous deux en attente)
      // laissaient le virement validé après l'annulation.
      await TransactionsAPI.bulkUpdate({ ids: pendingIds, validatedProvided: true, validated: false })
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la validation des transactions', e)
  } finally {
    validating.value = false
  }
}

const deletingPending = ref(false)

/** Supprime d'un coup toutes les transactions en attente de validation, sans passer par la selection manuelle. */
async function deletePending() {
  if (pendingCount.value === 0) return
  if (!(await askConfirm(`Supprimer ${pendingCount.value === 1 ? 'la transaction non validée' : `les ${pendingCount.value} transactions non validées`}${pendingScopeLabel.value} ?`))) return
  deletingPending.value = true
  // Même périmètre que pendingCount et validateAll ci-dessus (voir
  // ServiceTransaction#deleteAllPending) ; snapshotsBeforeDeletion retrouve la
  // jumelle d'un virement, supprimée avec lui, dans la liste globale.
  const pendingTransactions = transactions.value.filter(t => !t.validated)
  const candidateSnapshots = snapshotsBeforeDeletion(pendingTransactions)
  try {
    await TransactionsAPI.removePending(filterAccount.value ?? undefined)
    await load()
    notify('Transactions en attente supprimées.', 'success')
    // Un cote de virement en attente dont la jumelle est deja validee est
    // ignore par le backend (voir ServiceTransaction#deleteAllPending) : il
    // est donc toujours present apres le rechargement, et ne doit pas etre
    // "restaure" (il n'a jamais ete supprime, ca creerait un doublon). Lu
    // dans `transactions`, rechargee par load() que le compte soit filtre
    // ou non.
    const stillPresentIds = new Set(transactions.value.map(t => t.id))
    const snapshots = candidateSnapshots.filter(t => !stillPresentIds.has(t.id))
    const actuallyDeletedCount = pendingTransactions.filter(t => !stillPresentIds.has(t.id)).length
    offerRestore(`Suppression de ${pluralize(actuallyDeletedCount, 'transaction', 'transactions')}`, snapshots)
  } catch (e) {
    alertApiError('Erreur lors de la suppression des transactions en attente', e)
  } finally {
    deletingPending.value = false
  }
}

const deletingSelection = ref(false)

/** Supprime d'un coup toutes les transactions actuellement cochees (voir selectedIds). */
async function deleteSelected() {
  if (!selectedIds.value.size) return
  if (!(await askConfirm(`Supprimer ${selectedIds.value.size === 1 ? 'la transaction sélectionnée' : `les ${selectedIds.value.size} transactions sélectionnées`} ?`))) return
  deletingSelection.value = true
  const ids = Array.from(selectedIds.value)
  const selectedTransactions = selectedTransactionsSnapshot()
  const snapshots = snapshotsBeforeDeletion(selectedTransactions)
  try {
    await TransactionsAPI.removeBulk(ids)
    notify(`${pluralize(selectedTransactions.length, 'transaction supprimée', 'transactions supprimées')}.`, 'success')
    clearSelection()
    dataStore.hideDeletedTransactions(ids)
    await load()
    offerRestore(`Suppression de ${pluralize(selectedTransactions.length, 'transaction', 'transactions')}`, snapshots)
  } catch (e) {
    alertApiError('Erreur lors de la suppression des transactions sélectionnées', e)
  } finally {
    deletingSelection.value = false
  }
}

onMounted(async () => {
  // Si le prechargement lance au demarrage de l'app est deja termine (cas
  // courant), ceci est instantane et n'attend rien. Sinon (premier
  // chargement de l'app directement sur /transactions), on attend juste
  // cette premiere fois.
  try {
    await dataStore.ensureLoaded()
  } catch (e) {
    alertApiError('Erreur lors du chargement des données', e)
    return
  }
  // Revalidation silencieuse en arriere-plan a chaque visite de la page,
  // au cas ou des comptes/categories/tiers/transactions ont change depuis
  // le prechargement (cree ailleurs, autre onglet...).
  dataStore.revalidate()

  applyRouteQuery(route.query)
})

/**
 * Paramètres d'URL reconnus (liens internes, palette de commandes Ctrl+K) :
 * - edit=<id>   : ouvre la transaction en édition (« À traiter » > « Modifier ») ;
 * - search=<t>  : pré-remplit la recherche ;
 * - pending=1 / uncategorized=1 : active le filtre « À traiter » / « Sans catégorie » ;
 * - account=<id> : filtre sur ce compte (lien « opérations en attente » du rapprochement) ;
 * - new=1       : ouvre la fenêtre de création ;
 * - export=csv  : exporte les transactions affichées (palette « Exporter les transactions en CSV »).
 * Réappliqués à chaque changement de query : la page n'est pas remontée
 * quand on y navigue alors qu'elle est déjà affichée.
 */
function applyRouteQuery(query) {
  const editId = query.edit ? Number(query.edit) : null
  if (editId) {
    const target = transactions.value.find(t => t.id === editId)
    if (target) startEdit(target)
  }
  if (typeof query.search === 'string') searchQuery.value = query.search
  if (query.uncategorized) uncategorizedOnly.value = true
  drillDown.value = parseDrillDown(query)
  if (drillDown.value) {
    // La période du tableau de bord remplace le filtre mois/année de la page.
    filterMonth.value = null
    filterYear.value = null
  }
  if (query.pending) pendingOnly.value = true
  const accountId = query.account ? Number(query.account) : null
  if (accountId) filterAccount.value = accountId
  if (query.new && entryAccounts.value.length) {
    openCreate()
    // Retire "new" de l'URL : un retour arrière ou un nouveau raccourci
    // (touche N, palette) doit pouvoir rouvrir la création.
    const rest = { ...query }
    delete rest.new
    router.replace({ query: rest })
  }
  if (query.export === 'csv') {
    // Après application des filtres ci-dessus (et calcul de la liste
    // filtrée) ; retiré de l'URL pour pouvoir relancer la commande.
    nextTick(exportCsv)
    const rest = { ...query }
    delete rest.export
    router.replace({ query: rest })
  }
}

/** Vrai si la liste filtrée contient une transaction lissée sur 12 mois au tableau de bord (total différent du montant cliqué). */
const drillDownHasSmoothed = computed(() =>
  !!drillDown.value && hasSmoothedContribution(transactions.value, drillDown.value, categoriesById.value)
)

/** Retire le filtre du tableau de bord (et « Sans catégorie » qu'il avait activé) : retour à la liste complète. */
function clearDrillDown() {
  if (route.query.uncategorized && drillDown.value) uncategorizedOnly.value = false
  drillDown.value = null
  const rest = { ...route.query }
  for (const key of DRILL_DOWN_QUERY_KEYS) delete rest[key]
  router.replace({ query: rest })
}

watch(() => route.query, (query) => {
  if (dataStore.state.loaded.transactions) applyRouteQuery(query)
})

// ?search= ne sert qu'à pré-remplir : dès que la recherche est modifiée ou
// effacée, on le retire de l'URL pour qu'un rechargement ne la remette pas.
watch(searchQuery, (query) => {
  if (route.query.search === undefined || query === route.query.search) return
  const rest = { ...route.query }
  delete rest.search
  router.replace({ query: rest })
})
</script>

<style scoped>
/* Colonnes étroites de sélection et de statut : la troncature des cellules (…)
   s'y déclenchait pour un simple espace et affichait un point après la case. */
.transactions-table thead th:first-child,
.transactions-table :deep(td:is(.cell-select, .cell-status)) { text-overflow: clip; }
/* Recherche : poussée à droite de la barre d'outils (largeur gérée plus bas, voir #tx-search). */
.search-field { margin-left: auto; }
/* Explication sous le titre des fenêtres de modification groupée et de découpage. */
/* Fenêtre « Nouvelle transaction » : bascule Dépense / Revenu sur la ligne
   du libellé, pour laisser toute la largeur au champ montant. */
/* Filtre reçu du tableau de bord : pastille active avec bouton de retrait. */
.drill-down-filter { display: flex; flex-direction: column; gap: 6px; }
.drill-down-chip { gap: 6px; padding-right: 4px; cursor: default; }
.drill-down-clear {
  display: inline-flex;
  border: 0;
  border-radius: 50%;
  padding: 2px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.drill-down-note { font-size: 12px; color: var(--amber); max-width: 320px; }
.drill-down-clear:hover { background: color-mix(in srgb, var(--brand-text) 20%, transparent); }

/*
 * Volontairement pas d'overflow-x ici : un position:sticky sur le thead
 * (ci-dessous) ne colle au scroll de la PAGE que si aucun ancêtre entre lui
 * et le viewport n'a d'overflow non-visible (même overflow-x seul suffit à
 * casser la stickiness, voir memoire du projet). Si le tableau est trop
 * large pour l'ecran, c'est donc toute la page qui scrolle horizontalement
 * plutot qu'un scroll confine au tableau.
 */
.table-scroll {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--ink-900);
  box-shadow: var(--shadow-sm);
}
/* Largeurs calibrées pour tenir sans défilement horizontal sur un écran de
   1440 px (sidebar comprise) ; en dessous, c'est la page qui défile. */
.transactions-table {
  min-width: 1130px;
  width: 100%;
  table-layout: fixed;
}
/* Tiers et Catégorie sont les colonnes qu'on lit : elles prennent la place
   gagnée sur les cases à cocher (en-têtes en icônes) et sur le compte. */
.col-select { width: 36px; }
/* Assez large pour la date suivie du badge « Prévu » (transactions futures). */
.col-date { width: 146px; }
.col-status { width: 40px; }
.col-account { width: 84px; }
.col-payee { width: 200px; }
/* La catégorie prend toute la place restante ; le mémo, peu rempli, reste étroit. */
.col-category { width: auto; }
.col-memo { width: 56px; }
/* Sur grand écran, la catégorie a largement de quoi s'afficher : le mémo et
   le compte récupèrent de la place pour être lisibles sans troncature. */
@media (min-width: 1700px) {
  .col-memo { width: 200px; }
  .col-account { width: 150px; }
}
/* Assez large pour « -100 000,00 € » : jamais de troncature sur un montant. */
.col-amount { width: 104px; }
.col-monthly { width: 46px; }
.col-actions { width: 108px; } /* 3 boutons de 28 px (dupliquer, diviser, supprimer) + écarts et marges */
/* Portables (1 366 à 1 439 px) : le tableau tient sans barre de défilement
   horizontale, en prenant un peu sur le tiers. La colonne Solde réclame plus. */
@media (min-width: 601px) and (max-width: 1439px) {
  .transactions-table { min-width: 1060px; }
  .transactions-table.with-balance { min-width: 1110px; }
  .col-payee { width: 170px; }
  /* Colonne Catégorie étroite : la sous-catégorie seule reste lisible, le parent est dans l'infobulle. */
  .transactions-table :deep(td.col-cat .cat-parent) { display: none; }
}
@media (min-width: 601px) and (max-width: 1535px) {
  .transactions-table.with-balance .col-payee { width: 170px; }
  .transactions-table.with-balance :deep(td.col-cat .cat-parent) { display: none; }
}

/* Intertitre de mois : reste collé sous l'en-tête du tableau (43 px) pendant
   le défilement, pour toujours savoir quel mois on est en train de lire. */
.transactions-table .month-row th {
  position: sticky;
  top: calc(var(--tx-sticky-top) + 43px);
  z-index: 5;
  padding: 7px 12px;
  background: var(--surface-sunken);
  border-top: 1px solid var(--line-strong);
  border-bottom: 1px solid var(--line);
  text-align: left;
  white-space: nowrap;
  overflow: visible;
}
.month-row-label {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 650;
  letter-spacing: -0.01em;
}
.month-row-meta {
  margin-left: 10px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}
.month-row-meta .amount { font-size: 12px; }

.transactions-table th.th-icon {
  text-align: center;
  color: var(--text-faint);
}

.transactions-table th {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* Sous la barre de filtres collante (hauteur + son écart de 14 px avec le tableau). */
.transactions-page {
  --tx-sticky-top: calc(var(--tx-toolbar-height, 0px) + 14px);
}

.transactions-table thead th {
  position: sticky;
  top: var(--tx-sticky-top);
  z-index: 6;
}

/*
 * La colonne Actions reste toujours visible même si le tableau déborde
 * horizontalement (beaucoup de colonnes) : on la fixe à droite plutôt que
 * de forcer l'utilisateur à scroller jusqu'au bout pour supprimer/valider
 * une ligne. Le style des cellules (td) correspondantes vit desormais dans
 * TransactionRow.vue, qui gere sa propre ligne. z-index plus eleve que le
 * thead sticky "simple" ci-dessus car cette cellule cumule top:0 ET right:0
 * (coin haut-droit) et doit rester au-dessus des td sticky (right:0) du
 * corps du tableau pendant le scroll.
 */
.transactions-table th:last-child {
  position: sticky;
  right: 0;
  background: var(--surface-sunken);
  z-index: 7;
  box-shadow: var(--sticky-edge-shadow);
}

/*
 * Separe visuellement les filtres (Compte, Periode) des actions en masse
 * (À traiter / Non categorisees / Tout valider) dans la barre d'outils :
 * sans ca, les 6 champs/boutons se suivaient avec le meme poids visuel et le
 * groupe "actions" se noyait parmi les filtres.
 */
.toolbar-divider {
  align-self: stretch;
  width: 1px;
  margin: 2px 2px;
  background: var(--line);
}
.toolbar-actions {
  display: flex;
  align-items: center;
  align-self: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.text-hint {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 4px;
  line-height: 1.4;
}

/* Place sous la barre de sélection flottante (TransactionSelectionBar.vue) pour ne pas masquer les dernières lignes. */
.transactions-page:has(.bulk-bar) { padding-bottom: 72px; }

.scroll-status {
  margin-top: 12px;
}

/* -----------------------------------------------------------------------
   HomeBank — itération 2 : écran Transactions
   Refonte de présentation uniquement : aucun changement aux filtres,
   événements, appels API, édition inline ou pagination.
   ----------------------------------------------------------------------- */
.transactions-page .page-header {
  align-items: center;
  margin-bottom: 18px;
}

.transactions-page .page-header h1 {
  letter-spacing: -0.035em;
}

/*
 * Barre de filtres toujours visible : collée en haut pendant le défilement.
 * La 2e ombre, pleine et de la couleur du fond de page, masque les lignes qui
 * défilent dans l'écart de 14 px entre la barre et l'en-tête du tableau.
 */
.transactions-toolbar {
  position: sticky;
  top: 0;
  z-index: 8;
  align-items: flex-end;
  gap: 14px;
  padding: 16px;
  margin: 0 0 14px;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm), 0 15px 0 0 var(--ink-950);
}

/* Mobile : la barre (sur plusieurs lignes) prendrait la moitié de l'écran ;
   elle défile normalement, comme l'en-tête du tableau à cette taille. */
@media (max-width: 760px) {
  .transactions-toolbar {
    position: static;
    box-shadow: var(--shadow-sm);
  }
  .transactions-page {
    --tx-toolbar-height: 0px !important;
  }
}

.transactions-toolbar .form-field {
  min-width: 150px;
}

.transactions-toolbar .form-field select,
.transactions-toolbar .form-field input {
  min-height: 38px;
}

.transactions-toolbar .form-field:has(#tx-search) {
  min-width: 230px;
  flex: 1 1 260px;
}

.transactions-toolbar #tx-search {
  width: 100%;
  min-width: 0 !important;
}

.transactions-toolbar .toolbar-divider {
  align-self: stretch;
  margin: 2px 3px 0;
  background: var(--line-strong);
}

.transactions-toolbar .toolbar-actions {
  gap: 7px;
}

.transactions-toolbar .toolbar-actions .btn {
  min-height: 36px;
  white-space: nowrap;
}

.transactions-toolbar .chip {
  min-height: 34px;
  background: var(--surface-sunken);
}

.transactions-toolbar .chip.active {
  background: var(--brand);
  color: var(--brand-text);
}

.transactions-page .table-scroll {
  border-color: var(--line-strong);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
}

.transactions-page .transactions-table {
  font-size: 13px;
  border-spacing: 0;
}

.transactions-page .transactions-table thead th {
  height: 43px;
  padding: 10px 8px;
  background: var(--table-head-bg);
  color: var(--table-head-text);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: .055em;
  text-transform: none;
  border-bottom: 1px solid var(--table-head-border);
}

.transactions-page .transactions-table tbody td {
  padding: 10px 11px;
  border-bottom: 1px solid var(--line);
  font-size: 12.5px;
  line-height: 1.45;
  vertical-align: middle;
}

.transactions-page .transactions-table tbody tr:last-child td {
  border-bottom: 0;
}

.transactions-page .transactions-table tbody tr:hover td {
  background: var(--row-hover);
}

.transactions-page .transactions-table tbody tr.row-selected td,
.transactions-page .transactions-table tbody tr.row-selected:hover td {
  background: var(--row-selected);
}

.transactions-page .transactions-table tbody tr.row-editing td {
  background: var(--row-editing);
}

.transactions-page .transactions-table td.amount {
  font-size: 12.5px;
  font-weight: 650;
  letter-spacing: -.015em;
}


.transactions-page .scroll-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
  padding: 0 4px;
}

.transactions-page .text-hint {
  color: var(--text-muted);
}

@media (max-width: 1180px) {
  .transactions-toolbar {
    align-items: stretch;
  }
  .transactions-toolbar .toolbar-divider {
    display: none;
  }
  .transactions-toolbar .toolbar-actions {
    flex: 1 1 100%;
    align-self: auto;
  }
  .transactions-toolbar .form-field:has(#tx-search) {
    flex: 1 1 240px;
  }
}

@media (max-width: 700px) {
  .transactions-page .page-header {
    align-items: flex-start;
    gap: 12px;
  }
  .transactions-toolbar {
    padding: 12px;
    gap: 10px;
  }
  .transactions-toolbar .form-field,
  .transactions-toolbar .form-field:has(#tx-search) {
    flex: 1 1 100%;
    min-width: 0;
  }
  .transactions-toolbar .toolbar-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .transactions-toolbar .toolbar-actions .btn {
    white-space: normal;
  }
}

/* Téléphone : chaque transaction devient une carte de deux lignes, au lieu d'un
   tableau de 12 colonnes où le montant sortait de l'écran.
     ligne 1 : sélection | tiers           | montant
     ligne 2 : statut    | date            | catégorie
   Compte, mémo, solde, cases périodique/exceptionnelle et actions restent accessibles
   en touchant la ligne (édition, où toutes les cellules s'empilent). */
@media (max-width: 600px) {
  .transactions-page .transactions-table {
    display: block;
    min-width: 0; /* annule les 640 px imposés aux tableaux mobiles (style.css) */
  }
  .transactions-table colgroup,
  .transactions-table thead { display: none; }
  .transactions-table tbody { display: block; }
  .transactions-table tbody :deep(tr) {
    display: grid;
    grid-template-columns: 34px minmax(0, 1fr) auto;
    align-items: center;
    border-bottom: 1px solid var(--line);
  }
  .transactions-table .month-row th { grid-column: 1 / -1; }
  .transactions-page .transactions-table tbody :deep(td) {
    border-bottom: 0;
    padding: 5px 8px;
  }
  .transactions-table :deep(tr:not(.row-editing) td:is(.cell-account, .cell-memo, .cell-balance, .cell-periodic, .cell-exceptional, .cell-actions)),
  .transactions-table :deep(td:is(.cell-expense, .cell-income):empty) { display: none; }
  .transactions-table :deep(td.cell-select) { grid-area: 1 / 1; padding-bottom: 0; }
  .transactions-table :deep(td.col-payee) { grid-area: 1 / 2; padding-bottom: 0; font-size: 13.5px; font-weight: 600; }
  .transactions-table :deep(td:is(.cell-expense, .cell-income)) { grid-area: 1 / 3; padding-bottom: 0; font-size: 13.5px; }
  .transactions-table :deep(td.cell-status) { grid-area: 2 / 1; padding-top: 2px; }
  .transactions-table :deep(td.col-date) { grid-area: 2 / 2; padding-top: 2px; color: var(--text-muted); }
  .transactions-table :deep(td.col-cat) { grid-area: 2 / 3; padding-top: 2px; justify-self: end; max-width: 52vw; }
  /* Sous-catégorie seule : la catégorie parente (dans l'infobulle) tronquait les deux noms. */
  .transactions-table :deep(td.col-cat .cat-parent) { display: none; }
  /* Édition : un champ par ligne, sur toute la largeur. */
  .transactions-table :deep(tr.row-editing td) { grid-column: 1 / -1; }
  .transactions-table :deep(tr.row-editing td.cell-select) { display: none; }
  .transactions-table :deep(tr.row-editing td[data-label]) {
    display: flex;
    align-items: center;
    gap: 8px;
    text-align: left;
  }
  .transactions-table :deep(tr.row-editing td[data-label] input) { width: auto; flex: 0 0 auto; margin: 0; }
  .transactions-table :deep(tr.row-editing td[data-label]::after) {
    content: attr(data-label);
    font-size: 13px;
    color: var(--text-muted);
  }
}
</style>
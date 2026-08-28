<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Historique des opérations — cliquez sur une ligne pour la modifier</div>
      </div>

      <button class="btn" @click="openCreate" :disabled="!accounts.length">
        + Nouvelle transaction
      </button>
    </div>

    <TransactionsSubNav />

    <div class="toolbar">
      <div class="form-field">
        <label>Compte</label>
        <select v-model="filterAccount" @change="load">
          <option :value="null">Tous les comptes</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </div>

      <div class="form-field">
        <label>Mois</label>
        <select v-model="filterMonth">
          <option :value="null">Tous</option>
          <option v-for="m in MONTHS" :key="m.value" :value="m.value">{{ m.label }}</option>
        </select>
      </div>

      <div class="form-field">
        <label>Année</label>
        <select v-model="filterYear">
          <option :value="null">Toutes</option>
          <option v-for="y in availableYears" :key="y" :value="y">{{ y }}</option>
        </select>
      </div>

      <div class="form-field" style="align-self: flex-end;">
        <button
          class="btn"
          :class="{ secondary: !pendingOnly }"
          @click="pendingOnly = !pendingOnly"
          :title="pendingOnly ? 'Revenir à toutes les transactions' : 'N\'afficher que les transactions à traiter'"
        >
          À traiter ({{ pendingCount }})
        </button>
      </div>

      <div class="form-field" style="align-self: flex-end;">
        <button
          class="btn"
          :class="{ secondary: pendingCount === 0 }"
          :disabled="pendingCount === 0 || validating"
          @click="validateAll"
        >
          {{ validating ? 'Validation…' : `Tout valider (${pendingCount} en attente)` }}
        </button>
      </div>

      <div class="form-field" style="margin-left: auto;">
        <label>Recherche (tiers, montant, ou &gt; / &lt; / = un montant)</label>
        <input v-model="searchQuery" placeholder="Ex : Carrefour, 42,50 ou > 100" style="min-width: 260px;" />
        <div v-if="amountQuery" class="text-hint">
          {{ amountSearchLoading ? 'Recherche…' : `${filteredTransactions.length} transaction(s) trouvée(s)` }}
        </div>
      </div>
    </div>

    <div v-if="selectedIds.size" class="bulk-bar">
      <span>{{ selectedIds.size }} transaction(s) sélectionnée(s)</span>
      <button class="btn" @click="openBulkEdit">Modifier en masse</button>
      <button class="btn secondary" @click="clearSelection">Désélectionner tout</button>
    </div>

    <div class="table-scroll" v-if="filteredTransactions.length">
      <table class="transactions-table">
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
                title="Sélectionner/désélectionner toute la page"
                @change="toggleSelectAllPaged"
              />
            </th>
            <th title="Statut de validation (rapprochement bancaire)">Statut</th>
            <th>Date</th>
            <th>Compte</th>
            <th>Tiers</th>
            <th>Catégorie</th>
            <th>Mémo</th>
            <th class="num">Dépense</th>
            <th class="num">Revenu</th>
            <th class="num" title="Dépense périodique récurrente">Périodique</th>
            <th class="num" title="Lisser sur les 12 mois suivant le paiement, dans les rapports (ex : taxe foncière)">Annualisée</th>
            <th></th>
          </tr>
        </thead>

        <tbody>
          <TransactionRow
            v-for="t in pagedTransactions"
            :key="t.id"
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
            @start-edit="startEdit(t)"
            @cancel-edit="cancelEdit"
            @confirm-edit="confirmEdit"
            @toggle-validated="toggleValidated"
            @toggle-periodic="togglePeriodic"
            @toggle-annualized="toggleAnnualized"
            @toggle-select="toggleSelect"
            @remove="remove"
          />
        </tbody>
      </table>
    </div>

    <div v-if="filteredTransactions.length" class="pagination">
      <span class="text-hint">
        {{ filteredTransactions.length }} transaction(s) — page {{ currentPage }} / {{ totalPages }}
      </span>
      <div class="pagination-buttons">
        <button class="btn secondary" :disabled="currentPage <= 1" @click="currentPage--">‹ Précédent</button>
        <button class="btn secondary" :disabled="currentPage >= totalPages" @click="currentPage++">Suivant ›</button>
      </div>
    </div>

    <div v-else class="empty-state">
      {{
        !accounts.length
          ? "Créez d'abord un compte pour ajouter des transactions."
          : searchQuery.trim()
            ? "Aucune transaction ne correspond à cette recherche."
            : 'Aucune transaction pour ce filtre.'
      }}
    </div>

    <!-- Modal : création uniquement -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>Nouvelle transaction</h2>

        <div class="form-grid">
          <div class="form-field">
            <label>Compte</label>
            <select v-model.number="form.accountId">
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>

          <div class="form-field">
            <label>Date</label>
            <input v-model="form.date" type="date" />
          </div>

          <div class="form-field">
            <label>Tiers (payee)</label>
            <PayeeAutocomplete v-model="form.payee" :payees="payees" placeholder="Ex : Supermarché" />
          </div>

          <div class="form-field">
            <label>Montant (négatif = dépense)</label>
            <AmountInput v-model="form.amount" />
          </div>

          <div class="form-field">
            <label>Catégorie</label>
            <CategoryAutocomplete
              v-model="form.categoryId"
              :categories="flatCategories"
              :transaction-type="formAmountType"
              placeholder="Tapez pour rechercher, ou laissez vide"
            />
          </div>

          <div class="form-field">
            <label>Mode de paiement</label>
            <select v-model="form.paymentMode">
              <option value="NONE">Aucun</option>
              <option value="CASH">Espèces</option>
              <option value="CHEQUE">Chèque</option>
              <option value="TRANSFER">Virement</option>
              <option value="CARD">Carte</option>
              <option value="DIRECT_DEBIT">Prélèvement</option>
              <option value="STANDING_ORDER">Virement permanent</option>
            </select>
          </div>

          <div class="form-field" style="grid-column: span 2;">
            <label>Mémo</label>
            <input v-model="form.memo" placeholder="Note libre" />
          </div>

          <div class="form-field" style="grid-column: span 2;">
            <label>Tags (séparés par des espaces)</label>
            <input v-model="form.tags" />
          </div>

          <div class="form-field" style="grid-column: span 2; flex-direction: row; align-items: center; gap: 8px;">
            <input type="checkbox" v-model="form.periodicExpense" id="new-periodic-expense" style="width: auto;" />
            <label for="new-periodic-expense" style="margin: 0;">Dépense périodique récurrente (loyer, abonnement...)</label>
          </div>

          <div class="form-field" style="grid-column: span 2; flex-direction: row; align-items: center; gap: 8px;">
            <input type="checkbox" v-model="form.annualized" id="new-annualized" style="width: auto;" />
            <label for="new-annualized" style="margin: 0;">Lisser sur les 12 mois suivant le paiement, dans les rapports (ex : taxe foncière)</label>
          </div>
        </div>

        <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>

    <!-- Modal : édition en masse des transactions sélectionnées -->
    <div v-if="showBulkEditModal" class="modal-overlay" @click.self="showBulkEditModal = false">
      <div class="modal">
        <h2>Modifier {{ selectedIds.size }} transaction(s)</h2>
        <div class="text-hint" style="margin-bottom: 16px;">
          Cochez uniquement les champs à modifier : les autres resteront inchangés sur chaque transaction.
        </div>

        <div class="bulk-field">
          <label class="bulk-field-toggle">
            <input type="checkbox" v-model="bulkForm.categoryEnabled" />
            Catégorie
          </label>
          <CategoryAutocomplete
            v-model="bulkForm.categoryId"
            :categories="flatCategories"
            placeholder="Aucune"
          />
        </div>

        <div class="bulk-field">
          <label class="bulk-field-toggle">
            <input type="checkbox" v-model="bulkForm.payeeEnabled" />
            Tiers
          </label>
          <PayeeAutocomplete v-model="bulkForm.payee" :payees="payees" placeholder="Ex : Supermarché" />
        </div>

        <div class="bulk-field">
          <label class="bulk-field-toggle">
            <input type="checkbox" v-model="bulkForm.paymentModeEnabled" />
            Mode de paiement
          </label>
          <select v-model="bulkForm.paymentMode">
            <option value="NONE">Aucun</option>
            <option value="CASH">Espèces</option>
            <option value="CHEQUE">Chèque</option>
            <option value="TRANSFER">Virement</option>
            <option value="CARD">Carte</option>
            <option value="DIRECT_DEBIT">Prélèvement</option>
            <option value="STANDING_ORDER">Virement permanent</option>
          </select>
        </div>

        <div class="bulk-field">
          <label class="bulk-field-toggle">
            <input type="checkbox" v-model="bulkForm.periodicExpenseEnabled" />
            Dépense périodique
          </label>
          <select v-model="bulkForm.periodicExpense">
            <option :value="true">Oui</option>
            <option :value="false">Non</option>
          </select>
        </div>

        <div class="bulk-field">
          <label class="bulk-field-toggle">
            <input type="checkbox" v-model="bulkForm.annualizedEnabled" />
            Annualisée
          </label>
          <select v-model="bulkForm.annualized">
            <option :value="true">Oui</option>
            <option :value="false">Non</option>
          </select>
        </div>

        <div class="bulk-field">
          <label class="bulk-field-toggle">
            <input type="checkbox" v-model="bulkForm.validatedEnabled" />
            Statut
          </label>
          <select v-model="bulkForm.validated">
            <option :value="true">Validée</option>
            <option :value="false">En attente</option>
          </select>
        </div>

        <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px;">
          <button class="btn secondary" @click="showBulkEditModal = false">Annuler</button>
          <button class="btn" :disabled="!bulkHasAnyFieldEnabled || bulkSaving" @click="saveBulkEdit">
            {{ bulkSaving ? 'Application…' : 'Appliquer' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  TransactionsAPI
} from '../services/api'
import { dataStore } from '../services/dataStore'
import { normalizeSearch, parseAmountQuery } from '../services/search'
import { flattenCategories } from '../services/categories'
import PayeeAutocomplete from '../components/PayeeAutocomplete.vue'
import CategoryAutocomplete from '../components/CategoryAutocomplete.vue'
import TransactionRow from '../components/TransactionRow.vue'
import AmountInput from '../components/AmountInput.vue'
import TransactionsSubNav from './TransactionsSubNav.vue'
import { formatAmount } from '../services/format'

const route = useRoute()

const filterAccount = ref(null)
const filterMonth = ref(null)
const filterYear = ref(null)
const pendingOnly = ref(false)
const searchQuery = ref('')

const MONTHS = [
  { value: 1, label: 'Janvier' },
  { value: 2, label: 'Février' },
  { value: 3, label: 'Mars' },
  { value: 4, label: 'Avril' },
  { value: 5, label: 'Mai' },
  { value: 6, label: 'Juin' },
  { value: 7, label: 'Juillet' },
  { value: 8, label: 'Août' },
  { value: 9, label: 'Septembre' },
  { value: 10, label: 'Octobre' },
  { value: 11, label: 'Novembre' },
  { value: 12, label: 'Décembre' }
]

// Donnees issues du cache partage (deja precharge au demarrage de l'app,
// voir App.vue / dataStore.js) : s'affichent instantanement sans attendre
// de nouvel appel reseau.
const accounts = computed(() => dataStore.state.accounts)
const categories = computed(() => dataStore.state.categories)
const payees = computed(() => dataStore.state.payees)

// Liste "par defaut" (tous comptes confondus) issue du cache. Quand un
// filtre par compte est actif, on utilise une liste chargee a la demande
// (non mise en cache, car specifique au filtre).
const filteredByAccount = ref(null)
const transactions = computed(() =>
  filterAccount.value !== null ? (filteredByAccount.value ?? []) : dataStore.state.transactions
)

/** Années distinctes présentes dans la liste courante (compte filtré ou non), pour peupler le filtre Année. Recalculé uniquement quand la liste change, pas à chaque frappe. */
const availableYears = computed(() => {
  const years = new Set()
  for (const t of transactions.value) {
    if (t.date) years.add(Number(t.date.slice(0, 4)))
  }
  return Array.from(years).sort((a, b) => b - a)
})

const showModal = ref(false)
const form = ref(emptyForm())

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

function toggleSelect(transaction) {
  if (selectedIds.value.has(transaction.id)) selectedIds.value.delete(transaction.id)
  else selectedIds.value.add(transaction.id)
}

function clearSelection() {
  selectedIds.value.clear()
}

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

/** État du formulaire d'édition en masse : voir modal correspondant. */
function emptyBulkForm() {
  return {
    categoryEnabled: false,
    categoryId: null,
    payeeEnabled: false,
    payee: '',
    paymentModeEnabled: false,
    paymentMode: 'NONE',
    periodicExpenseEnabled: false,
    periodicExpense: false,
    annualizedEnabled: false,
    annualized: false,
    validatedEnabled: false,
    validated: true
  }
}

const showBulkEditModal = ref(false)
const bulkForm = ref(emptyBulkForm())
const bulkSaving = ref(false)

const bulkHasAnyFieldEnabled = computed(() =>
  bulkForm.value.categoryEnabled ||
  bulkForm.value.payeeEnabled ||
  bulkForm.value.paymentModeEnabled ||
  bulkForm.value.periodicExpenseEnabled ||
  bulkForm.value.annualizedEnabled ||
  bulkForm.value.validatedEnabled
)

function openBulkEdit() {
  bulkForm.value = emptyBulkForm()
  showBulkEditModal.value = true
}

async function saveBulkEdit() {
  if (!bulkHasAnyFieldEnabled.value || !selectedIds.value.size) return

  const payload = { ids: Array.from(selectedIds.value) }
  if (bulkForm.value.categoryEnabled) {
    payload.categoryIdProvided = true
    payload.categoryId = bulkForm.value.categoryId
  }
  if (bulkForm.value.payeeEnabled) {
    payload.payeeProvided = true
    payload.payee = bulkForm.value.payee
  }
  if (bulkForm.value.paymentModeEnabled) {
    payload.paymentModeProvided = true
    payload.paymentMode = bulkForm.value.paymentMode
  }
  if (bulkForm.value.periodicExpenseEnabled) {
    payload.periodicExpenseProvided = true
    payload.periodicExpense = bulkForm.value.periodicExpense
  }
  if (bulkForm.value.annualizedEnabled) {
    payload.annualizedProvided = true
    payload.annualized = bulkForm.value.annualized
  }
  if (bulkForm.value.validatedEnabled) {
    payload.validatedProvided = true
    payload.validated = bulkForm.value.validated
  }

  bulkSaving.value = true
  try {
    await TransactionsAPI.bulkUpdate(payload)
    showBulkEditModal.value = false
    clearSelection()
    await load()
  } finally {
    bulkSaving.value = false
  }
}

function emptyForm() {
  return {
    accountId: null,
    date: new Date().toISOString().slice(0, 10),
    payee: '',
    amount: 0,
    categoryId: null,
    paymentMode: 'NONE',
    memo: '',
    tags: '',
    transferTransactionId: null,
    periodicExpense: false,
    annualized: false,
    validated: true
  }
}

/**
 * Filtre les transactions par le texte de recherche : cherche à la fois
 * dans le tiers (payee) et dans le montant (dépense ou revenu), peu importe
 * le format saisi (avec virgule, point, signe, ou juste des chiffres).
 *
 * Cas particulier : si le texte est une expression de comparaison de montant
 * ("> 100", "< 50", "= 42.50"), la recherche bascule sur amountSearchResults,
 * deja filtre cote backend (voir watcher plus bas) plutot que de filtrer en
 * Javascript la liste complete des transactions.
 */
const pendingCount = computed(() =>
  transactions.value.filter(t => !t.validated).length
)

/**
 * Champs de recherche normalises (tiers, montant formate) par transaction,
 * precalcules en Map une seule fois quand la liste de transactions change
 * (chargement, changement de filtre compte...), et non recalcules a chaque
 * frappe dans la recherche : formatAmount + normalizeSearch sur plusieurs
 * milliers de transactions a chaque caractere tape est ce qui avait deja
 * rendu la recherche lente une premiere fois sur ce projet (voir CLAUDE.md).
 * filteredTransactions ci-dessous ne fait plus que des comparaisons de
 * chaines deja normalisees.
 */
const searchIndexById = computed(() => {
  const index = new Map()
  for (const t of transactions.value) {
    index.set(t.id, {
      payeeNormalized: normalizeSearch(t.payee),
      rawAmount: String(t.amount ?? '').toLowerCase(),
      absAmount: String(Math.abs(t.amount ?? 0)),
      amountFormattedNormalized: normalizeSearch(formatAmount(t.amount))
    })
  }
  return index
})

/**
 * Statut + mois/année : etape memoisee separee de filteredTransactions
 * ci-dessous, pour ne pas repasser sur toute la liste a chaque frappe dans
 * la recherche (elle ne depend que du statut/mois/annee/compte, jamais de
 * searchQuery — meme principe que searchIndexById plus haut).
 */
const statusAndDateFiltered = computed(() => {
  const baseTransactions = amountQuery.value
    ? (amountSearchResults.value ?? [])
    : transactions.value

  const statusFiltered = pendingOnly.value
    ? baseTransactions.filter(t => !t.validated)
    : baseTransactions

  if (filterMonth.value == null && filterYear.value == null) return statusFiltered

  return statusFiltered.filter(t => {
    if (!t.date) return false
    if (filterYear.value != null && Number(t.date.slice(0, 4)) !== filterYear.value) return false
    if (filterMonth.value != null && Number(t.date.slice(5, 7)) !== filterMonth.value) return false
    return true
  })
})

const filteredTransactions = computed(() => {
  const q = normalizeSearch(searchQuery.value)
  if (!q || amountQuery.value) return statusAndDateFiltered.value

  const normalizedQuery = q.replace(',', '.')
  const index = searchIndexById.value

  return statusAndDateFiltered.value.filter(t => {
    const idx = index.get(t.id)
    if (!idx) return false

    const payeeMatch = idx.payeeNormalized.includes(q)

    const amountMatch =
      idx.rawAmount.includes(normalizedQuery) ||
      idx.absAmount.includes(normalizedQuery) ||
      idx.amountFormattedNormalized.includes(q)

    return payeeMatch || amountMatch
  })
})

/**
 * Pagination cote client : avec un historique de plusieurs milliers de
 * transactions, rendre TOUTES les lignes filtrees dans le DOM a chaque
 * frappe (un composant TransactionRow par ligne) rend la recherche
 * inutilisable (reconciliation de milliers de composants a chaque
 * caractere). On ne rend donc jamais plus de PAGE_SIZE lignes a la fois,
 * quelle que soit la taille de filteredTransactions.
 */
const PAGE_SIZE = 100
const currentPage = ref(1)

const totalPages = computed(() =>
  Math.max(1, Math.ceil(filteredTransactions.value.length / PAGE_SIZE))
)

const pagedTransactions = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE
  return filteredTransactions.value.slice(start, start + PAGE_SIZE)
})

/**
 * Recherche par montant avec operateur (">", "<", "=") : filtree directement
 * cote backend (voir TransactionController/TransactionService) pour eviter
 * de charger inutilement toutes les transactions cote frontend. Un leger
 * debounce evite un appel reseau a chaque frappe pendant la saisie du nombre.
 */
const amountQuery = computed(() => parseAmountQuery(searchQuery.value))
const amountSearchResults = ref(null)
const amountSearchLoading = ref(false)
let amountSearchTimer = null

// Revenir en page 1 des qu'un filtre change le resultat, pour ne jamais
// rester bloque sur une page devenue vide ou hors bornes.
watch([searchQuery, filterAccount, filterMonth, filterYear, pendingOnly, amountSearchResults], () => {
  currentPage.value = 1
})

watch([amountQuery, filterAccount], ([query, accountId]) => {
  clearTimeout(amountSearchTimer)
  if (!query) {
    amountSearchResults.value = null
    amountSearchLoading.value = false
    return
  }
  amountSearchLoading.value = true
  amountSearchTimer = setTimeout(async () => {
    try {
      amountSearchResults.value = await TransactionsAPI.list({
        accountId: accountId ?? undefined,
        amountOperator: query.operator,
        amountValue: query.value
      })
    } finally {
      amountSearchLoading.value = false
    }
  }, 250)
})

const flatCategories = computed(() => flattenCategories(categories.value))

// Meme logique que TransactionRow.vue : ne proposer dans CategoryAutocomplete
// que des categories du type correspondant au signe du montant saisi.
const formAmountType = computed(() => {
  const amount = form.value.amount
  if (amount == null || amount === 0 || amount === '') return null
  return amount < 0 ? 'EXPENSE' : 'INCOME'
})

// Index Map (id -> objet) pour accounts/categories, precalcules une seule
// fois quand ces listes changent (rarement), et passes en prop a chaque
// TransactionRow : evite un scan lineaire de accounts/categories pour
// chaque ligne affichee (categoryLabel/accountName vivent desormais dans
// TransactionRow.vue et consomment ces Map en O(1)).
const accountsById = computed(() => new Map(accounts.value.map(a => [a.id, a])))
const categoriesById = computed(() => new Map(categories.value.map(c => [c.id, c])))

/**
 * Une transaction est "future" si sa date est strictement postérieure à
 * aujourd'hui : elle est prévue mais n'a pas encore réellement eu lieu
 * (comparaison de chaînes ISO "AAAA-MM-JJ", fiable sans passer par Date()).
 */
const todayIso = new Date().toISOString().slice(0, 10)

async function load() {
  if (filterAccount.value !== null) {
    filteredByAccount.value = await TransactionsAPI.list({ accountId: filterAccount.value })
    // Solde(s) affiché(s) dans la sidebar et sur la page Comptes : à
    // rafraîchir même quand la liste filtrée par compte ne passe pas par le
    // cache partagé ci-dessus.
    await dataStore.refreshAccounts()
  } else {
    // Revalide le cache partage en arriere-plan (l'affichage courant du
    // cache reste visible pendant ce temps, aucun blocage / clignotement).
    // Rafraîchit aussi les soldes des comptes (voir dataStore.js).
    await dataStore.refreshTransactions()
  }
}

/**
 * Categorisation automatique par tiers : un tiers connu (page "Tiers") peut
 * avoir une seule categorie par defaut (Payee#category cote backend). Quand
 * l'utilisateur saisit/selectionne un tiers reconnu dans le formulaire et
 * que le champ Categorie est encore vide, on pre-remplit ce champ avec la
 * categorie par defaut du tiers, SANS jamais ecraser une categorie deja
 * choisie (manuellement ou par un remplissage precedent). C'est un simple
 * confort d'affichage : la source de verite reste appliquee cote backend
 * (voir TransactionService#applyPayload) pour rester fiable meme si ce
 * pre-remplissage frontend echouait ou etait court-circuite.
 */
// Indexe les tiers par nom normalise, memoise (recalcule uniquement quand
// la liste des tiers change, pas a chaque frappe) : evite un scan lineaire
// de tous les tiers a chaque caractere tape dans le champ Tiers.
const payeeByNormalizedName = computed(() => {
  const map = new Map()
  for (const p of payees.value) {
    if (p.name) map.set(p.name.trim().toLowerCase(), p)
  }
  return map
})

function findPayeeByName(name) {
  if (!name || !name.trim()) return null
  return payeeByNormalizedName.value.get(name.trim().toLowerCase()) || null
}

// L'edition inline applique la meme logique localement, voir
// TransactionRow.vue (elle y a besoin de sa propre copie car editForm n'est
// plus accessible depuis ce composant).
function applyDefaultCategoryFromPayee(payeeName) {
  if (form.value.categoryId) return
  const payee = findPayeeByName(payeeName)
  if (payee?.categoryId) form.value.categoryId = payee.categoryId
}

watch(() => form.value.payee, applyDefaultCategoryFromPayee)

function openCreate() {
  form.value = emptyForm()
  if (accounts.value.length) form.value.accountId = accounts.value[0].id
  showModal.value = true
}

async function save() {
  if (!form.value.accountId) {
    alert('Veuillez sélectionner un compte.')
    return
  }

  const payload = {
    accountId: form.value.accountId,
    date: form.value.date,
    payee: form.value.payee,
    amount: form.value.amount,
    categoryId: form.value.categoryId,
    paymentMode: form.value.paymentMode,
    memo: form.value.memo,
    tags: form.value.tags,
    transferTransactionId: form.value.transferTransactionId,
    periodicExpense: form.value.periodicExpense,
    annualized: form.value.annualized,
    validated: form.value.validated
  }

  await TransactionsAPI.create(payload)
  showModal.value = false
  await load()
}

/* --- Édition inline : clic sur une ligne --- */

function startEdit(t) {
  editingId.value = t.id
}

function cancelEdit() {
  editingId.value = null
}

async function confirmEdit(payload) {
  if (!editingId.value) return
  const id = editingId.value
  editingId.value = null

  await TransactionsAPI.update(id, payload)
  await load()
}

async function remove(transaction) {
  if (!confirm('Supprimer cette transaction ?')) return
  await TransactionsAPI.remove(transaction.id)
  await load()
}

/** Bascule directement le statut "dépense périodique" sans passer par l'édition complète. */
async function togglePeriodic(transaction) {
  await TransactionsAPI.togglePeriodicExpense(transaction.id)
  await load()
}

/** Bascule directement le statut "annualisée" sans passer par l'édition complète. */
async function toggleAnnualized(transaction) {
  await TransactionsAPI.toggleAnnualized(transaction.id)
  await load()
}

/* --- Validation / rapprochement (façon "reconcile" YNAB) --- */

const validating = ref(false)

/** Bascule le statut validée/en attente d'une seule transaction. */
async function toggleValidated(transaction) {
  await TransactionsAPI.toggleValidated(transaction.id)
  await load()
}

/**
 * Valide en une fois toutes les transactions en attente : à utiliser une fois
 * que le solde du compte dans l'app correspond au solde réel de la banque,
 * comme le "reconcile" de YNAB. Permet ensuite de repérer d'un coup d'œil
 * les transactions ajoutées depuis la dernière validation (pastille orange).
 */
async function validateAll() {
  if (pendingCount.value === 0) return
  if (!confirm(`Valider les ${pendingCount.value} transaction(s) en attente ?`)) return
  validating.value = true
  try {
    await TransactionsAPI.validateAll()
    await load()
  } finally {
    validating.value = false
  }
}

onMounted(async () => {
  // Si le prechargement lance au demarrage de l'app est deja termine (cas
  // courant), ceci est instantane et n'attend rien. Sinon (premier
  // chargement de l'app directement sur /transactions), on attend juste
  // cette premiere fois.
  await dataStore.ensureLoaded()
  // Revalidation silencieuse en arriere-plan a chaque visite de la page,
  // au cas ou des comptes/categories/tiers/transactions ont change depuis
  // le prechargement (cree ailleurs, autre onglet...).
  dataStore.refreshAll()
  if (filterAccount.value !== null) await load()

  // Arrivee depuis "À traiter" > "Modifier" : ouvre directement la
  // transaction concernee en edition complete, sans action supplementaire.
  const editId = route.query.edit ? Number(route.query.edit) : null
  if (editId) {
    const target = transactions.value.find(t => t.id === editId)
    if (target) startEdit(target)
  }
})
</script>

<style scoped>
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
}
.transactions-table {
  min-width: 1200px;
  width: 100%;
  table-layout: fixed;
}
.col-select { width: 36px; }
.col-date { width: 120px; }
.col-status { width: 50px; }
.col-account { width: 100px; }
.col-payee { width: 280px; }
.col-category { width: auto; }
.col-memo { width: 140px; }
.col-amount { width: 110px; }
.col-monthly { width: 70px; }
.col-actions { width: 90px; }

.transactions-table th {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.transactions-table thead th {
  position: sticky;
  top: 0;
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
  background: var(--ink-800);
  z-index: 7;
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
}

.text-hint {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 4px;
  line-height: 1.4;
}

.bulk-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 10px;
  background: var(--azure-dim);
  border: 1px solid var(--azure);
  border-radius: var(--radius-md);
}

.bulk-field {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.bulk-field > *:last-child {
  flex: 1;
  min-width: 0;
}
.bulk-field-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 170px;
  font-size: 14px;
  color: var(--text-primary);
  cursor: pointer;
}
.bulk-field-toggle input {
  width: auto;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}
.pagination-buttons {
  display: flex;
  gap: 8px;
}
</style>
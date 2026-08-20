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

    <div class="toolbar">
      <div class="form-field">
        <label>Compte</label>
        <select v-model="filterAccount" @change="load">
          <option :value="null">Tous les comptes</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </div>

      <div class="form-field">
        <label>Statut</label>
        <select v-model="filterStatus">
          <option value="all">Toutes</option>
          <option value="pending">À traiter ({{ pendingCount }})</option>
        </select>
      </div>

      <div class="form-field">
        <label>Recherche (tiers, montant, ou &gt; / &lt; / = un montant)</label>
        <input v-model="searchQuery" placeholder="Ex : Carrefour, 42,50 ou > 100" style="min-width: 260px;" />
        <div v-if="amountQuery" class="text-hint">
          {{ amountSearchLoading ? 'Recherche…' : `${filteredTransactions.length} transaction(s) trouvée(s)` }}
        </div>
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
    </div>

    <div class="table-scroll" v-if="filteredTransactions.length">
      <table class="transactions-table">
        <colgroup>
          <col class="col-status" />
          <col class="col-date" />
          <col class="col-account" />
          <col class="col-payee" />
          <col class="col-category" />
          <col class="col-memo" />
          <col class="col-amount" />
          <col class="col-amount" />
          <col class="col-monthly" />
          <col class="col-actions" />
        </colgroup>
        <thead>
          <tr>
            <th title="Statut de validation (rapprochement bancaire)">Statut</th>
            <th>Date</th>
            <th>Compte</th>
            <th>Tiers</th>
            <th>Catégorie</th>
            <th>Mémo</th>
            <th class="num">Dépense</th>
            <th class="num">Revenu</th>
            <th class="num" title="Dépense mensuelle récurrente">Mensuelle</th>
            <th></th>
          </tr>
        </thead>

        <tbody>
          <TransactionRow
            v-for="t in filteredTransactions"
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
            @start-edit="startEdit(t)"
            @cancel-edit="cancelEdit"
            @confirm-edit="confirmEdit"
            @toggle-validated="toggleValidated"
            @toggle-monthly="toggleMonthly"
            @remove="remove"
          />
        </tbody>
      </table>
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
            <input v-model.number="form.amount" type="number" step="0.01" />
          </div>

          <div class="form-field">
            <label>Catégorie</label>
            <CategoryAutocomplete v-model="form.categoryId" :categories="flatCategories" placeholder="Tapez pour rechercher, ou laissez vide" />
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
            <input type="checkbox" v-model="form.monthlyExpense" id="new-monthly-expense" style="width: auto;" />
            <label for="new-monthly-expense" style="margin: 0;">Dépense mensuelle récurrente (loyer, abonnement...)</label>
          </div>
        </div>

        <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
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
import PayeeAutocomplete from '../components/PayeeAutocomplete.vue'
import CategoryAutocomplete from '../components/CategoryAutocomplete.vue'
import TransactionRow from '../components/TransactionRow.vue'
import { formatAmount } from '../services/format'

const route = useRoute()

const filterAccount = ref(null)
const filterStatus = ref('all')
const searchQuery = ref('')

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

const showModal = ref(false)
const form = ref(emptyForm())

// --- Édition inline ---
// L'etat du formulaire d'edition (editForm) vit desormais localement dans
// TransactionRow.vue : seul l'id de la ligne en cours d'edition reste ici,
// pour garantir qu'une seule ligne est editee a la fois. Voir le commentaire
// en tete de TransactionRow.vue pour le detail de l'optimisation.
const editingId = ref(null)

function emptyForm() {
  return {
    accountId: null,
    date: new Date().toISOString().slice(0, 10),
    payee: '',
    amount: 0,
    categoryId: null,
    paymentMode: 'NONE',
    status: 'NONE',
    memo: '',
    tags: '',
    transferTransactionId: null,
    monthlyExpense: false,
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

const filteredTransactions = computed(() => {
  const baseTransactions = amountQuery.value
    ? (amountSearchResults.value ?? [])
    : transactions.value

  const statusFiltered = filterStatus.value === 'pending'
    ? baseTransactions.filter(t => !t.validated)
    : baseTransactions

  const q = normalizeSearch(searchQuery.value)
  if (!q || amountQuery.value) return statusFiltered

  const normalizedQuery = q.replace(',', '.')

  return statusFiltered.filter(t => {
    const payeeMatch = normalizeSearch(t.payee).includes(q)

    const rawAmount = String(t.amount ?? '').toLowerCase()
    const absAmount = String(Math.abs(t.amount ?? 0))
    const formatted = normalizeSearch(formatAmount(t.amount))

    const amountMatch =
      rawAmount.includes(normalizedQuery) ||
      absAmount.includes(normalizedQuery) ||
      formatted.includes(q)

    return payeeMatch || amountMatch
  })
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

const flatCategories = computed(() => {
  const roots = categories.value.filter(c => !c.parentId)
  const result = []
  for (const root of roots) {
    result.push({ id: root.id, label: root.name, type: root.type })
    const children = categories.value.filter(c => c.parentId === root.id)
    for (const child of children) {
      result.push({ id: child.id, label: `${root.name} : ${child.name}`, type: child.type })
    }
  }
  return result
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
  } else {
    // Revalide le cache partage en arriere-plan (l'affichage courant du
    // cache reste visible pendant ce temps, aucun blocage / clignotement).
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
    status: form.value.status,
    memo: form.value.memo,
    tags: form.value.tags,
    transferTransactionId: form.value.transferTransactionId,
    monthlyExpense: form.value.monthlyExpense,
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

/** Bascule directement le statut "dépense mensuelle" sans passer par l'édition complète. */
async function toggleMonthly(transaction) {
  await TransactionsAPI.toggleMonthlyExpense(transaction.id)
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
.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.transactions-table {
  min-width: 920px;
  width: 100%;
  table-layout: fixed;
}
.col-date { width: 145px; }
.col-status { width: 60px; }
.col-account { width: 130px; }
.col-payee { width: auto; }
.col-category { width: 190px; }
.col-memo { width: 170px; }
.col-amount { width: 120px; }
.col-monthly { width: 90px; }
.col-actions { width: 110px; }

.transactions-table th {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/*
 * La colonne Actions reste toujours visible même si le tableau déborde
 * horizontalement (beaucoup de colonnes) : on la fixe à droite plutôt que
 * de forcer l'utilisateur à scroller jusqu'au bout pour supprimer/valider
 * une ligne. Le style des cellules (td) correspondantes vit desormais dans
 * TransactionRow.vue, qui gere sa propre ligne.
 */
.transactions-table th:last-child {
  position: sticky;
  right: 0;
  background: var(--ink-800);
  z-index: 2;
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
}

.text-hint {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 4px;
  line-height: 1.4;
}
</style>
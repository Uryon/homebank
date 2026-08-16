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
        <label>Recherche (tiers, dépense, revenu)</label>
        <input v-model="searchQuery" placeholder="Ex : Carrefour ou 42,50" style="min-width: 240px;" />
      </div>
    </div>

    <div class="table-scroll" v-if="filteredTransactions.length">
      <table class="transactions-table">
        <colgroup>
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
          <tr
            v-for="t in filteredTransactions"
            :key="t.id"
            :class="{ 'row-editing': editingId === t.id }"
            @click="editingId !== t.id && startEdit(t)"
            @keydown.esc="cancelEdit"
            @keydown.enter.prevent="editingId === t.id && confirmEdit()"
          >
            <!-- Mode édition -->
            <template v-if="editingId === t.id">
              <td @click.stop>
                <input v-model="editForm.date" type="date" ref="firstEditField" />
              </td>
              <td @click.stop>
                <select v-model.number="editForm.accountId">
                  <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
                </select>
              </td>
              <td @click.stop>
                <input v-model="editForm.payee" list="payee-options" autocomplete="off" />
              </td>
              <td @click.stop>
                <input v-model="editForm.categoryName" list="category-options" autocomplete="off" />
              </td>
              <td @click.stop>
                <input v-model="editForm.memo" placeholder="Note libre" />
              </td>
              <td class="num" colspan="2" @click.stop>
                <input v-model.number="editForm.amount" type="number" step="0.01" style="text-align: right;" />
              </td>
              <td class="num" @click.stop>
                <input type="checkbox" v-model="editForm.monthlyExpense" title="Dépense mensuelle récurrente" />
              </td>
              <td @click.stop class="row-actions">
                <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
              </td>
            </template>

            <!-- Mode lecture -->
            <template v-else>
              <td class="col-date">{{ formatDate(t.date) }}</td>
              <td>{{ accountName(t.accountId) }}</td>
              <td class="col-payee" :title="t.payee">{{ t.payee }}</td>
              <td>{{ categoryLabel(t.categoryId) }}</td>
              <td class="text-muted">{{ displayMemo(t.memo) }}</td>
              <td class="num amount negative">{{ t.amount < 0 ? formatAmount(t.amount) : '' }}</td>
              <td class="num amount positive">{{ t.amount > 0 ? formatAmount(t.amount) : '' }}</td>
              <td class="num" @click.stop>
                <input
                  type="checkbox"
                  :checked="t.monthlyExpense"
                  title="Dépense mensuelle récurrente"
                  @change="toggleMonthly(t)"
                />
              </td>
              <td class="row-actions" @click.stop>
                <button class="btn danger" @click="remove(t)">Suppr.</button>
              </td>
            </template>
          </tr>
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

    <!-- datalists partagées (édition inline + modal création) -->
    <datalist id="payee-options">
      <option v-for="p in payees" :key="p.id" :value="p.name" />
    </datalist>
    <datalist id="category-options">
      <option v-for="c in flatCategories" :key="c.id" :value="c.label" />
    </datalist>

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
            <input v-model="form.payee" list="payee-options" autocomplete="off" placeholder="Ex : Supermarché" />
          </div>

          <div class="form-field">
            <label>Montant (négatif = dépense)</label>
            <input v-model.number="form.amount" type="number" step="0.01" />
          </div>

          <div class="form-field">
            <label>Catégorie</label>
            <input v-model="form.categoryName" list="category-options" autocomplete="off" placeholder="Tapez pour rechercher, ou laissez vide" />
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
import { ref, onMounted, computed, nextTick } from 'vue'
import {
  TransactionsAPI
} from '../services/api'
import { dataStore } from '../services/dataStore'
import { normalizeSearch } from '../services/search'

const filterAccount = ref(null)
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
const editingId = ref(null)
const editForm = ref(null)
const firstEditField = ref(null)

function emptyForm() {
  return {
    accountId: null,
    date: new Date().toISOString().slice(0, 10),
    payee: '',
    amount: 0,
    categoryName: '',
    paymentMode: 'NONE',
    status: 'NONE',
    memo: '',
    tags: '',
    transferTransactionId: null,
    monthlyExpense: false
  }
}

/**
 * Filtre les transactions par le texte de recherche : cherche à la fois
 * dans le tiers (payee) et dans le montant (dépense ou revenu), peu importe
 * le format saisi (avec virgule, point, signe, ou juste des chiffres).
 */
const filteredTransactions = computed(() => {
  const q = normalizeSearch(searchQuery.value)
  if (!q) return transactions.value

  const normalizedQuery = q.replace(',', '.')

  return transactions.value.filter(t => {
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

const flatCategories = computed(() => {
  const roots = categories.value.filter(c => !c.parentId)
  const result = []
  for (const root of roots) {
    result.push({ id: root.id, label: root.name })
    const children = categories.value.filter(c => c.parentId === root.id)
    for (const child of children) {
      result.push({ id: child.id, label: `${root.name} : ${child.name}` })
    }
  }
  return result
})

function accountName(id) {
  return accounts.value.find(a => a.id === id)?.name || '—'
}

function categoryLabel(categoryId) {
  if (!categoryId) return '—'
  const category = categories.value.find(c => c.id === categoryId)
  if (!category) return '—'
  if (category.parentId) {
    const parent = categories.value.find(c => c.id === category.parentId)
    if (parent) return `${parent.name} : ${category.name}`
  }
  return category.name
}

/**
 * N'affiche rien si le mémo est vide, null, ou contient littéralement
 * "(NULL)" (certains exports CSV/QIF écrivent cette valeur telle quelle
 * pour un champ vide).
 */
function displayMemo(memo) {
  if (!memo) return ''
  if (memo.trim().toUpperCase() === '(NULL)') return ''
  return memo
}

function formatAmount(value) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(value || 0)
}

function formatDate(iso) {
  if (!iso) return ''
  const [y, m, d] = iso.split('-')
  return `${d}/${m}/${y}`
}

async function load() {
  if (filterAccount.value !== null) {
    filteredByAccount.value = await TransactionsAPI.list({ accountId: filterAccount.value })
  } else {
    // Revalide le cache partage en arriere-plan (l'affichage courant du
    // cache reste visible pendant ce temps, aucun blocage / clignotement).
    await dataStore.refreshTransactions()
  }
}

function resolveCategoryId(typedLabel) {
  if (!typedLabel || !typedLabel.trim()) return null
  const trimmed = typedLabel.trim().toLowerCase()
  const match = flatCategories.value.find(c => c.label.toLowerCase() === trimmed)
  return match ? match.id : null
}

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
    categoryId: resolveCategoryId(form.value.categoryName),
    paymentMode: form.value.paymentMode,
    status: form.value.status,
    memo: form.value.memo,
    tags: form.value.tags,
    transferTransactionId: form.value.transferTransactionId,
    monthlyExpense: form.value.monthlyExpense
  }

  await TransactionsAPI.create(payload)
  showModal.value = false
  await load()
}

/* --- Édition inline : clic sur une ligne --- */

function startEdit(t) {
  const label = categoryLabel(t.categoryId)
  editingId.value = t.id
  editForm.value = {
    accountId: t.accountId,
    date: t.date,
    payee: t.payee || '',
    amount: t.amount,
    categoryName: label === '—' ? '' : label,
    paymentMode: t.paymentMode || 'NONE',
    status: t.status || 'NONE',
    memo: displayMemo(t.memo),
    tags: t.tags || '',
    transferTransactionId: t.transferTransactionId ?? null,
    monthlyExpense: !!t.monthlyExpense
  }
  nextTick(() => {
    firstEditField.value?.[0]?.focus?.()
  })
}

function cancelEdit() {
  editingId.value = null
  editForm.value = null
}

async function confirmEdit() {
  if (!editingId.value || !editForm.value) return
  if (!editForm.value.accountId) {
    alert('Veuillez sélectionner un compte.')
    return
  }

  const payload = {
    accountId: editForm.value.accountId,
    date: editForm.value.date,
    payee: editForm.value.payee,
    amount: editForm.value.amount,
    categoryId: resolveCategoryId(editForm.value.categoryName),
    paymentMode: editForm.value.paymentMode,
    status: editForm.value.status,
    memo: editForm.value.memo,
    tags: editForm.value.tags,
    transferTransactionId: editForm.value.transferTransactionId,
    monthlyExpense: editForm.value.monthlyExpense
  }

  const id = editingId.value
  editingId.value = null
  editForm.value = null

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
.col-account { width: 130px; }
.col-payee { width: auto; }
.col-category { width: 190px; }
.col-memo { width: 170px; }
.col-amount { width: 120px; }
.col-monthly { width: 90px; }
.col-actions { width: 110px; }

.transactions-table td, .transactions-table th {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.transactions-table .col-actions,
.transactions-table td:last-child {
  max-width: none;
  overflow: visible;
}
.transactions-table .col-memo,
.transactions-table td:nth-child(5) {
  white-space: normal;
}

.transactions-table tbody tr {
  cursor: pointer;
}
.transactions-table tbody tr.row-editing {
  cursor: default;
  background: var(--ink-800);
  box-shadow: inset 3px 0 0 var(--azure);
}
.transactions-table tbody tr.row-editing td {
  overflow: visible;
}
.transactions-table tbody tr.row-editing input,
.transactions-table tbody tr.row-editing select {
  width: 100%;
}

.row-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

/*
 * La colonne Actions reste toujours visible même si le tableau déborde
 * horizontalement (beaucoup de colonnes) : on la fixe à droite plutôt que
 * de forcer l'utilisateur à scroller jusqu'au bout pour supprimer/valider
 * une ligne.
 */
.transactions-table th:last-child,
.transactions-table td:last-child {
  position: sticky;
  right: 0;
  background: var(--ink-900);
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
}
.transactions-table thead th:last-child {
  background: var(--ink-800);
  z-index: 2;
}
.transactions-table tbody tr:hover td:last-child {
  background: var(--ink-800);
}
.transactions-table tbody tr.row-editing td:last-child {
  background: var(--ink-800);
}

.text-muted {
  color: var(--text-muted);
}
</style>
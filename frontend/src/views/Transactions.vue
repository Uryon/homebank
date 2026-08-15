<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Historique des opérations</div>
      </div>

      <button
        class="btn"
        @click="openCreate"
        :disabled="!accounts.length"
      >
        + Nouvelle transaction
      </button>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label>Compte</label>
        <select v-model="filterAccount" @change="load">
          <option :value="null">Tous les comptes</option>

          <option
            v-for="a in accounts"
            :key="a.id"
            :value="a.id"
          >
            {{ a.name }}
          </option>
        </select>
      </div>

      <div class="form-field">
        <label>Recherche (tiers, dépense, revenu)</label>
        <input
          v-model="searchQuery"
          placeholder="Ex : Carrefour ou 42,50"
          style="min-width: 240px;"
        />
      </div>
    </div>

    <table v-if="filteredTransactions.length">
      <thead>
        <tr>
          <th>Date</th>
          <th>Compte</th>
          <th>Tiers</th>
          <th>Catégorie</th>
          <th>Mémo</th>
          <th class="num">Dépense</th>
          <th class="num">Revenu</th>
          <th></th>
        </tr>
      </thead>

      <tbody>
        <tr
          v-for="t in filteredTransactions"
          :key="t.id"
        >
          <td>{{ t.date }}</td>

          <td>
            {{ accountName(t.accountId) }}
          </td>

          <td>
            {{ t.payee }}
          </td>

          <td>
            {{ categoryLabel(t.categoryId) }}
          </td>

          <td>
            {{ t.memo || '' }}
          </td>

          <!-- Dépense -->
          <td class="num amount negative">
            {{ t.amount < 0 ? formatAmount(t.amount) : '' }}
          </td>

          <!-- Revenu -->
          <td class="num amount positive">
            {{ t.amount > 0 ? formatAmount(t.amount) : '' }}
          </td>

          <td>
            <button
              class="btn secondary"
              style="margin-right: 6px;"
              @click="openEdit(t)"
            >
              Modifier
            </button>

            <button
              class="btn danger"
              @click="remove(t)"
            >
              Suppr.
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <div
      v-else
      class="empty-state"
    >
      {{
        !accounts.length
          ? "Créez d'abord un compte pour ajouter des transactions."
          : searchQuery.trim()
            ? "Aucune transaction ne correspond à cette recherche."
            : 'Aucune transaction pour ce filtre.'
      }}
    </div>

    <!-- Modal -->
    <div
      v-if="showModal"
      class="modal-overlay"
      @click.self="showModal = false"
    >
      <div class="modal">
        <h2>
          {{
            editing
              ? 'Modifier la transaction'
              : 'Nouvelle transaction'
          }}
        </h2>

        <div class="form-grid">

          <!-- Compte -->
          <div class="form-field">
            <label>Compte</label>

            <select v-model.number="form.accountId">
              <option
                v-for="a in accounts"
                :key="a.id"
                :value="a.id"
              >
                {{ a.name }}
              </option>
            </select>
          </div>

          <!-- Date -->
          <div class="form-field">
            <label>Date</label>

            <input
              v-model="form.date"
              type="date"
            />
          </div>

          <!-- Payee -->
          <div class="form-field">
            <label>Tiers (payee)</label>

            <input
              v-model="form.payee"
              list="payee-options"
              autocomplete="off"
              placeholder="Ex : Supermarché"
            />
            <datalist id="payee-options">
              <option
                v-for="p in payees"
                :key="p.id"
                :value="p.name"
              />
            </datalist>
          </div>

          <!-- Montant -->
          <div class="form-field">
            <label>Montant (négatif = dépense)</label>

            <input
              v-model.number="form.amount"
              type="number"
              step="0.01"
            />
          </div>

          <!-- Catégorie -->
          <div class="form-field">
            <label>Catégorie</label>

            <input
              v-model="form.categoryName"
              list="category-options"
              autocomplete="off"
              placeholder="Tapez pour rechercher, ou laissez vide"
            />
            <datalist id="category-options">
              <option
                v-for="c in flatCategories"
                :key="c.id"
                :value="c.label"
              />
            </datalist>
          </div>

          <!-- Mode de paiement -->
          <div class="form-field">
            <label>Mode de paiement</label>

            <select v-model="form.paymentMode">
              <option value="NONE">Aucun</option>
              <option value="CASH">Espèces</option>
              <option value="CHEQUE">Chèque</option>
              <option value="TRANSFER">Virement</option>
              <option value="CARD">Carte</option>
              <option value="DIRECT_DEBIT">Prélèvement</option>
              <option value="STANDING_ORDER">
                Virement permanent
              </option>
            </select>
          </div>

          <!-- Mémo -->
          <div
            class="form-field"
            style="grid-column: span 2;"
          >
            <label>Mémo</label>

            <input
              v-model="form.memo"
              placeholder="Note libre"
            />
          </div>

          <!-- Tags -->
          <div
            class="form-field"
            style="grid-column: span 2;"
          >
            <label>
              Tags (séparés par des espaces)
            </label>

            <input v-model="form.tags" />
          </div>
        </div>

        <!-- Actions -->
        <div
          style="
            display: flex;
            justify-content: flex-end;
            gap: 10px;
            margin-top: 20px;
          "
        >
          <button
            class="btn secondary"
            @click="showModal = false"
          >
            Annuler
          </button>

          <button
            class="btn"
            @click="save"
          >
            Enregistrer
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import {
  AccountsAPI,
  CategoriesAPI,
  PayeesAPI,
  TransactionsAPI
} from '../services/api'

const accounts = ref([])
const categories = ref([])
const payees = ref([])
const transactions = ref([])

const filterAccount = ref(null)
const searchQuery = ref('')

const showModal = ref(false)
const editing = ref(null)

const form = ref(emptyForm())

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
    transferTransactionId: null
  }
}

/**
 * Filtre les transactions par le texte de recherche : cherche à la fois
 * dans le tiers (payee) et dans le montant (dépense ou revenu), peu importe
 * le format saisi (avec virgule, point, signe, ou juste des chiffres).
 */
const filteredTransactions = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return transactions.value

  const normalizedQuery = q.replace(',', '.')

  return transactions.value.filter(t => {
    const payeeMatch = (t.payee || '').toLowerCase().includes(q)

    const rawAmount = String(t.amount ?? '').toLowerCase()
    const absAmount = String(Math.abs(t.amount ?? 0))
    const formatted = formatAmount(t.amount).toLowerCase()

    const amountMatch =
      rawAmount.includes(normalizedQuery) ||
      absAmount.includes(normalizedQuery) ||
      formatted.includes(q)

    return payeeMatch || amountMatch
  })
})

/**
 * Transforme les catégories en liste plate
 * pour le select.
 */
const flatCategories = computed(() => {
  const roots = categories.value.filter(
    c => !c.parentId
  )

  const result = []

  for (const root of roots) {
    result.push({
      id: root.id,
      label: root.name
    })

    const children = categories.value.filter(
      c => c.parentId === root.id
    )

    for (const child of children) {
      result.push({
        id: child.id,
        label: `${root.name} : ${child.name}`
      })
    }
  }

  return result
})

/**
 * Retourne le nom d'un compte à partir de son ID.
 */
function accountName(id) {
  return (
    accounts.value.find(a => a.id === id)?.name || '—'
  )
}

/**
 * Retourne le libellé d'une catégorie à partir de son ID.
 */
function categoryLabel(categoryId) {
  if (!categoryId) {
    return '—'
  }

  const category = categories.value.find(
    c => c.id === categoryId
  )

  if (!category) {
    return '—'
  }

  if (category.parentId) {
    const parent = categories.value.find(
      c => c.id === category.parentId
    )

    if (parent) {
      return `${parent.name} : ${category.name}`
    }
  }

  return category.name
}

/**
 * Formatage des montants en euros.
 */
function formatAmount(value) {
  return new Intl.NumberFormat('fr-FR', {
    style: 'currency',
    currency: 'EUR'
  }).format(value || 0)
}

/**
 * Chargement des transactions.
 */
async function load() {
  const params = {}

  if (filterAccount.value) {
    params.accountId = filterAccount.value
  }

  transactions.value = await TransactionsAPI.list(params)
}

/**
 * Chargement des données de référence.
 */
async function loadRefs() {
  accounts.value = await AccountsAPI.list()
  categories.value = await CategoriesAPI.list()
  payees.value = await PayeesAPI.list()
}

/**
 * Résout un libellé de catégorie tapé/sélectionné (ex : "Alimentation : Supermarché")
 * vers l'ID de la catégorie existante correspondante. Renvoie null si vide
 * ou si aucune catégorie existante ne correspond exactement (on ne crée pas
 * de nouvelle catégorie depuis ce champ).
 */
function resolveCategoryId(typedLabel) {
  if (!typedLabel || !typedLabel.trim()) return null
  const trimmed = typedLabel.trim().toLowerCase()
  const match = flatCategories.value.find(c => c.label.toLowerCase() === trimmed)
  return match ? match.id : null
}

/**
 * Ouvre le formulaire de création.
 */
function openCreate() {
  editing.value = null
  form.value = emptyForm()

  if (accounts.value.length) {
    form.value.accountId = accounts.value[0].id
  }

  showModal.value = true
}

/**
 * Ouvre le formulaire de modification.
 */
function openEdit(transaction) {
  editing.value = transaction

  const label = categoryLabel(transaction.categoryId)

  form.value = {
    accountId: transaction.accountId,
    date: transaction.date,
    payee: transaction.payee || '',
    amount: transaction.amount,
    categoryName: label === '—' ? '' : label,
    paymentMode: transaction.paymentMode || 'NONE',
    status: transaction.status || 'NONE',
    memo: transaction.memo || '',
    tags: transaction.tags || '',
    transferTransactionId:
      transaction.transferTransactionId ?? null
  }

  showModal.value = true
}

/**
 * Création / modification d'une transaction.
 */
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
    transferTransactionId:
      form.value.transferTransactionId
  }

  if (editing.value) {
    await TransactionsAPI.update(
      editing.value.id,
      payload
    )
  } else {
    await TransactionsAPI.create(payload)
  }

  showModal.value = false

  await load()
}

/**
 * Suppression d'une transaction.
 */
async function remove(transaction) {
  if (!confirm('Supprimer cette transaction ?')) {
    return
  }

  await TransactionsAPI.remove(transaction.id)

  await load()
}

onMounted(async () => {
  await loadRefs()
  await load()
})
</script>
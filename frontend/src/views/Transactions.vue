<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Historique des opérations</div>
      </div>
      <button class="btn" @click="openCreate" :disabled="!accounts.length">+ Nouvelle transaction</button>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label>Compte</label>
        <select v-model="filterAccount" @change="load">
          <option :value="null">Tous les comptes</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </div>
    </div>

    <table v-if="transactions.length">
      <thead>
        <tr>
          <th>Date</th>
          <th>Compte</th>
          <th>Tiers</th>
          <th>Catégorie</th>
          <th>Mémo</th>
          <th class="num">Montant</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in transactions" :key="t.id">
          <td>{{ t.date }}</td>
          <td>{{ accountName(t.account?.id) }}</td>
          <td>{{ t.payee }}</td>
          <td>{{ categoryLabel(t.category) }}</td>
          <td>{{ t.memo }}</td>
          <td class="num amount" :class="t.amount >= 0 ? 'positive' : 'negative'">{{ formatAmount(t.amount) }}</td>
          <td>
            <button class="btn secondary" style="margin-right: 6px;" @click="openEdit(t)">Modifier</button>
            <button class="btn danger" @click="remove(t)">Suppr.</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty-state">
      {{ accounts.length ? 'Aucune transaction pour ce filtre.' : "Créez d'abord un compte pour ajouter des transactions." }}
    </div>

    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? 'Modifier la transaction' : 'Nouvelle transaction' }}</h2>
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
            <input v-model="form.payee" placeholder="Ex : Supermarché" />
          </div>
          <div class="form-field">
            <label>Montant (négatif = dépense)</label>
            <input v-model.number="form.amount" type="number" step="0.01" />
          </div>
          <div class="form-field">
            <label>Catégorie</label>
            <select v-model.number="form.categoryId">
              <option :value="null">Non catégorisé</option>
              <option v-for="c in flatCategories" :key="c.id" :value="c.id">{{ c.label }}</option>
            </select>
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
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { AccountsAPI, CategoriesAPI, TransactionsAPI } from '../services/api'

const accounts = ref([])
const categories = ref([])
const transactions = ref([])
const filterAccount = ref(null)
const showModal = ref(false)
const editing = ref(null)
const form = ref(emptyForm())

function emptyForm() {
  return {
    accountId: null,
    date: new Date().toISOString().slice(0, 10),
    payee: '',
    amount: 0,
    categoryId: null,
    paymentMode: 'NONE',
    memo: '',
    tags: ''
  }
}

const flatCategories = computed(() => {
  const roots = categories.value.filter(c => !c.parent)
  const result = []
  for (const root of roots) {
    result.push({ id: root.id, label: root.name })
    const children = categories.value.filter(c => c.parent?.id === root.id)
    for (const child of children) {
      result.push({ id: child.id, label: `${root.name} : ${child.name}` })
    }
  }
  return result
})

function accountName(id) {
  return accounts.value.find(a => a.id === id)?.name || '—'
}

function categoryLabel(category) {
  if (!category) return '—'
  return category.parent ? `${category.parent.name} : ${category.name}` : category.name
}

function formatAmount(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v || 0)
}

async function load() {
  const params = {}
  if (filterAccount.value) params.accountId = filterAccount.value
  transactions.value = await TransactionsAPI.list(params)
}

async function loadRefs() {
  accounts.value = await AccountsAPI.list()
  categories.value = await CategoriesAPI.list()
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  if (accounts.value.length) form.value.accountId = accounts.value[0].id
  showModal.value = true
}

function openEdit(t) {
  editing.value = t
  form.value = {
    accountId: t.account?.id,
    date: t.date,
    payee: t.payee,
    amount: t.amount,
    categoryId: t.category?.id ?? null,
    paymentMode: t.paymentMode,
    memo: t.memo,
    tags: t.tags
  }
  showModal.value = true
}

async function save() {
  if (!form.value.accountId) {
    alert('Veuillez sélectionner un compte.')
    return
  }
  const payload = {
    account: { id: form.value.accountId },
    date: form.value.date,
    payee: form.value.payee,
    amount: form.value.amount,
    category: form.value.categoryId ? { id: form.value.categoryId } : null,
    paymentMode: form.value.paymentMode,
    memo: form.value.memo,
    tags: form.value.tags,
    status: 'NONE'
  }
  if (editing.value) {
    await TransactionsAPI.update(editing.value.id, payload)
  } else {
    await TransactionsAPI.create(payload)
  }
  showModal.value = false
  await load()
}

async function remove(t) {
  if (!confirm('Supprimer cette transaction ?')) return
  await TransactionsAPI.remove(t.id)
  await load()
}

onMounted(async () => {
  await loadRefs()
  await load()
})
</script>

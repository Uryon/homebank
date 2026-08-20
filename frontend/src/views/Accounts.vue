<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Comptes</h1>
        <div class="subtitle">Gérez vos comptes bancaires</div>
      </div>
      <div style="display:flex; gap:10px;">
        <button class="btn secondary" @click="openTransfer" :disabled="accounts.length < 2">
          Transfert entre comptes
        </button>
        <button class="btn" @click="openCreate">+ Nouveau compte</button>
      </div>
    </div>

    <table v-if="accounts.length">
      <thead>
        <tr>
          <th>Nom</th>
          <th>Type</th>
          <th class="num">Solde initial</th>
          <th class="num">Solde courant</th>
          <th>Devise</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="a in accounts" :key="a.id">
          <td>{{ a.name }}</td>
          <td><span class="badge" :class="a.type.toLowerCase()">{{ typeLabel(a.type) }}</span></td>
          <td class="num amount">{{ formatAmount(a.initialBalance) }}</td>
          <td class="num amount" :class="(balances[a.id] ?? 0) >= 0 ? 'positive' : 'negative'">
            {{ formatAmount(balances[a.id] ?? 0) }}
          </td>
          <td>{{ a.currency }}</td>
          <td>
            <button class="btn secondary" style="margin-right: 6px;" @click="openEdit(a)">Modifier</button>
            <button class="btn danger" @click="remove(a)">Supprimer</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty-state">Aucun compte. Créez-en un pour commencer à suivre vos transactions.</div>

    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? 'Modifier le compte' : 'Nouveau compte' }}</h2>
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2;">
            <label>Nom</label>
            <input v-model="form.name" placeholder="Ex : Compte courant" />
          </div>
          <div class="form-field">
            <label>Type</label>
            <select v-model="form.type">
              <option value="CHECKING">Compte courant</option>
              <option value="SAVINGS">Épargne</option>
              <option value="CASH">Espèces</option>
              <option value="CREDIT_CARD">Carte de crédit</option>
              <option value="INVESTMENT">Placement</option>
            </select>
          </div>
          <div class="form-field">
            <label>Devise</label>
            <input v-model="form.currency" placeholder="EUR" maxlength="3" />
          </div>
          <div class="form-field">
            <label>Solde initial</label>
            <input v-model.number="form.initialBalance" type="number" step="0.01" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Notes</label>
            <textarea v-model="form.notes" rows="2"></textarea>
          </div>
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>

    <!-- Modal : virement entre comptes -->
    <div v-if="showTransferModal" class="modal-overlay" @click.self="showTransferModal = false">
      <div class="modal">
        <h2>Transfert entre comptes</h2>
        <p style="color: var(--text-muted); font-size: 14px; margin-bottom: 16px;">
          Débite le compte source et crédite le compte destination du même montant.
          Ce mouvement n'est jamais compté comme une dépense ou un revenu dans les rapports.
        </p>
        <div class="form-grid">
          <div class="form-field">
            <label>Compte source</label>
            <select v-model.number="transferForm.fromAccountId">
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
          <div class="form-field">
            <label>Compte destination</label>
            <select v-model.number="transferForm.toAccountId">
              <option
                v-for="a in accounts"
                :key="a.id"
                :value="a.id"
                :disabled="a.id === transferForm.fromAccountId"
              >
                {{ a.name }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label>Montant</label>
            <input v-model.number="transferForm.amount" type="number" step="0.01" min="0" />
          </div>
          <div class="form-field">
            <label>Date</label>
            <input v-model="transferForm.date" type="date" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Mémo (facultatif)</label>
            <input v-model="transferForm.memo" placeholder="Ex : Épargne du mois" />
          </div>
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showTransferModal = false">Annuler</button>
          <button class="btn" :disabled="transferring" @click="submitTransfer">
            {{ transferring ? 'Transfert en cours…' : 'Confirmer le transfert' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { AccountsAPI, TransactionsAPI } from '../services/api'
import { formatAmount } from '../services/format'

const accounts = ref([])
const balances = ref({})
const showModal = ref(false)
const editing = ref(null)
const form = ref(emptyForm())

const showTransferModal = ref(false)
const transferring = ref(false)
const transferForm = ref(emptyTransferForm())

function emptyForm() {
  return { name: '', type: 'CHECKING', currency: 'EUR', initialBalance: 0, notes: '' }
}

function emptyTransferForm() {
  return {
    fromAccountId: null,
    toAccountId: null,
    amount: 0,
    date: new Date().toISOString().slice(0, 10),
    memo: ''
  }
}

function typeLabel(type) {
  const labels = { CHECKING: 'Courant', SAVINGS: 'Épargne', CASH: 'Espèces', CREDIT_CARD: 'Carte', INVESTMENT: 'Placement' }
  return labels[type] || type
}

async function load() {
  accounts.value = await AccountsAPI.list()
  balances.value = await AccountsAPI.balances()
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(account) {
  editing.value = account
  form.value = { ...account }
  showModal.value = true
}

async function save() {
  if (!form.value.name.trim()) {
    alert('Le nom du compte est obligatoire.')
    return
  }
  if (editing.value) {
    await AccountsAPI.update(editing.value.id, form.value)
  } else {
    await AccountsAPI.create(form.value)
  }
  showModal.value = false
  await load()
}

async function remove(account) {
  if (!confirm(`Supprimer le compte "${account.name}" et toutes ses transactions ?`)) return
  await AccountsAPI.remove(account.id)
  await load()
}

/* --- Virement entre comptes --- */

function openTransfer() {
  transferForm.value = emptyTransferForm()
  if (accounts.value.length >= 2) {
    transferForm.value.fromAccountId = accounts.value[0].id
    transferForm.value.toAccountId = accounts.value[1].id
  }
  showTransferModal.value = true
}

async function submitTransfer() {
  const f = transferForm.value
  if (!f.fromAccountId || !f.toAccountId) {
    alert('Veuillez sélectionner les deux comptes.')
    return
  }
  if (f.fromAccountId === f.toAccountId) {
    alert('Le compte source et le compte destination doivent être différents.')
    return
  }
  if (!f.amount || f.amount <= 0) {
    alert('Le montant du transfert doit être positif.')
    return
  }
  transferring.value = true
  try {
    await TransactionsAPI.transfer({
      fromAccountId: f.fromAccountId,
      toAccountId: f.toAccountId,
      amount: f.amount,
      date: f.date,
      memo: f.memo
    })
    showTransferModal.value = false
    await load()
  } catch (e) {
    alert('Erreur lors du transfert : ' + (e.response?.data?.error || e.message))
  } finally {
    transferring.value = false
  }
}

onMounted(load)
</script>

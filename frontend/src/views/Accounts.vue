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
          <th class="num">Solde courant</th>
          <th>Devise</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="a in sortedAccounts" :key="a.id">
          <td>{{ a.name }}</td>
          <td><span class="badge" :class="a.type.toLowerCase()">{{ typeLabel(a.type) }}</span></td>
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

    <AccountModal
      v-if="showModal"
      :account="editing"
      @close="showModal = false"
      @saved="onSaved"
      @deleted="onDeleted"
    />

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
            <AmountInput v-model="transferForm.amount" />
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
import { ref, computed, onMounted } from 'vue'
import { AccountsAPI, TransactionsAPI } from '../services/api'
import { formatAmount } from '../services/format'
import { dataStore } from '../services/dataStore'
import AmountInput from '../components/AmountInput.vue'
import AccountModal from '../components/AccountModal.vue'

// Comptes et soldes partagés avec la sidebar (voir App.vue / dataStore.js) :
// une modification ici (création, édition, suppression, virement) se
// répercute automatiquement dans la sidebar sans code supplémentaire.
const accounts = computed(() => dataStore.state.accounts)
const balances = computed(() => dataStore.state.balances)
const showModal = ref(false)
const editing = ref(null)

const showTransferModal = ref(false)
const transferring = ref(false)
const transferForm = ref(emptyTransferForm())

function emptyTransferForm() {
  return {
    fromAccountId: null,
    toAccountId: null,
    amount: 0,
    date: new Date().toISOString().slice(0, 10),
    memo: ''
  }
}

/** Comptes courants toujours en tête de liste, le reste dans l'ordre reçu de l'API. */
const sortedAccounts = computed(() =>
  [...accounts.value].sort((a, b) => (a.type === 'CHECKING' ? 0 : 1) - (b.type === 'CHECKING' ? 0 : 1))
)

function typeLabel(type) {
  const labels = { CHECKING: 'Courant', SAVINGS: 'Épargne', CASH: 'Espèces', CREDIT_CARD: 'Carte', INVESTMENT: 'Placement' }
  return labels[type] || type
}

function openCreate() {
  editing.value = null
  showModal.value = true
}

function openEdit(account) {
  editing.value = account
  showModal.value = true
}

async function onSaved() {
  showModal.value = false
  await dataStore.refreshAccounts()
}

async function onDeleted() {
  showModal.value = false
  await dataStore.refreshAccounts()
}

/** Suppression directe depuis la ligne du tableau (raccourci sans passer par la modale). */
async function remove(account) {
  if (!confirm(`Supprimer le compte "${account.name}" et toutes ses transactions ?`)) return
  await AccountsAPI.remove(account.id)
  await dataStore.refreshAccounts()
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
    // Un virement crée deux transactions : rafraîchit aussi la liste des
    // transactions du cache partagé, pas seulement les soldes.
    await dataStore.refreshTransactions()
  } catch (e) {
    alert('Erreur lors du transfert : ' + (e.response?.data?.error || e.message))
  } finally {
    transferring.value = false
  }
}

onMounted(dataStore.ensureLoaded)
</script>

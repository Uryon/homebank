<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Comptes</h1>
        <div class="subtitle">Gérez vos comptes bancaires</div>
      </div>
      <button class="btn" @click="openCreate">+ Nouveau compte</button>
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { AccountsAPI } from '../services/api'

const accounts = ref([])
const balances = ref({})
const showModal = ref(false)
const editing = ref(null)
const form = ref(emptyForm())

function emptyForm() {
  return { name: '', type: 'CHECKING', currency: 'EUR', initialBalance: 0, notes: '' }
}

function typeLabel(type) {
  const labels = { CHECKING: 'Courant', SAVINGS: 'Épargne', CASH: 'Espèces', CREDIT_CARD: 'Carte', INVESTMENT: 'Placement' }
  return labels[type] || type
}

function formatAmount(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v || 0)
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

onMounted(load)
</script>

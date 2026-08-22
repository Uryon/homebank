<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal">
      <h2>{{ isEdit ? 'Modifier le compte' : 'Nouveau compte' }}</h2>
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
          <AmountInput v-model="form.initialBalance" />
        </div>
        <div class="form-field" style="grid-column: span 2;">
          <label>Notes</label>
          <textarea v-model="form.notes" rows="2"></textarea>
        </div>
      </div>
      <div style="display:flex; justify-content:space-between; align-items:center; margin-top:20px;">
        <button v-if="isEdit" class="btn danger" @click="remove">Supprimer</button>
        <div style="display:flex; gap:10px; margin-left:auto;">
          <button class="btn secondary" @click="$emit('close')">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Modale de création/édition/suppression d'un compte, partagée entre la
 * page Comptes et le raccourci d'édition de la sidebar (voir App.vue) pour
 * ne pas dupliquer cette logique à deux endroits.
 */
import { ref, computed, watch } from 'vue'
import { AccountsAPI } from '../services/api'
import AmountInput from './AmountInput.vue'

const props = defineProps({
  account: { type: Object, default: null }
})
const emit = defineEmits(['close', 'saved', 'deleted'])

const isEdit = computed(() => !!props.account)

function emptyForm() {
  return { name: '', type: 'CHECKING', currency: 'EUR', initialBalance: 0, notes: '' }
}

const form = ref(props.account ? { ...props.account } : emptyForm())

watch(() => props.account, (account) => {
  form.value = account ? { ...account } : emptyForm()
})

async function save() {
  if (!form.value.name.trim()) {
    alert('Le nom du compte est obligatoire.')
    return
  }
  const saved = isEdit.value
    ? await AccountsAPI.update(props.account.id, form.value)
    : await AccountsAPI.create(form.value)
  emit('saved', saved)
}

async function remove() {
  if (!confirm(`Supprimer le compte "${props.account.name}" et toutes ses transactions ?`)) return
  await AccountsAPI.remove(props.account.id)
  emit('deleted', props.account.id)
}
</script>

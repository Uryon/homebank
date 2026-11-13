<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div v-dialog="() => $emit('close')" class="modal" role="dialog" aria-modal="true">
      <h2>{{ isEdit ? 'Modifier le compte' : 'Nouveau compte' }}</h2>
      <div class="form-grid">
        <div class="form-field" style="grid-column: span 2;">
          <label for="account-modal-name">Nom</label>
          <input id="account-modal-name" v-model="form.name" placeholder="Ex : Compte courant" />
        </div>
        <div class="form-field">
          <label for="account-modal-type">Type</label>
          <select id="account-modal-type" v-model="form.type">
            <option value="CHECKING">Compte courant</option>
            <option value="SAVINGS">Épargne</option>
            <option value="CASH">Espèces</option>
            <option value="CREDIT_CARD">Carte de crédit</option>
            <option value="INVESTMENT">Placement</option>
          </select>
        </div>
        <div class="form-field">
          <label for="account-modal-currency">Devise</label>
          <input id="account-modal-currency" v-model="form.currency" placeholder="EUR" maxlength="3" />
        </div>
        <div class="form-field">
          <label for="account-modal-balance">Solde initial</label>
          <AmountInput id="account-modal-balance" v-model="form.initialBalance" :allow-relative="false" />
        </div>
        <div class="form-field" style="grid-column: span 2;">
          <label for="account-modal-notes">Notes</label>
          <textarea id="account-modal-notes" v-model="form.notes" rows="2"></textarea>
        </div>
        <div v-if="isEdit" class="form-field" style="grid-column: span 2;">
          <label class="archive-toggle">
            <input v-model="form.archived" type="checkbox" />
            Compte archivé
          </label>
          <p class="archive-hint">
            Masqué de la barre latérale et des listes de saisie ; plus aucune transaction ne peut y être ajoutée.
            L'historique, le solde et le patrimoine net restent inchangés.
          </p>
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
import { notify, askConfirm } from '../services/feedback'
/**
 * Modale de création/édition/suppression d'un compte, partagée entre la
 * page Comptes et le raccourci d'édition de la sidebar (voir App.vue) pour
 * ne pas dupliquer cette logique à deux endroits.
 */
import { ref, computed, watch } from 'vue'
import { AccountsAPI } from '../services/api'
import { alertApiError } from '../services/errors'
import AmountInput from './AmountInput.vue'

const props = defineProps({
  account: { type: Object, default: null }
})
const emit = defineEmits(['close', 'saved', 'deleted'])

const isEdit = computed(() => !!props.account)

function emptyForm() {
  return { name: '', type: 'CHECKING', currency: 'EUR', initialBalance: 0, notes: '', archived: false }
}

const form = ref(props.account ? { ...props.account } : emptyForm())

watch(() => props.account, (account) => {
  form.value = account ? { ...account } : emptyForm()
})

async function save() {
  if (!form.value.name.trim()) {
    notify('Le nom du compte est obligatoire.', 'warning')
    return
  }
  try {
    const saved = isEdit.value
      ? await AccountsAPI.update(props.account.id, form.value)
      : await AccountsAPI.create(form.value)
    notify(isEdit.value ? 'Compte modifié.' : 'Compte créé.', 'success')
    emit('saved', saved)
  } catch (e) {
    alertApiError("Erreur lors de l'enregistrement du compte", e)
  }
}

async function remove() {
  // Le backend refuse (409) la suppression d'un compte qui contient des
  // transactions (voir ServiceCompte#delete) : le message ne doit plus
  // laisser croire que l'historique serait supprime avec lui.
  if (!(await askConfirm(`Supprimer le compte "${props.account.name}" ? Seul un compte sans transaction peut être supprimé.`))) return
  try {
    await AccountsAPI.remove(props.account.id)
    notify('Compte supprimé.', 'success')
    emit('deleted', props.account.id)
  } catch (e) {
    alertApiError('Suppression du compte impossible', e)
  }
}
</script>

<style scoped>
.archive-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  cursor: pointer;
}
.archive-hint {
  margin: 4px 0 0;
  color: var(--text-muted);
  font-size: 13px;
}
</style>

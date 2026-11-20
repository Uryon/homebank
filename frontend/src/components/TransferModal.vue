<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div v-dialog="() => $emit('close')" class="modal" role="dialog" aria-modal="true">
      <h2>Transfert entre comptes</h2>
      <p class="modal-intro">
        Débite le compte source et crédite le compte destination du même montant.
        Ce mouvement n'est jamais compté comme une dépense ou un revenu dans les rapports.
      </p>
      <div class="form-grid">
        <div class="form-field">
          <label for="transfer-from">Compte source</label>
          <select id="transfer-from" v-model.number="form.fromAccountId" @change="clear('toAccount')">
            <option v-for="a in options" :key="a.id" :value="a.id">{{ a.label }}</option>
          </select>
        </div>
        <div class="form-field">
          <label for="transfer-to">Compte destination</label>
          <select id="transfer-to" v-model.number="form.toAccountId" v-bind="invalidAttrs('toAccount')" @change="clear('toAccount')">
            <option
              v-for="a in options"
              :key="a.id"
              :value="a.id"
              :disabled="a.id === form.fromAccountId"
            >
              {{ a.label }}
            </option>
          </select>
          <FieldError id="transfer-to-error" :message="errors.toAccount" />
        </div>
        <div class="form-field">
          <label for="transfer-amount">Montant</label>
          <AmountInput id="transfer-amount" v-model="form.amount" placeholder="0,00" v-bind="invalidAttrs('amount')" @input="clear('amount')" />
          <FieldError id="transfer-amount-error" :message="errors.amount" />
        </div>
        <div class="form-field">
          <label for="transfer-date">Date</label>
          <input id="transfer-date" v-model="form.date" type="date" :min="MIN_DATE" :max="MAX_DATE" />
        </div>
        <div class="form-field span-2">
          <label for="transfer-memo">Mémo (facultatif)</label>
          <input id="transfer-memo" v-model="form.memo" maxlength="255" placeholder="Ex : Épargne du mois" />
        </div>
      </div>
      <div class="modal-actions">
        <button class="btn secondary" @click="$emit('close')">Annuler</button>
        <button class="btn" :disabled="transferring" @click="submit">
          {{ transferring ? 'Transfert en cours…' : 'Confirmer le transfert' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Virement entre deux comptes (voir ServiceOperationsCompte#createTransfer
 * côté backend) : deux transactions liées, jamais comptées comme dépense ou
 * revenu. La fenêtre saisit, valide et envoie ; le rechargement et
 * l'« Annuler » restent dans la page (voir Accounts.vue).
 */
import { computed, ref } from 'vue'
import { TransactionsAPI } from '../services/api'
import { alertApiError } from '../services/errors'
import { MAX_DATE, MIN_DATE, formatAmount, toLocalIsoDate } from '../services/format'
import { useFieldErrors } from '../services/fieldErrors'
import AmountInput from './AmountInput.vue'
import FieldError from './FieldError.vue'

const props = defineProps({
  /** Comptes pouvant recevoir un virement (jamais un compte archivé, voir ServiceCompte#ensureNotArchived). */
  accounts: { type: Array, required: true },
  /** Solde de chaque compte par id (voir dataStore.state.balances), affiché dans les listes. */
  balances: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['close', 'saved'])

/** Comptes avec leur solde, pour choisir sans repasser par le tableau. */
const options = computed(() => props.accounts.map(a => ({
  id: a.id,
  label: `${a.name} — ${formatAmount(Number(props.balances[a.id] ?? 0), a.currency)}`
})))

// Deux premiers comptes proposés d'office comme source et destination, s'il y en a deux.
const prefilled = props.accounts.length >= 2
const form = ref({
  fromAccountId: prefilled ? props.accounts[0].id : null,
  toAccountId: prefilled ? props.accounts[1].id : null,
  amount: null, // vide plutôt que 0 : on tape directement le montant
  date: toLocalIsoDate(),
  memo: ''
})
const transferring = ref(false)

const { errors, check, clear, invalidAttrs } = useFieldErrors({ toAccount: 'transfer-to', amount: 'transfer-amount' })

/** Erreur du compte destination (sélection manquante, ou identique au compte source), vide si valide. */
function accountsError(f) {
  if (!f.fromAccountId || !f.toAccountId) return 'Choisissez les deux comptes.'
  if (f.fromAccountId === f.toAccountId) return 'Choisissez un compte différent du compte source.'
  return ''
}

async function submit() {
  const f = form.value
  const valid = check({
    toAccount: accountsError(f),
    amount: f.amount > 0 ? '' : 'Saisissez un montant positif.'
  })
  if (!valid) return
  transferring.value = true
  try {
    const [debit] = await TransactionsAPI.transfer({
      fromAccountId: f.fromAccountId,
      toAccountId: f.toAccountId,
      amount: f.amount,
      date: f.date,
      memo: f.memo
    })
    emit('saved', debit)
  } catch (e) {
    alertApiError('Erreur lors du transfert', e)
  } finally {
    transferring.value = false
  }
}
</script>

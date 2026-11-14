<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div v-dialog="() => $emit('close')" class="modal" role="dialog" aria-modal="true" aria-labelledby="reconcile-title">
      <h2 id="reconcile-title">Rapprocher « {{ account.name }} »</h2>
      <p class="reconcile-intro">
        Saisissez le solde affiché sur votre relevé bancaire à une date : HomeBank le compare
        à son propre solde à la même date.
      </p>

      <div class="form-grid">
        <div class="form-field">
          <label for="reconcile-date">Date du relevé</label>
          <input id="reconcile-date" v-model="date" type="date" :max="todayIso" @input="invalidateResult" />
        </div>
        <div class="form-field">
          <label for="reconcile-balance">Solde du relevé ({{ currencyLabel }})</label>
          <!-- @input (à chaque frappe), pas @update:model-value : AmountInput ne
               valide le montant qu'en quittant le champ, trop tard (voir invalidateResult). -->
          <AmountInput id="reconcile-balance" v-model="statementBalance" :allow-relative="false" @input="invalidateResult" />
        </div>
      </div>

      <div v-if="result" class="reconcile-result" :class="isBalanced ? 'balanced' : 'unbalanced'" role="status">
        <dl class="reconcile-figures">
          <div><dt>Solde du relevé</dt><dd><Money :value="result.statementBalance" :currency="account.currency" /></dd></div>
          <div><dt>Solde HomeBank au {{ formattedDate }}</dt><dd><Money :value="result.bookBalance" :currency="account.currency" /></dd></div>
          <div class="reconcile-gap"><dt>Écart</dt><dd><Money :value="result.difference" :currency="account.currency" /></dd></div>
        </dl>

        <p v-if="isBalanced" class="reconcile-message">
          <Icon name="check" size="sm" /> Les soldes concordent.
          <template v-if="result.pendingCount">
            Vous pouvez valider les {{ result.pendingCount }} opération(s) encore en attente jusqu'au {{ formattedDate }}.
          </template>
          <template v-else>Toutes les opérations jusqu'à cette date sont déjà validées.</template>
        </p>
        <p v-else class="reconcile-message">
          {{ gapExplanation }}
          <template v-if="result.pendingCount">
            {{ result.pendingCount }} opération(s) sont encore en attente jusqu'à cette date
            (total {{ formattedPendingTotal }}) :
            <RouterLink :to="pendingLink" @click="$emit('close')">les vérifier</RouterLink>.
          </template>
        </p>
      </div>

      <div class="reconcile-actions">
        <button class="btn secondary" @click="$emit('close')">Fermer</button>
        <button
          v-if="canValidate"
          class="btn"
          :disabled="busy"
          @click="validatePending"
        >
          {{ busy ? 'Validation…' : `Valider les ${result.pendingCount} opération(s)` }}
        </button>
        <button v-else class="btn" :disabled="busy" @click="compare">
          {{ busy ? 'Calcul…' : 'Comparer' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Rapprochement bancaire d'un compte (voir ServiceRapprochement côté
 * backend) : compare le solde d'un relevé à celui de HomeBank à la même date
 * et, s'ils concordent, valide d'un coup les opérations encore en attente.
 */
import { computed, ref } from 'vue'
import { AccountsAPI } from '../services/api'
import { alertApiError } from '../services/errors'
import { notify } from '../services/feedback'
import { DEFAULT_CURRENCY, formatAmount, formatDate, toLocalIsoDate } from '../services/format'
import AmountInput from './AmountInput.vue'
import Icon from './Icon.vue'
import Money from './Money.vue'

const props = defineProps({
  account: { type: Object, required: true }
})
const emit = defineEmits(['close', 'validated'])

const todayIso = toLocalIsoDate()
const date = ref(todayIso)
const statementBalance = ref(0)
const result = ref(null)
const busy = ref(false)

const currencyLabel = computed(() => props.account.currency || DEFAULT_CURRENCY)
const formattedDate = computed(() => formatDate(result.value?.date))
const isBalanced = computed(() => Number(result.value?.difference) === 0)
const canValidate = computed(() => isBalanced.value && result.value.pendingCount > 0)
const formattedPendingTotal = computed(() => formatAmount(result.value?.pendingTotal, props.account.currency))

/**
 * Efface le résultat dès la première frappe dans la date ou le solde, et non
 * à la validation du champ : sinon l'ancien résultat (et son bouton
 * « Valider ») restait affiché pour un montant qui n'était plus celui saisi,
 * et le clic sur ce bouton était perdu — la sortie du champ effaçait le
 * résultat et redimensionnait la fenêtre entre l'appui et le relâchement.
 */
function invalidateResult() {
  result.value = null
}

/** Lien vers les opérations en attente de ce compte, pour chercher l'origine d'un écart. */
const pendingLink = computed(() => ({ path: '/transactions', query: { account: props.account.id, pending: '1' } }))

/**
 * Écart = relevé − HomeBank : positif, il manque un crédit (ou une dépense
 * est en trop) dans HomeBank ; négatif, l'inverse.
 */
const gapExplanation = computed(() => {
  const gap = Number(result.value.difference)
  const amount = formatAmount(Math.abs(gap), props.account.currency)
  return gap > 0
    ? `Le relevé indique ${amount} de plus que HomeBank : un revenu manque, ou une dépense est en trop ou mal saisie.`
    : `Le relevé indique ${amount} de moins que HomeBank : une dépense manque, ou un revenu est en trop ou mal saisi.`
})

async function compare() {
  if (!date.value) {
    notify('La date du relevé est obligatoire.', 'warning')
    return
  }
  busy.value = true
  try {
    result.value = await AccountsAPI.reconciliation(props.account.id, date.value, statementBalance.value || 0)
  } catch (e) {
    alertApiError('Rapprochement impossible', e)
  } finally {
    busy.value = false
  }
}

async function validatePending() {
  busy.value = true
  try {
    const { validatedCount } = await AccountsAPI.validateReconciled(props.account.id, result.value.date)
    notify(`${validatedCount} opération(s) validée(s) : compte rapproché au ${formattedDate.value}.`, 'success')
    emit('validated')
  } catch (e) {
    alertApiError('Validation des opérations impossible', e)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.reconcile-intro {
  margin: 0 0 16px;
  color: var(--text-muted);
  font-size: 14px;
}
.reconcile-result {
  margin-top: 18px;
  padding: 14px 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--line);
  background: var(--surface-sunken);
}
.reconcile-result.balanced { border-color: var(--mint); background: var(--mint-dim); }
.reconcile-result.unbalanced { border-color: var(--amber); background: var(--amber-dim); }
.reconcile-figures {
  display: grid;
  gap: 6px;
  margin: 0;
}
.reconcile-figures div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}
.reconcile-figures dt { color: var(--text-muted); }
.reconcile-figures dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.reconcile-gap {
  padding-top: 6px;
  border-top: 1px solid var(--line-strong);
}
/* Texte courant (pas de flex) : un flex découpait la phrase en blocs, d'où un
   lien « les vérifier » isolé sur sa ligne et un point détaché. */
.reconcile-message {
  margin: 12px 0 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-primary);
}
.reconcile-message a {
  color: var(--azure);
  font-weight: 600;
  text-decoration: underline;
}
.reconcile-message :deep(.icon) {
  vertical-align: -2px;
  margin-right: 2px;
}
.reconcile-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
</style>

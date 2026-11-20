<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div v-dialog="() => $emit('close')" class="modal" role="dialog" aria-modal="true">
      <h2>Nouvelle transaction</h2>

      <div class="form-grid">
        <div class="form-field">
          <label for="new-tx-account">Compte</label>
          <select id="new-tx-account" v-model.number="form.accountId" v-bind="invalidAttrs('accountId')" @change="clear('accountId')">
            <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
          <FieldError id="new-tx-account-error" :message="errors.accountId" />
        </div>

        <div class="form-field">
          <label for="new-tx-date">Date</label>
          <input id="new-tx-date" v-model="form.date" type="date" :min="MIN_DATE" :max="MAX_DATE" v-bind="invalidAttrs('date')" @input="clear('date')" />
          <FieldError id="new-tx-date-error" :message="errors.date" />
        </div>

        <div class="form-field">
          <label for="new-tx-payee">Tiers</label>
          <!-- Compte et date sont préremplis : la saisie commence par le tiers. -->
          <PayeeAutocomplete id="new-tx-payee" v-model="form.payee" :payees="payees" placeholder="Ex : Supermarché" autofocus />
        </div>

        <div class="form-field">
          <div class="amount-label-row">
            <label for="new-tx-amount">Montant</label>
            <div class="flow-toggle" role="radiogroup" aria-label="Sens de la transaction">
              <button
                v-for="option in FLOW_OPTIONS"
                :key="option.value"
                type="button"
                role="radio"
                class="flow-option"
                :class="[option.value.toLowerCase(), { active: flow === option.value }]"
                :aria-checked="flow === option.value"
                @click="setFlow(option.value)"
              >{{ option.label }}</button>
            </div>
          </div>
          <AmountInput id="new-tx-amount" v-model="form.amount" placeholder="0,00" v-bind="invalidAttrs('amount')" @input="clear('amount')" />
          <FieldError id="new-tx-amount-error" :message="errors.amount" />
        </div>

        <div class="form-field">
          <label for="new-tx-category">Catégorie</label>
          <CategoryAutocomplete
            id="new-tx-category"
            v-model="form.categoryId"
            :categories="categories"
            :transaction-type="flow"
            placeholder="Rechercher (facultatif)"
          />
        </div>

        <div class="form-field">
          <label for="new-tx-payment-mode">Mode de paiement</label>
          <select id="new-tx-payment-mode" v-model="form.paymentMode">
            <option v-for="mode in PAYMENT_MODE_OPTIONS" :key="mode.value" :value="mode.value">{{ mode.label }}</option>
          </select>
        </div>

        <div class="form-field span-2">
          <label for="new-tx-memo">Mémo</label>
          <input id="new-tx-memo" v-model="form.memo" maxlength="255" placeholder="Note libre" />
        </div>

        <div class="form-field span-2 checkbox-field">
          <input type="checkbox" v-model="form.periodicExpense" id="new-periodic-expense" />
          <label for="new-periodic-expense">Dépense périodique récurrente (loyer, abonnement…)</label>
        </div>

        <div class="form-field span-2 checkbox-field">
          <input type="checkbox" v-model="form.exceptional" id="new-exceptional" />
          <label for="new-exceptional">Transaction exceptionnelle (exclue des tableaux de bord, ex : achat d'une voiture)</label>
        </div>
      </div>

      <div class="modal-actions">
        <button class="btn secondary" @click="$emit('close')">Annuler</button>
        <button class="btn" @click="submit">Enregistrer</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Saisie d'une nouvelle transaction, vierge ou pré-remplie (« Dupliquer »).
 * La modale gère le formulaire, le sens Dépense/Revenu, la catégorie par
 * défaut du tiers et la validation des champs ; la détection de doublon,
 * l'appel au serveur et l'« Annuler » restent dans la page (voir save dans
 * Transactions.vue). Le formulaire est recréé à chaque ouverture (v-if).
 */
import { ref, watch } from 'vue'
import AmountInput from './AmountInput.vue'
import CategoryAutocomplete from './CategoryAutocomplete.vue'
import FieldError from './FieldError.vue'
import PayeeAutocomplete from './PayeeAutocomplete.vue'
import { useFieldErrors } from '../services/fieldErrors'
import { MAX_DATE, MIN_DATE, amountFieldError, dateFieldError } from '../services/format'
import { findPayeeByName } from '../services/payee'
import { PAYMENT_MODE_OPTIONS } from '../services/paymentModes'

const props = defineProps({
  /** Valeurs de départ du formulaire (voir newTransactionDraft dans Transactions.vue). */
  draft: { type: Object, required: true },
  /** Comptes ouverts à la saisie (jamais un compte archivé). */
  accounts: { type: Array, required: true },
  payees: { type: Array, required: true },
  /** Tiers indexés par nom normalisé (voir payeeByNormalizedName dans Transactions.vue). */
  payeeIndex: { type: Map, required: true },
  /** Catégories aplaties (voir flattenCategories), comme pour CategoryAutocomplete. */
  categories: { type: Array, required: true }
})

const emit = defineEmits({
  close: null,
  /** Formulaire validé, à transformer par transactionPayloadFrom. */
  submit: (form) => form != null
})

/**
 * Sens de la transaction : le montant se tape sans signe et prend celui du
 * sens choisi. Un montant tapé négatif bascule sur Dépense. CategoryAutocomplete
 * ne propose que les catégories de ce sens, comme TransactionRow.vue le fait
 * d'après le signe.
 */
const FLOW_OPTIONS = [
  { value: 'EXPENSE', label: 'Dépense' },
  { value: 'INCOME', label: 'Revenu' }
]

const form = ref({ ...props.draft })
const flow = ref(props.draft.amount > 0 ? 'INCOME' : 'EXPENSE')

function signedForFlow(amount, newFlow) {
  if (!amount) return amount
  return newFlow === 'EXPENSE' ? -Math.abs(amount) : Math.abs(amount)
}

function setFlow(newFlow) {
  flow.value = newFlow
  form.value.amount = signedForFlow(form.value.amount, newFlow)
}

watch(() => form.value.amount, (amount) => {
  if (amount < 0) flow.value = 'EXPENSE'
  else if (amount > 0 && flow.value === 'EXPENSE') form.value.amount = -amount
})

/**
 * Catégorie par défaut du tiers : choisir ou changer le tiers remplit la
 * catégorie avec celle du tiers reconnu, y compris si une catégorie était
 * déjà renseignée (voir le commentaire de payeeByNormalizedName dans
 * Transactions.vue). Seul un changement de tiers déclenche ce remplissage :
 * une duplication garde la catégorie de la transaction d'origine.
 */
watch(() => form.value.payee, (payeeName) => {
  const payee = findPayeeByName(props.payeeIndex, payeeName, form.value.amount)
  if (payee?.categoryId) form.value.categoryId = payee.categoryId
})

/** Erreurs affichées sous les champs, effacées dès la saisie. */
const { errors, check, clear, invalidAttrs } = useFieldErrors({
  accountId: 'new-tx-account',
  date: 'new-tx-date',
  amount: 'new-tx-amount'
})

function submit() {
  const valid = check({
    accountId: form.value.accountId ? '' : 'Choisissez un compte.',
    date: dateFieldError(form.value.date),
    amount: amountFieldError(form.value.amount)
  })
  if (valid) emit('submit', form.value)
}
</script>

<style scoped>
.amount-label-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
/* Marges négatives : la bascule (22 px) ne doit pas rendre cette ligne plus
   haute qu'un libellé simple, sinon le champ Montant se décale de celui de
   la colonne voisine (Tiers). */
.flow-toggle {
  display: inline-flex;
  flex-shrink: 0;
  margin-block: -2px;
  padding: 1px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--ink-800);
}
.flow-option {
  padding: 1px 9px;
  line-height: 16px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.flow-option:hover:not(.active) { color: var(--text-primary); }
.flow-option.expense.active { background: var(--coral-dim); color: var(--coral); }
.flow-option.income.active { background: var(--mint-dim); color: var(--mint); }
</style>

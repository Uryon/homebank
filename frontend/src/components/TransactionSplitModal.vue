<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div v-dialog="() => $emit('close')" class="modal" role="dialog" aria-modal="true">
      <h2>Diviser la transaction</h2>
      <div class="text-hint modal-hint">
        {{ transaction.payee || 'Sans tiers' }} · {{ formatDate(transaction.date) }} ·
        Montant total : {{ formatAmount(transaction.amount, currency) }}
      </div>

      <div class="form-grid">
        <div class="form-field">
          <label for="split-first-amount">Montant — 1ère partie {{ transaction.amount < 0 ? '(négatif = dépense)' : '(positif = revenu)' }}</label>
          <AmountInput
            id="split-first-amount"
            v-model="form.firstAmount"
            v-bind="invalidAttrs('firstAmount')"
            @input="clear('firstAmount')"
          />
          <FieldError id="split-first-amount-error" :message="errors.firstAmount" />
        </div>
        <div class="form-field">
          <label for="split-first-category">Catégorie — 1ère partie</label>
          <CategoryAutocomplete
            id="split-first-category"
            v-model="form.firstCategoryId"
            :categories="categories"
            :transaction-type="amountType"
            placeholder="Aucune"
          />
        </div>
        <div class="form-field span-2">
          <label for="split-first-memo">Mémo — 1ère partie</label>
          <input id="split-first-memo" v-model="form.firstMemo" maxlength="255" placeholder="Note libre" />
        </div>

        <div class="form-field">
          <span class="form-field-label">Montant — 2e partie</span>
          <div class="split-second-amount amount" :class="secondAmount >= 0 ? 'positive' : 'negative'">
            {{ formatAmount(secondAmount, currency) }}
          </div>
        </div>
        <div class="form-field">
          <label for="split-second-category">Catégorie — 2e partie</label>
          <CategoryAutocomplete
            id="split-second-category"
            v-model="form.secondCategoryId"
            :categories="categories"
            :transaction-type="amountType"
            placeholder="Aucune"
          />
        </div>
        <div class="form-field span-2">
          <label for="split-second-memo">Mémo — 2e partie</label>
          <input id="split-second-memo" v-model="form.secondMemo" maxlength="255" placeholder="Note libre" />
        </div>
      </div>

      <p v-if="error" class="split-error">{{ error }}</p>

      <div class="modal-actions">
        <button class="btn secondary" @click="$emit('close')">Annuler</button>
        <button class="btn" :disabled="saving" @click="submit">
          {{ saving ? 'Division…' : 'Diviser' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Division d'une transaction en deux parties (montant, catégorie et mémo de
 * chacune). La modale saisit et valide ; l'appel au serveur et l'« Annuler »
 * restent dans la page (voir saveSplit dans Transactions.vue), qui renvoie
 * l'éventuelle erreur serveur par la prop error.
 */
import { computed, ref } from 'vue'
import AmountInput from './AmountInput.vue'
import CategoryAutocomplete from './CategoryAutocomplete.vue'
import FieldError from './FieldError.vue'
import { useFieldErrors } from '../services/fieldErrors'
import { splitFirstAmountError } from '../services/split'
import { formatAmount, formatDate } from '../services/format'

const props = defineProps({
  transaction: { type: Object, required: true },
  /** Devise du compte de la transaction, pour afficher ses montants dans la bonne monnaie. */
  currency: { type: String, default: undefined },
  /** Catégories aplaties (voir flattenCategories), comme pour CategoryAutocomplete. */
  categories: { type: Array, required: true },
  saving: { type: Boolean, default: false },
  /** Erreur renvoyée par le serveur (non rattachée à un champ). */
  error: { type: String, default: '' }
})

const emit = defineEmits({
  close: null,
  /** Parties validées : { firstAmount, firstCategoryId, firstMemo, secondCategoryId, secondMemo }. */
  submit: (parts) => parts != null
})

// Moitié par défaut, arrondie au centime, modifiable avant validation.
const form = ref({
  firstAmount: Math.round((Number(props.transaction.amount) / 2) * 100) / 100,
  firstCategoryId: props.transaction.categoryId ?? null,
  firstMemo: props.transaction.memo || '',
  secondCategoryId: props.transaction.categoryId ?? null,
  secondMemo: ''
})

const { errors, check, clear, invalidAttrs } = useFieldErrors({ firstAmount: 'split-first-amount' })

/** Montant de la 2e partie, calculé pour l'affichage seul (la valeur qui compte est recalculée côté backend, voir ServiceTransaction#splitTransaction). */
const secondAmount = computed(() => Number(props.transaction.amount) - Number(form.value.firstAmount || 0))

// Même logique que la saisie d'une transaction : ne proposer que des
// catégories du bon sens (dépense/revenu) selon le signe du montant d'origine.
const amountType = computed(() => {
  const amount = props.transaction.amount
  if (amount == null || amount === 0) return null
  return amount < 0 ? 'EXPENSE' : 'INCOME'
})

function submit() {
  const amountError = splitFirstAmountError(props.transaction.amount, form.value.firstAmount, value => formatAmount(value, props.currency))
  if (!check({ firstAmount: amountError })) return
  emit('submit', { ...form.value, firstAmount: Number(form.value.firstAmount) })
}
</script>

<style scoped>
.modal-hint { margin-bottom: 20px; }

/* Montant de la 2e partie : lecture seule, calculé à partir du montant total
   et de la 1ère partie (voir secondAmount) — même gabarit qu'un champ input
   pour rester aligné avec les autres champs du formulaire, mais non
   éditable directement. */
.split-second-amount {
  display: flex;
  align-items: center;
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--ink-950);
  font-weight: 700;
}

.split-error {
  color: var(--coral);
  font-size: 13px;
  margin-top: 12px;
}
</style>

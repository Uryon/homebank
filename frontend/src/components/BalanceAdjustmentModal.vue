<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div v-dialog="() => $emit('close')" class="modal" role="dialog" aria-modal="true" aria-labelledby="adjust-title">
      <h2 id="adjust-title">Actualiser le solde de « {{ account.name }} »</h2>
      <p class="adjust-intro">
        Saisissez la valeur réelle du compte (relevé, valeur du placement…). HomeBank enregistre
        l'écart comme un « Ajustement de solde » : il compte dans le solde et le patrimoine,
        mais jamais comme une dépense ou un revenu.
      </p>

      <div class="form-grid">
        <div class="form-field">
          <label for="adjust-date">Date de la valeur</label>
          <input id="adjust-date" v-model="date" type="date" :max="todayIso" />
        </div>
        <div class="form-field">
          <label for="adjust-balance">Solde réel ({{ currencyLabel }})</label>
          <AmountInput id="adjust-balance" v-model="actualBalance" :allow-relative="false" />
        </div>
      </div>
      <p class="adjust-current">
        Solde actuel dans HomeBank : <strong><Money :value="currentBalance" :currency="account.currency" /></strong>
      </p>

      <div class="adjust-actions">
        <button class="btn secondary" @click="$emit('close')">Annuler</button>
        <button class="btn" :disabled="saving" @click="save">
          {{ saving ? 'Enregistrement…' : 'Actualiser le solde' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Aligne le solde d'un compte sur sa valeur réelle (voir
 * ServiceTransaction#adjustAccountBalance côté backend) : utile pour un
 * livret ou un placement dont on ne saisit pas chaque mouvement.
 */
import { computed, ref } from 'vue'
import { AccountsAPI } from '../services/api'
import { alertApiError } from '../services/errors'
import { notify } from '../services/feedback'
import { DEFAULT_CURRENCY, formatAmount, toLocalIsoDate } from '../services/format'
import AmountInput from './AmountInput.vue'
import Money from './Money.vue'

const props = defineProps({
  account: { type: Object, required: true },
  /** Solde courant calculé par HomeBank (voir dataStore.state.balances), pré-rempli dans le champ. */
  currentBalance: { type: Number, default: 0 }
})
const emit = defineEmits(['close', 'saved'])

const todayIso = toLocalIsoDate()
const date = ref(todayIso)
const actualBalance = ref(props.currentBalance)
const saving = ref(false)

const currencyLabel = computed(() => props.account.currency || DEFAULT_CURRENCY)

async function save() {
  if (!date.value) {
    notify('La date est obligatoire.', 'warning')
    return
  }
  saving.value = true
  try {
    const adjustment = await AccountsAPI.adjustBalance(props.account.id, date.value, actualBalance.value || 0)
    notify(`Solde actualisé : ajustement de ${formatAmount(adjustment.amount, props.account.currency)}.`, 'success')
    emit('saved')
  } catch (e) {
    alertApiError("Impossible d'actualiser le solde", e)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.adjust-intro {
  margin: 0 0 16px;
  color: var(--text-muted);
  font-size: 14px;
}
.adjust-current {
  margin: 12px 0 0;
  font-size: 14px;
  color: var(--text-muted);
}
.adjust-current strong {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.adjust-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
</style>

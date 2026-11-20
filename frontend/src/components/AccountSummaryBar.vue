<template>
  <section class="account-summary" :aria-label="`Compte ${account.name}`">
    <span class="account-summary-dot" :class="typeClass" aria-hidden="true"></span>
    <div class="account-summary-name">
      <strong>{{ account.name }}</strong>
      <span class="account-summary-type">{{ typeLabel }}<template v-if="account.archived"> · archivé</template></span>
    </div>

    <div class="account-summary-item">
      <span class="account-summary-label">Solde</span>
      <span class="account-summary-value amount" :class="balanceClass"><Money :value="balance" :currency="account.currency" /></span>
    </div>

    <div class="account-summary-item">
      <span class="account-summary-label">À traiter</span>
      <button v-if="pendingCount" type="button" class="account-summary-value account-summary-link" @click="emit('show-pending')"
              title="N'afficher que les transactions à traiter de ce compte">
        {{ pluralize(pendingCount, 'opération', 'opérations') }}
      </button>
      <span v-else class="account-summary-value">Aucune</span>
    </div>

    <div class="account-summary-item">
      <span class="account-summary-label">Rapprochement</span>
      <span class="account-summary-value" :title="validationTitle">{{ validationText }}</span>
    </div>
  </section>
</template>

<script setup>
/**
 * En-tête de la page Transactions filtrée sur un compte : solde, opérations
 * à traiter et date jusqu'à laquelle tout est validé (veille de la plus
 * ancienne opération en attente), ce qu'établit un rapprochement.
 */
import { computed } from 'vue'
import Money from './Money.vue'
import { amountToneClass } from '../services/accounts'
import { formatDate, pluralize, toLocalIsoDate } from '../services/format'

const props = defineProps({
  account: { type: Object, required: true },
  balance: { type: Number, default: 0 },
  pendingCount: { type: Number, default: 0 },
  /** Date ISO de la plus ancienne opération en attente du compte, null si aucune. */
  oldestPendingDate: { type: String, default: null },
  /** Le compte a-t-il au moins une opération ? */
  hasTransactions: { type: Boolean, default: false }
})
const emit = defineEmits(['show-pending'])

const TYPE_LABELS = {
  CHECKING: 'Compte courant',
  SAVINGS: 'Épargne',
  CASH: 'Espèces',
  CREDIT_CARD: 'Carte de crédit',
  INVESTMENT: 'Placement'
}
const typeLabel = computed(() => TYPE_LABELS[props.account.type] || props.account.type)
const typeClass = computed(() => String(props.account.type || '').toLowerCase())
const balanceClass = computed(() => amountToneClass(props.balance))

/** Veille d'une date ISO, en texte. */
function dayBefore(isoDate) {
  const [year, month, day] = isoDate.split('-').map(Number)
  return formatDate(toLocalIsoDate(new Date(year, month - 1, day - 1)))
}

const validationText = computed(() => {
  if (!props.hasTransactions) return 'Aucune opération'
  if (!props.oldestPendingDate) return 'Tout est validé'
  return `Validé jusqu'au ${dayBefore(props.oldestPendingDate)}`
})

const validationTitle = computed(() => (props.oldestPendingDate
  ? `Opération en attente la plus ancienne : ${formatDate(props.oldestPendingDate)}`
  : null))
</script>

<style scoped>
.account-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 28px;
  margin-bottom: 14px;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--ink-900);
  box-shadow: var(--shadow-xs);
}
.account-summary-dot {
  flex: 0 0 10px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--text-faint);
}
.account-summary-dot.checking { background: var(--azure); }
.account-summary-dot.savings { background: var(--mint); }
.account-summary-dot.cash { background: var(--amber); }
.account-summary-dot.credit_card { background: var(--coral); }
.account-summary-dot.investment { background: var(--violet); }
.account-summary-name {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin-right: auto;
}
.account-summary-name strong {
  font-size: 15px;
  color: var(--text-primary);
}
.account-summary-type,
.account-summary-label {
  font-size: 12px;
  color: var(--text-faint);
}
.account-summary-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.account-summary-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.account-summary-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--amber);
  font: inherit;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}
.account-summary-link:hover { text-decoration: underline; text-underline-offset: 3px; }

@media (max-width: 640px) {
  .account-summary { gap: 10px 18px; }
  .account-summary-name { flex-basis: 100%; }
}
</style>

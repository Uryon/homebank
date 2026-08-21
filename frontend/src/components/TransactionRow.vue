<template>
  <tr
    :class="{
      'row-editing': isEditing,
      'row-pending': !isEditing && !transaction.validated,
      'row-future': !isEditing && isFuture(transaction.date)
    }"
    @click="!isEditing && $emit('start-edit')"
    @keydown.esc="$emit('cancel-edit')"
    @keydown.enter.prevent="isEditing && confirmEdit()"
  >
    <!-- Mode édition -->
    <template v-if="isEditing && editForm">
      <td class="num" @click.stop>
        <input type="checkbox" v-model="editForm.validated" title="Transaction validée (rapprochement bancaire)" />
      </td>
      <td @click.stop>
        <input v-model="editForm.date" type="date" ref="firstEditField" />
      </td>
      <td @click.stop>
        <select v-model.number="editForm.accountId">
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </td>
      <td @click.stop>
        <PayeeAutocomplete v-model="editForm.payee" :payees="payees" />
      </td>
      <td @click.stop>
        <CategoryAutocomplete v-model="editForm.categoryId" :categories="flatCategories" placeholder="Aucune" />
      </td>
      <td @click.stop>
        <input v-model="editForm.memo" placeholder="Note libre" />
      </td>
      <td class="num" colspan="2" @click.stop>
        <AmountInput v-model="editForm.amount" style="text-align: right;" />
      </td>
      <td class="num" @click.stop>
        <input type="checkbox" v-model="editForm.periodicExpense" title="Dépense périodique récurrente" />
      </td>
      <td @click.stop class="row-actions">
        <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
        <button class="btn secondary" style="padding: 6px 10px;" @click="$emit('cancel-edit')">✕</button>
      </td>
    </template>

    <!-- Mode lecture -->
    <template v-else>
      <td class="num" @click.stop>
        <span
          class="status-dot"
          :class="transaction.validated ? 'validated' : 'pending'"
          :title="transaction.validated ? 'Validée' : 'En attente de validation — cliquer pour valider'"
          @click="$emit('toggle-validated', transaction)"
        >
          {{ transaction.validated ? '✓' : '' }}
        </span>
      </td>
      <td class="col-date">
        {{ formatDate(transaction.date) }}
        <span v-if="isFuture(transaction.date)" class="future-badge" title="Transaction prévue à une date future : elle n'a pas encore réellement eu lieu et n'est pas comptée dans les statistiques.">Prévu</span>
      </td>
      <td>{{ accountName(transaction.accountId) }}</td>
      <td class="col-payee" :title="transaction.payee">{{ transaction.payee }}</td>
      <td>{{ categoryLabel(transaction.categoryId) }}</td>
      <td class="text-muted">{{ displayMemo(transaction.memo) }}</td>
      <td class="num amount negative">{{ transaction.amount < 0 ? formatAmount(transaction.amount) : '' }}</td>
      <td class="num amount positive">{{ transaction.amount > 0 ? formatAmount(transaction.amount) : '' }}</td>
      <td class="num" @click.stop>
        <input
          type="checkbox"
          :checked="transaction.periodicExpense"
          title="Dépense périodique récurrente"
          @change="$emit('toggle-periodic', transaction)"
        />
      </td>
      <td class="row-actions" @click.stop>
        <button class="btn danger" @click="$emit('remove', transaction)">Suppr.</button>
      </td>
    </template>
  </tr>
</template>

<script setup>
/**
 * Une ligne de la table Transactions, en composant a part entiere : c'est
 * ce qui permet a l'edition inline de rester fluide meme sur un tres grand
 * historique (des milliers de transactions).
 *
 * Avant ce composant, l'etat d'edition (editForm) vivait dans Transactions.vue :
 * chaque frappe au clavier modifiait une donnee reactive lue par LE TEMPLATE
 * PARENT (qui boucle sur toutes les transactions), donc Vue devait
 * reexecuter le rendu de la table ENTIERE (et rappeler categoryLabel/
 * accountName pour chaque ligne) a chaque caractere tape, meme si une seule
 * ligne changeait visuellement.
 *
 * Ici, `editForm` est un etat 100% local a l'instance de ce composant : une
 * frappe ne declenche plus que le re-rendu de CETTE ligne. Les autres lignes
 * ne sont meme pas revisitees par Vue tant que leurs props (transaction,
 * isEditing) ne changent pas reellement.
 */
import { ref, watch, nextTick } from 'vue'
import PayeeAutocomplete from './PayeeAutocomplete.vue'
import CategoryAutocomplete from './CategoryAutocomplete.vue'
import AmountInput from './AmountInput.vue'
import { formatAmount, formatDate, displayMemo } from '../services/format'

const props = defineProps({
  transaction: { type: Object, required: true },
  isEditing: { type: Boolean, default: false },
  accounts: { type: Array, required: true },
  payees: { type: Array, required: true },
  // Categories a plat { id, label, type }, pour CategoryAutocomplete.
  flatCategories: { type: Array, required: true },
  // Index Map precalcules cote parent (id -> objet), pour eviter un scan
  // lineaire de accounts/categories a chaque ligne affichee.
  accountsById: { type: Map, required: true },
  categoriesById: { type: Map, required: true },
  // Map (nom de tiers normalise -> tiers), pour la categorisation
  // automatique par tiers pendant l'edition.
  payeeIndex: { type: Map, required: true },
  todayIso: { type: String, required: true }
})

const emit = defineEmits([
  'start-edit',
  'cancel-edit',
  'confirm-edit',
  'toggle-validated',
  'toggle-periodic',
  'remove'
])

const editForm = ref(null)
const firstEditField = ref(null)

function buildEditForm(t) {
  return {
    accountId: t.accountId,
    date: t.date,
    payee: t.payee || '',
    amount: t.amount,
    categoryId: t.categoryId ?? null,
    paymentMode: t.paymentMode || 'NONE',
    memo: displayMemo(t.memo),
    tags: t.tags || '',
    transferTransactionId: t.transferTransactionId ?? null,
    periodicExpense: !!t.periodicExpense,
    validated: !!t.validated
  }
}

watch(() => props.isEditing, (editing) => {
  if (editing) {
    editForm.value = buildEditForm(props.transaction)
    nextTick(() => firstEditField.value?.focus?.())
  } else {
    editForm.value = null
  }
}, { immediate: true })

function findPayeeByName(name) {
  if (!name || !name.trim()) return null
  return props.payeeIndex.get(name.trim().toLowerCase()) || null
}

// Categorisation automatique par tiers (voir Transactions.vue pour le detail
// du raisonnement) : ne s'applique que si aucune categorie n'est deja
// choisie, sur le tiers actuellement tape dans CETTE ligne.
watch(() => editForm.value?.payee, (payeeName) => {
  if (!editForm.value || editForm.value.categoryId) return
  const payee = findPayeeByName(payeeName)
  if (payee?.categoryId) editForm.value.categoryId = payee.categoryId
})

function accountName(id) {
  return props.accountsById.get(id)?.name || '—'
}

function categoryLabel(categoryId) {
  if (!categoryId) return '—'
  const category = props.categoriesById.get(categoryId)
  if (!category) return '—'
  if (category.parentId) {
    const parent = props.categoriesById.get(category.parentId)
    if (parent) return `${parent.name} : ${category.name}`
  }
  return category.name
}

function isFuture(iso) {
  return !!iso && iso > props.todayIso
}

function confirmEdit() {
  if (!editForm.value) return
  if (!editForm.value.accountId) {
    alert('Veuillez sélectionner un compte.')
    return
  }
  emit('confirm-edit', {
    accountId: editForm.value.accountId,
    date: editForm.value.date,
    payee: editForm.value.payee,
    amount: editForm.value.amount,
    categoryId: editForm.value.categoryId,
    paymentMode: editForm.value.paymentMode,
    memo: editForm.value.memo,
    tags: editForm.value.tags,
    transferTransactionId: editForm.value.transferTransactionId,
    periodicExpense: editForm.value.periodicExpense,
    validated: editForm.value.validated
  })
}
</script>

<style scoped>
tr {
  cursor: pointer;
}
tr.row-editing {
  cursor: default;
  background: var(--ink-800);
  box-shadow: inset 3px 0 0 var(--azure);
}
tr.row-pending {
  box-shadow: inset 3px 0 0 var(--amber);
}
tr.row-editing td {
  overflow: visible;
}
tr.row-editing input,
tr.row-editing select {
  width: 100%;
}

td {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
td:nth-child(5) {
  white-space: normal;
}

/*
 * Transaction future (date > aujourd'hui) : prevue mais pas encore
 * reellement survenue, dans l'esprit YNAB. Fond attenue + texte plus clair
 * pour la distinguer immediatement des transactions deja effectuees, tout
 * en restant lisible et sans bloquer l'edition/les actions habituelles.
 */
tr.row-future td {
  background: var(--ink-800);
  color: var(--text-faint);
}
tr.row-future td.amount.negative {
  color: #C98E8E;
}
tr.row-future td.amount.positive {
  color: #8FC0A6;
}
tr.row-future:hover td {
  background: var(--ink-700);
}
.future-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--ink-700);
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .04em;
  vertical-align: middle;
}

.status-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  cursor: pointer;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
}
.status-dot.validated {
  background: var(--mint-dim);
  color: var(--mint);
}
.status-dot.pending {
  background: var(--amber-dim);
  border: 2px solid var(--amber);
}

.row-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

/*
 * La colonne Actions reste toujours visible même si le tableau déborde
 * horizontalement (beaucoup de colonnes) : on la fixe à droite plutôt que
 * de forcer l'utilisateur à scroller jusqu'au bout pour supprimer/valider
 * une ligne.
 */
td:last-child {
  max-width: none;
  overflow: visible;
  position: sticky;
  right: 0;
  background: var(--ink-900);
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
}
tr:hover td:last-child {
  background: var(--ink-800);
}
tr.row-editing td:last-child {
  background: var(--ink-800);
}
tr.row-future td:last-child {
  background: var(--ink-800);
}

.text-muted {
  color: var(--text-muted);
}
</style>

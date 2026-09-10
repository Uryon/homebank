<template>
  <tr
    :data-transaction-id="transaction.id"
    :class="{
      'row-editing': isEditing,
      'row-pending': !isEditing && !transaction.validated,
      'row-future': !isEditing && isFuture(transaction.date),
      'row-selected': !isEditing && selected
    }"
    @click="!isEditing && $emit('start-edit')"
    @keydown.esc="$emit('cancel-edit')"
    @keydown.enter.prevent="isEditing && confirmEdit()"
  >
    <!-- Mode édition -->
    <template v-if="isEditing && editForm">
      <td class="num" @click.stop>
        <input type="checkbox" :checked="selected" title="Sélectionner cette transaction" aria-label="Sélectionner cette transaction" @change="$emit('toggle-select', transaction)" />
      </td>
      <td class="num" @click.stop>
        <input type="checkbox" v-model="editForm.validated" title="Transaction validée (rapprochement bancaire)" aria-label="Transaction validée (rapprochement bancaire)" />
      </td>
      <td @click.stop>
        <input v-model="editForm.date" type="date" ref="firstEditField" aria-label="Date" />
      </td>
      <td @click.stop>
        <select v-model.number="editForm.accountId" aria-label="Compte">
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </td>
      <td @click.stop>
        <label for="tx-edit-payee" class="sr-only">Tiers</label>
        <PayeeAutocomplete id="tx-edit-payee" v-model="editForm.payee" :payees="payees" />
      </td>
      <td @click.stop>
        <label for="tx-edit-category" class="sr-only">Catégorie</label>
        <CategoryAutocomplete
          id="tx-edit-category"
          v-model="editForm.categoryId"
          :categories="flatCategories"
          :transaction-type="editAmountType"
          placeholder="Aucune"
        />
      </td>
      <td @click.stop>
        <input v-model="editForm.memo" aria-label="Mémo" placeholder="Note libre" />
      </td>
      <td class="num" colspan="2" @click.stop>
        <AmountInput v-model="editForm.amount" aria-label="Montant" style="text-align: right;" :allow-relative="false" />
      </td>
      <td class="num" @click.stop>
        <input type="checkbox" v-model="editForm.periodicExpense" title="Dépense périodique récurrente" aria-label="Dépense périodique récurrente" />
      </td>
      <td class="num" @click.stop>
        <input type="checkbox" v-model="editForm.exceptional" title="Transaction exceptionnelle : exclue des tableaux de bord (ex : achat d'une voiture)" aria-label="Transaction exceptionnelle" />
      </td>
      <td @click.stop>
        <span class="row-actions">
          <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
          <button class="btn secondary" style="padding: 6px 10px;" @click="$emit('cancel-edit')">✕</button>
        </span>
      </td>
    </template>

    <!-- Mode lecture -->
    <template v-else>
      <td class="num" @click.stop>
        <label :for="'tx-select-' + transaction.id" class="sr-only">Sélectionner cette transaction</label>
        <input :id="'tx-select-' + transaction.id" type="checkbox" :checked="selected" title="Sélectionner cette transaction" :aria-label="'Sélectionner la transaction ' + (transaction.payee || 'sans tiers') + ' du ' + formatDate(transaction.date)" @change="$emit('toggle-select', transaction)" />
      </td>
      <td class="num" @click.stop>
        <span
          class="status-dot"
          :class="transaction.validated ? 'validated' : (isFuture(transaction.date) ? 'future' : 'pending')"
          :title="transaction.validated ? 'Validée' : (isFuture(transaction.date) ? 'Prévue — n\'a pas encore eu lieu, pas de rapprochement à faire' : 'En attente de validation — cliquer pour valider')"
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
        <label :for="'tx-periodic-' + transaction.id" class="sr-only">Dépense périodique récurrente</label>
        <input
          :id="'tx-periodic-' + transaction.id"
          type="checkbox"
          :checked="transaction.periodicExpense"
          title="Dépense périodique récurrente"
          :aria-label="'Dépense périodique récurrente — ' + (transaction.payee || 'sans tiers')"
          @change="$emit('toggle-periodic', transaction)"
        />
      </td>
      <td class="num" @click.stop>
        <label :for="'tx-exceptional-' + transaction.id" class="sr-only">Transaction exceptionnelle</label>
        <input
          :id="'tx-exceptional-' + transaction.id"
          type="checkbox"
          :checked="transaction.exceptional"
          title="Transaction exceptionnelle : exclue des tableaux de bord (ex : achat d'une voiture)"
          :aria-label="'Transaction exceptionnelle — ' + (transaction.payee || 'sans tiers')"
          @change="$emit('toggle-exceptional', transaction)"
        />
      </td>
      <td @click.stop>
        <span class="row-actions">
          <button class="split-btn" title="Diviser cette transaction en deux" @click="$emit('split', transaction)">✂</button>
          <button class="delete-btn" title="Supprimer cette transaction" @click="$emit('remove', transaction)">✕</button>
        </span>
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
import { ref, computed, watch, nextTick } from 'vue'
import PayeeAutocomplete from './PayeeAutocomplete.vue'
import CategoryAutocomplete from './CategoryAutocomplete.vue'
import AmountInput from './AmountInput.vue'
import { formatAmount, formatDate, displayMemo } from '../services/format'
import { findPayeeByName } from '../services/payee'

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
  // Map (nom de tiers normalise -> tiers[]) — plusieurs tiers peuvent
  // partager le meme nom pour des montants differents, voir Tiers#amount —
  // pour la categorisation automatique par tiers pendant l'edition.
  payeeIndex: { type: Map, required: true },
  todayIso: { type: String, required: true },
  selected: { type: Boolean, default: false }
})

const emit = defineEmits([
  'start-edit',
  'cancel-edit',
  'confirm-edit',
  'toggle-validated',
  'toggle-periodic',
  'toggle-exceptional',
  'toggle-select',
  'remove',
  'split'
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
    exceptional: !!t.exceptional,
    validated: !!t.validated
  }
}

// Valeur du champ Tiers au moment ou l'edition commence (voir le watch sur
// editForm.value?.payee plus bas) : sert a distinguer un VRAI changement de
// tiers (saisi/choisi par l'utilisateur pendant l'edition) du simple echo
// reactif declenche par le remplissage initial de editForm ci-dessous — ce
// dernier ne doit jamais a lui seul ecraser une categorie deja renseignee.
let payeeAtEditStart = null

watch(() => props.isEditing, (editing) => {
  if (editing) {
    editForm.value = buildEditForm(props.transaction)
    payeeAtEditStart = editForm.value.payee
    nextTick(() => firstEditField.value?.focus?.())
  } else {
    editForm.value = null
  }
}, { immediate: true })

// Type ('EXPENSE'/'INCOME') deduit du signe du montant en cours d'edition,
// pour ne proposer dans CategoryAutocomplete que des categories du bon sens
// (jamais une categorie de revenu sur une depense ou l'inverse). Null tant
// que le montant est vide/nul : les deux groupes restent alors visibles.
const editAmountType = computed(() => {
  const amount = editForm.value?.amount
  if (amount == null || amount === 0 || amount === '') return null
  return amount < 0 ? 'EXPENSE' : 'INCOME'
})

/**
 * Categorisation automatique par tiers (voir Transactions.vue pour le detail
 * du raisonnement), sur CETTE ligne. Deux cas :
 * - categorie encore vide : rempli des l'ouverture de l'edition (ce watch se
 *   declenche aussi tout seul, par simple echo reactif, quand editForm vient
 *   d'etre rempli ci-dessus) — comportement historique, rien a perdre.
 * - categorie deja renseignee : n'est ecrasee que si le tiers a REELLEMENT
 *   change depuis l'ouverture de l'edition (voir payeeAtEditStart), jamais
 *   par le seul fait d'ouvrir la ligne — sinon rouvrir une transaction pour
 *   corriger juste la date, par exemple, ecraserait silencieusement une
 *   categorie choisie a la main. Corrige en revanche le cas d'une categorie
 *   deja mal pre-remplie par un tiers homonyme avant la desambiguisation par
 *   montant (voir findPayeeByName) : re-choisir/retaper le tiers suffit.
 */
watch(() => editForm.value?.payee, (payeeName) => {
  if (!editForm.value) return
  const payeeActuallyChanged = payeeName !== payeeAtEditStart
  if (editForm.value.categoryId && !payeeActuallyChanged) return
  const payee = findPayeeByName(props.payeeIndex, payeeName, editForm.value.amount)
  if (payee?.categoryId) editForm.value.categoryId = payee.categoryId
})

function accountName(id) {
  return props.accountsById.get(id)?.name || '—'
}

function withIcon(category) {
  return category.icon ? `${category.icon} ${category.name}` : category.name
}

function categoryLabel(categoryId) {
  if (!categoryId) return '—'
  const category = props.categoriesById.get(categoryId)
  if (!category) return '—'
  if (category.parentId) {
    const parent = props.categoriesById.get(category.parentId)
    if (parent) return `${withIcon(parent)} : ${withIcon(category)}`
  }
  return withIcon(category)
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
    exceptional: editForm.value.exceptional,
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
tr.row-selected td {
  background: var(--azure-dim);
}
tr.row-selected:hover td {
  background: var(--azure-dim);
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
/*
 * Transaction future en attente : ne reprend pas l'anneau ambre de .pending
 * (reserve aux transactions deja effectuees qui attendent un rapprochement
 * bancaire) — une transaction prevue n'a rien a rapprocher, l'ambre serait
 * trompeur. Teinte neutre alignee sur .row-future ci-dessous.
 */
.status-dot.future {
  background: var(--ink-700);
  border: 2px solid var(--ink-600);
}

/*
 * .row-actions est un <span> A L'INTERIEUR du <td>, pas sur le <td>
 * lui-meme : mettre display:flex directement sur un <td> lui fait perdre
 * son comportement de cellule de tableau (dont le centrage vertical
 * automatique dans la ligne), ce qui remontait le bouton en haut de la
 * ligne des qu'une autre colonne (ex : tiers sur plusieurs lignes) la
 * rendait plus haute que prevu. En restant un <span> inline-flex ordinaire,
 * le <td> parent garde son vertical-align:middle natif et centre le
 * contenu quelle que soit la hauteur de la ligne.
 */
.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
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
  text-align: center;
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

/*
 * Bouton de suppression : simple croix dans un cercle plutôt qu'un bouton
 * texte "Suppr." — moins imposant visuellement dans une colonne consultée
 * en permanence, tout en restant clairement reconnaissable et cliquable.
 * Teinte corail douce (même paire dim/pleine que .badge.credit_card) au
 * repos pour rester identifiable au premier coup d'œil sans être agressif
 * sur toute la hauteur du tableau ; le survol passe en corail plein avec
 * une légère élévation, comme les .btn de l'application.
 */
.delete-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border: none;
  border-radius: 50%;
  background: var(--coral-dim);
  color: var(--coral);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, box-shadow 0.15s, transform 0.1s;
}
.delete-btn:hover {
  background: var(--coral);
  color: #FFFFFF;
  box-shadow: var(--shadow-sm);
  transform: scale(1.08);
}
.delete-btn:active {
  transform: scale(0.92);
}

/* Bouton "diviser" : meme gabarit que .delete-btn (croix), teinte azur
   (action neutre) plutot que corail (reserve aux actions destructives). */
.split-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  border: none;
  border-radius: 50%;
  background: var(--azure-dim);
  color: var(--azure);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, box-shadow 0.15s, transform 0.1s;
}
.split-btn:hover {
  background: var(--azure);
  color: #FFFFFF;
  box-shadow: var(--shadow-sm);
  transform: scale(1.08);
}
.split-btn:active {
  transform: scale(0.92);
}
</style>

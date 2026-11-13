<template>
  <tr
    ref="rowRef"
    :data-transaction-id="transaction.id"
    :class="{
      'row-editing': isEditing,
      'row-pending': !isEditing && !transaction.validated,
      'row-future': !isEditing && isFuture(transaction.date),
      'row-selected': !isEditing && selected
    }"
    :tabindex="isEditing ? undefined : 0"
    :aria-label="isEditing ? undefined : rowLabel"
    @click="!isEditing && $emit('start-edit')"
    @keydown.esc="$emit('cancel-edit')"
    @keydown.enter="onEnter"
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
          <option v-for="a in editableAccounts" :key="a.id" :value="a.id">{{ accountOptionLabel(a) }}</option>
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
          <button class="btn" style="padding: 6px 9px;" title="Enregistrer (Entrée)" aria-label="Enregistrer" @click="confirmEdit"><Icon name="check" /></button>
          <button class="btn secondary" style="padding: 6px 9px;" title="Annuler (Échap)" aria-label="Annuler" @click="$emit('cancel-edit')"><Icon name="x" /></button>
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
        <button
          type="button"
          class="status-dot"
          :class="transaction.validated ? 'validated' : (isFuture(transaction.date) ? 'future' : 'pending')"
          :title="transaction.validated ? 'Validée' : (isFuture(transaction.date) ? 'Prévue — n\'a pas encore eu lieu, pas de rapprochement à faire' : 'En attente de validation — cliquer pour valider')"
          :aria-label="transaction.validated ? 'Validée — annuler la validation' : 'Valider cette transaction'"
          :aria-pressed="transaction.validated"
          @click="$emit('toggle-validated', transaction)"
        >
          <Icon v-if="transaction.validated" name="check" />
        </button>
      </td>
      <td class="col-date">
        {{ formatDate(transaction.date) }}
        <span v-if="isFuture(transaction.date)" class="future-badge" title="Transaction prévue à une date future : elle n'a pas encore réellement eu lieu et n'est pas comptée dans les statistiques.">Prévu</span>
      </td>
      <td>{{ accountName(transaction.accountId) }}</td>
      <!-- Texte complet au survol s'il est tronqué : infobulle globale (OverflowTooltip.vue) -->
      <td class="col-payee">{{ transaction.payee }}</td>
      <td class="col-cat">
        <span v-if="categoryInfo" class="cat-chip" :style="{ '--h': categoryInfo.hue }" :data-tooltip="categoryInfo.path">
          <span class="cat-mark" aria-hidden="true">{{ categoryInfo.icon }}</span>
          <span v-if="categoryInfo.parent" class="cat-parent">{{ categoryInfo.parent }} ›</span>
          <span class="cat-name">{{ categoryInfo.name }}</span>
        </span>
        <span v-else class="cat-none">Sans catégorie</span>
      </td>
      <td class="text-muted">{{ displayMemo(transaction.memo) }}</td>
      <td class="num amount negative"><Money v-if="transaction.amount < 0" :value="transaction.amount" /></td>
      <td class="num amount positive"><Money v-if="transaction.amount > 0" :value="transaction.amount" /></td>
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
          <button class="icon-btn" title="Diviser cette transaction en deux" aria-label="Diviser cette transaction" @click="$emit('split', transaction)"><Icon name="split" /></button>
          <button class="icon-btn danger" title="Supprimer cette transaction" aria-label="Supprimer cette transaction" @click="$emit('remove', transaction)"><Icon name="trash" /></button>
        </span>
      </td>
    </template>
  </tr>
</template>

<script setup>
import { notify } from '../services/feedback'
/**
 * Une ligne de la table Transactions, en composant a part entiere : c'est
 * ce qui permet a l'edition inline de rester fluide meme sur un tres grand
 * historique (des milliers de transactions).
 *
 * Avant ce composant, l'etat d'edition (editForm) vivait dans Transactions.vue :
 * chaque frappe au clavier modifiait une donnee reactive lue par LE TEMPLATE
 * PARENT (qui boucle sur toutes les transactions), donc Vue devait
 * reexecuter le rendu de la table ENTIERE (et recalculer libelles de
 * categorie/compte pour chaque ligne) a chaque caractere tape, meme si une seule
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
import Icon from './Icon.vue'
import Money from './Money.vue'
import { formatAmount, formatDate, displayMemo } from '../services/format'
import { findPayeeByName } from '../services/payee'
import { accountOptionLabel } from '../services/accounts'

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

/**
 * Comptes proposés en édition : les comptes actifs, plus le compte actuel de
 * la transaction même s'il est archivé (modifier une transaction déjà
 * présente reste permis, seul un déplacement VERS un compte archivé est
 * refusé — voir ServiceTransaction#applyPayload). Calculé seulement pour la
 * ligne en cours d'édition (le <select> n'existe que dans ce mode).
 */
const editableAccounts = computed(() =>
  props.accounts.filter(a => !a.archived || a.id === props.transaction.accountId)
)

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
const rowRef = ref(null)

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

watch(() => props.isEditing, (editing, wasEditing) => {
  if (editing) {
    editForm.value = buildEditForm(props.transaction)
    payeeAtEditStart = editForm.value.payee
    nextTick(() => firstEditField.value?.focus?.())
  } else {
    editForm.value = null
    // Uniquement à la sortie d'une édition (pas au premier affichage) : le
    // focus était dans un champ de la ligne, qui vient de disparaître ; on le
    // rend à la ligne pour qu'un utilisateur clavier reprenne là où il était.
    if (!wasEditing) return
    const focusWasInRow = rowRef.value?.contains(document.activeElement)
    nextTick(() => {
      const lost = !document.activeElement || document.activeElement === document.body
      if (focusWasInRow || lost) rowRef.value?.focus({ preventScroll: true })
    })
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

/**
 * Données d'affichage de la pastille de catégorie. La teinte (0-360) est
 * dérivée de la catégorie RACINE : une catégorie et ses sous-catégories
 * partagent la même couleur, et elle reste stable d'une session à l'autre
 * sans rien stocker en base.
 */
const categoryInfo = computed(() => {
  const id = props.transaction.categoryId
  const category = id ? props.categoriesById.get(id) : null
  if (!category) return null
  const parent = category.parentId ? props.categoriesById.get(category.parentId) : null
  const root = parent || category
  return {
    name: category.name,
    parent: parent ? parent.name : '',
    icon: root.icon || category.icon || '',
    hue: (root.id * 47) % 360,
    path: parent ? `${parent.name} › ${category.name}` : category.name
  }
})

function isFuture(iso) {
  return !!iso && iso > props.todayIso
}

/** Libellé de la ligne pour les lecteurs d'écran quand elle reçoit le focus clavier. */
const rowLabel = computed(() => {
  const t = props.transaction
  const parts = [formatDate(t.date), t.payee || 'sans tiers', formatAmount(t.amount), categoryInfo.value?.path || 'sans catégorie']
  return `${parts.join(', ')} — Entrée pour modifier`
})

/**
 * Entrée : enregistre en mode édition ; sinon ouvre l'édition, mais
 * seulement si c'est la ligne elle-même qui a le focus (pas une case à
 * cocher ou un bouton de la ligne, qui gardent leur propre comportement).
 */
function onEnter(event) {
  if (props.isEditing) {
    event.preventDefault()
    confirmEdit()
  } else if (event.target === event.currentTarget) {
    event.preventDefault()
    emit('start-edit')
  }
}

function confirmEdit() {
  if (!editForm.value) return
  if (!editForm.value.accountId) {
    notify('Veuillez sélectionner un compte.', 'warning')
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
/* Ligne atteinte au clavier (Tab) : cadre visible, à l'intérieur pour ne pas être rogné par le tableau. */
tr:focus-visible {
  outline: 2px solid var(--azure);
  outline-offset: -2px;
}
tr.row-editing {
  cursor: default;
  background: var(--ink-800);
  box-shadow: inset 3px 0 0 var(--azure);
}
tr.row-pending {
  box-shadow: inset 2px 0 0 var(--amber);
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
/* En édition, chaque pixel compte (colonnes à largeur fixe) : marges
   réduites pour que la date, le compte et le mémo restent lisibles. */
tr.row-editing td {
  padding-left: 4px;
  padding-right: 4px;
}
tr.row-editing input:not([type="checkbox"]),
tr.row-editing select {
  padding-left: 7px;
  padding-right: 6px;
  font-size: 13px;
}
tr.row-editing input[type="date"]::-webkit-calendar-picker-indicator {
  margin-left: 0;
  padding: 0;
}

td {
  padding-top: 10px;
  padding-bottom: 10px;
  /* Marges latérales resserrées : la place va au texte (tiers, catégorie). */
  padding-left: 8px;
  padding-right: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* Montants : jamais tronqués ni remplacés par « … », le montant exact doit
   toujours être lisible (la colonne est dimensionnée en conséquence). */
td.amount {
  overflow: visible;
  text-overflow: clip;
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
  color: color-mix(in srgb, var(--coral) 55%, var(--ink-800));
}
tr.row-future td.amount.positive {
  color: color-mix(in srgb, var(--mint) 55%, var(--ink-800));
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
  font-size: 11px;
  font-weight: 600;
  vertical-align: middle;
}

.status-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: inherit;
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
  background: transparent;
  border: 2px solid var(--amber);
  width: 18px;
  height: 18px;
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
  box-shadow: var(--sticky-edge-shadow);
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
/* Cellule d'actions fixe d'une ligne sélectionnée (survolée ou non) : même
   teinte que la ligne, mais OPAQUE (mélange avec la surface) pour ne pas
   laisser voir les colonnes qui défilent dessous. */
tr.row-selected td:last-child,
tr.row-selected:hover td:last-child {
  background: color-mix(in srgb, var(--azure) 14%, var(--ink-900));
}

.text-muted {
  color: var(--text-muted);
}

/*
 * Actions de ligne : boutons-icônes discrets, visibles seulement au survol
 * (ou au focus clavier, ou si la ligne est sélectionnée) pour alléger un
 * tableau consulté en permanence. Sur écran tactile (pas de survol), ils
 * restent toujours visibles.
 */
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  transition: background 0.12s, color 0.12s;
}
.icon-btn .icon { width: 16px; height: 16px; }
.icon-btn:hover { background: var(--ink-700); color: var(--text-primary); }
.icon-btn.danger:hover { background: var(--coral-dim); color: var(--coral); }
@media (hover: hover) {
  tr:not(.row-editing) .row-actions { opacity: 0; transition: opacity 0.12s; }
  tr:hover .row-actions,
  tr:focus-within .row-actions,
  tr.row-selected .row-actions { opacity: 1; }
}

/* Pastille de catégorie : teinte dérivée de la catégorie racine (--h) */
.cat-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 2px 10px 2px 6px;
  border-radius: 999px;
  background: hsl(var(--h) var(--chip-bg-saturation) var(--chip-bg-lightness));
  color: hsl(var(--h) var(--chip-text-saturation) var(--chip-text-lightness));
  font-size: 13px;
  font-weight: 500;
  vertical-align: middle;
}
.cat-mark { font-size: 13px; line-height: 1; flex-shrink: 0; }
.cat-mark:empty { display: none; }
.cat-chip:has(.cat-mark:empty) { padding-left: 10px; }
/* Faute de place, c'est le parent qui se tronque en premier (forte valeur de
   flex-shrink) : la sous-catégorie, plus précise, reste lisible. Le chemin
   complet est dans l'infobulle (title). */
.cat-parent {
  opacity: 0.65;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 2.5em;
  flex: 0 1000 auto;
}
.cat-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 0 1 auto; }
.cat-none { color: var(--text-faint); font-size: 13px; }
tr.row-future .cat-chip { opacity: 0.6; }

td.col-date { color: var(--text-muted); font-variant-numeric: tabular-nums; }
.col-payee { font-weight: 500; }
.status-dot .icon { width: 12px; height: 12px; stroke-width: 2.8; }
</style>

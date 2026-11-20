<template>
  <div class="payees-page">
    <div class="page-header">
      <div>
        <h1>Tiers</h1>
        <div class="subtitle">Cliquez sur un tiers pour le modifier — catégorisation automatique à l'import</div>
      </div>
      <div class="header-actions">
        <button
          class="btn secondary"
          :disabled="autoCategorizing"
          title="Categorise chaque tiers sans categorie dont toutes les transactions deja categorisees s'accordent sur une seule, puis applique cette categorie aux transactions de ce tiers encore sans categorie. Ne modifie jamais une categorie deja choisie."
          @click="runAutoCategorize"
        >
          {{ autoCategorizing ? 'Catégorisation…' : 'Catégoriser automatiquement' }}
        </button>
        <button class="btn" @click="openCreate"><Icon name="plus" size="sm" /> Nouveau tiers</button>
      </div>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label for="payees-search" title="Nom, motif, catégorie ou montant. Séparez plusieurs critères par point-virgule pour tous les exiger à la fois (ET), ex : Bouygues;29,99. Entourez un critère de guillemets pour une correspondance exacte, ex : &quot;CAF&quot;">Recherche</label>
        <input id="payees-search" v-model="searchQuery" class="toolbar-search" placeholder='Ex : Bouygues;29,99 ou "CAF"' />
      </div>

      <div class="form-field toolbar-action">
        <button
          class="btn"
          :class="{ secondary: !withoutCategoryOnly }"
          @click="withoutCategoryOnly = !withoutCategoryOnly"
          :title="withoutCategoryOnly ? 'Revenir à tous les tiers' : 'N\'afficher que les tiers sans catégorie associée'"
        >
          Sans catégorie ({{ withoutCategoryCount }})
        </button>
      </div>

      <div class="form-field toolbar-action">
        <button
          class="btn"
          :class="{ secondary: !unusedOnly }"
          @click="unusedOnly = !unusedOnly"
          :title="unusedOnly ? 'Revenir à tous les tiers' : 'N\'afficher que les tiers sans aucune transaction (supprimables)'"
        >
          Inutilisés ({{ unusedCount }})
        </button>
      </div>

      <div v-if="selectedIds.length >= 2" class="form-field toolbar-action">
        <button class="btn" @click="openMergeModal">
          Fusionner ({{ selectedIds.length }} sélectionnés)
        </button>
      </div>
      <div v-if="selectedIds.length > 0 && selectionIsUnused" class="form-field toolbar-action">
        <button class="btn danger" :disabled="removingSelection" @click="removeSelected">
          {{ removingSelection ? 'Suppression…' : `Supprimer (${selectedIds.length})` }}
        </button>
      </div>
      <div v-if="selectedIds.length > 0" class="form-field toolbar-action">
        <button class="btn secondary" @click="clearSelection">Annuler la sélection</button>
      </div>
    </div>

    <div v-if="autoCategorizeResult" class="auto-categorize-result">
      <span>
        {{ pluralize(autoCategorizeResult.payeesUpdated, 'tiers catégorisé', 'tiers catégorisés') }}, {{ pluralize(autoCategorizeResult.transactionsUpdated, 'transaction mise', 'transactions mises') }} à jour.
      </span>
      <button type="button" class="auto-categorize-result-close" title="Fermer" aria-label="Fermer ce message" @click="autoCategorizeResult = null"><Icon name="x" size="sm" /></button>
    </div>

    <div v-if="cleanupItems.length" class="cleanup-notice">
      <span>{{ cleanupNotice }}</span>
      <button type="button" class="btn secondary" @click="showCleanupModal = true">Voir l'aperçu</button>
    </div>

    <div class="table-scroll" v-if="filteredPayees.length">
      <table v-row-nav="'tr[data-payee-id]'" class="payees-table">
        <colgroup>
          <col style="width: 44px;" />
          <col style="width: 21%;" />
          <col style="width: 18%;" />
          <col style="width: 100px;" />
          <col style="width: 118px;" />
          <col style="width: 104px;" />
          <col />
          <col style="width: 72px;" />
        </colgroup>
        <thead>
          <tr>
            <th>
              <input
                type="checkbox"
                :checked="allFilteredSelected"
                title="Sélectionner / désélectionner tous les tiers actuellement affichés"
                aria-label="Sélectionner / désélectionner tous les tiers actuellement affichés"
                @change="toggleAllIn(filteredPayeeIds)"
              />
            </th>
            <th :aria-sort="ariaSort('name')">
              <button type="button" class="sort-header" @click="toggleSort('name')">
                Nom <Icon :name="sortIcon('name')" size="sm" />
              </button>
            </th>
            <th title="Insensible aux majuscules. Tapez juste 'Carrefour' pour reconnaître tout libellé qui le contient.">Motif de reconnaissance</th>
            <th class="num">Montant</th>
            <th class="num" :aria-sort="ariaSort('count')">
              <button type="button" class="sort-header" title="Trier par nombre de transactions" @click="toggleSort('count')">
                Transactions <Icon :name="sortIcon('count')" size="sm" />
              </button>
            </th>
            <th class="num" :aria-sort="ariaSort('lastDate')">
              <button type="button" class="sort-header" title="Trier par date de la dernière transaction" @click="toggleSort('lastDate')">
                Dernière <Icon :name="sortIcon('lastDate')" size="sm" />
              </button>
            </th>
            <th>Catégorie associée</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="p in visiblePayees"
            :key="p.id"
            :data-payee-id="p.id"
            :class="{ 'row-editing': editingId === p.id }"
            :tabindex="editingId === p.id ? undefined : 0"
            :aria-label="editingId === p.id ? undefined : `${p.name} — Entrée pour modifier`"
            @click="editingId !== p.id && startEdit(p)"
            @keydown.enter="onRowEnter($event, p)"
            @keydown.esc="cancelEdit"
          >
            <td class="cell-select" @click.stop><input type="checkbox" :value="p.id" v-model="selectedIds" :aria-label="'Sélectionner ' + p.name" /></td>

            <template v-if="editingId === p.id">
              <td class="cell-name" data-label="Nom" @click.stop>
                <input id="payee-edit-name" v-model="editForm.name" maxlength="255" ref="firstEditField" aria-label="Nom" v-bind="editInvalidAttrs('name')" @input="clearEditError('name')" />
                <FieldError id="payee-edit-name-error" :message="editErrors.name" />
              </td>
              <td class="cell-pattern" data-label="Motif de reconnaissance" @click.stop>
                <input id="payee-edit-pattern" v-model="editForm.pattern" aria-label="Motif de reconnaissance" placeholder="Ex : Carrefour" v-bind="editInvalidAttrs('pattern')" @input="clearEditError('pattern')" />
                <FieldError id="payee-edit-pattern-error" :message="editErrors.pattern" />
              </td>
              <td class="num cell-amount" data-label="Montant (vide : tous)" @click.stop>
                <AmountInput v-model="editForm.amount" aria-label="Montant" placeholder="Tous" title="Vide : le tiers est reconnu quel que soit le montant" />
              </td>
              <td class="num usage-cell">{{ usageOf(p.id).count }}</td>
              <td class="num usage-cell usage-date">{{ usageOf(p.id).lastDate ? formatDate(usageOf(p.id).lastDate) : '—' }}</td>
              <td class="cell-category" data-label="Catégorie associée" @click.stop>
                <label for="payee-edit-category" class="sr-only">Catégorie associée</label>
                <CategoryAutocomplete
                  id="payee-edit-category"
                  v-model="editForm.categoryId"
                  :categories="flatCategories"
                  placeholder="Aucune"
                />
              </td>
              <td @click.stop class="row-actions">
                <button class="btn btn-icon" title="Enregistrer (Entrée)" aria-label="Enregistrer" @click="confirmEdit"><Icon name="check" /></button>
                <button class="btn secondary btn-icon" title="Annuler (Échap)" aria-label="Annuler" @click="cancelEdit"><Icon name="x" /></button>
              </td>
            </template>
            <template v-else>
              <td class="cell-name">{{ p.name }}</td>
              <td class="cell-pattern">
                <span class="pattern-segments">
                  <code v-for="(segment, index) in patternSegmentsById.get(p.id)" :key="index">{{ segment }}</code>
                </span>
              </td>
              <td class="num cell-amount">{{ p.amount != null ? formatAmount(p.amount) : '' }}</td>
              <td class="num usage-cell" :class="{ unused: !usageOf(p.id).count }">
                <RouterLink
                  v-if="usageOf(p.id).count"
                  class="usage-link"
                  :to="{ path: '/transactions', query: { search: transactionSearchForPayee(p) } }"
                  :title="'Voir les transactions de ' + p.name"
                  @click.stop
                >
                  <span class="usage-count">{{ usageOf(p.id).count }}</span>
                  <span class="usage-unit">{{ usageUnitLabel(usageOf(p.id).count) }}</span>
                </RouterLink>
                <template v-else>
                  <span class="usage-count">0</span>
                  <span class="usage-unit">{{ usageUnitLabel(0) }}</span>
                </template>
              </td>
              <td class="num usage-cell usage-date">{{ usageOf(p.id).lastDate ? formatDate(usageOf(p.id).lastDate) : '—' }}</td>
              <td class="cell-category">
                <CategoryChip v-if="p.categoryId" :category-id="p.categoryId" :categories-by-id="categoriesById" />
                <button
                  v-else-if="suggestedCategoryId(p)"
                  type="button"
                  class="category-suggestion"
                  :disabled="applyingSuggestionId === p.id"
                  :title="'Toutes les transactions déjà catégorisées de ce tiers utilisent ' + categoryLabel(suggestedCategoryId(p)) + ' — cliquer pour appliquer cette catégorie au tiers'"
                  @click.stop="applySuggestion(p)"
                >
                  {{ applyingSuggestionId === p.id ? 'Application…' : `Suggestion : ${suggestionPath(p)}` }}
                </button>
                <template v-else>—</template>
              </td>
              <td class="row-actions" @click.stop>
                <div class="hover-actions">
                  <button
                    class="icon-btn danger"
                    :title="deleteTitle(p)"
                    :aria-label="'Supprimer ' + p.name"
                    :aria-disabled="usageOf(p.id).count ? 'true' : undefined"
                    @click="remove(p)"
                  ><Icon name="trash" /></button>
                </div>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
      <div v-if="hasMorePayees" ref="payeesSentinel" class="load-more-sentinel">Chargement…</div>
    </div>
    <div v-else-if="!payeesLoaded && loadFailed" class="empty-state">
      Tiers indisponibles tant que le serveur ne répond pas.
    </div>
    <SkeletonRows v-else-if="!payeesLoaded" label="Chargement des tiers…" :columns="['4%', '20%', '24%', '8%', '28%']" />
    <div v-else class="empty-state">
      <span>{{
        searchQuery.trim()
          ? 'Aucun tiers ne correspond à cette recherche.'
          : 'Aucun tiers enregistré. Créez-en un pour catégoriser automatiquement vos imports (ex : nom "Carrefour", motif Carrefour, catégorie "Alimentation").'
      }}</span>
      <button v-if="searchQuery.trim()" type="button" class="btn secondary" @click="searchQuery = ''">Effacer la recherche</button>
    </div>

    <PayeeCreateModal
      v-if="newPayeeDraft"
      :initial="newPayeeDraft"
      :categories="flatCategories"
      @save="save"
      @cancel="newPayeeDraft = null"
    />

    <PayeeMergeModal
      v-if="showMergeModal"
      :payees="selectedPayeeObjects"
      :confirming="merging"
      @confirm="confirmMerge"
      @cancel="showMergeModal = false"
    />

    <PayeeCleanupModal
      v-if="showCleanupModal"
      :items="cleanupItems"
      :confirming="cleaning"
      @confirm="confirmCleanup"
      @cancel="showCleanupModal = false"
    />

    <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
  </div>
</template>

<script setup>
import { notify, askConfirm } from '../services/feedback'
import { ref, onMounted, computed, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useRouteActions } from '../services/routeActions'
import { PayeesAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { categoryChipInfo, flattenCategories, transactionSearchForPayee } from '../services/categories'
import { splitPatternSegments } from '../services/payee'
import { handleRowEnter, restoreFocusToRow } from '../services/rowKeyboard'
import { nextSort, sortPayees, usageByPayeeId } from '../services/payeeUsage'
import { ariaSortOf, sortIconOf } from '../services/tableSort'
import { formatAmount, formatDate, pluralize } from '../services/format'
import { alertApiError } from '../services/errors'
import { useProgressiveList } from '../services/progressiveList'
import CategoryAutocomplete from '../components/CategoryAutocomplete.vue'
import AmountInput from '../components/AmountInput.vue'
import FieldError from '../components/FieldError.vue'
import { useFieldErrors } from '../services/fieldErrors'
import PayeeCreateModal from '../components/PayeeCreateModal.vue'
import PayeeMergeModal from '../components/PayeeMergeModal.vue'
import PayeeCleanupModal from '../components/PayeeCleanupModal.vue'
import Icon from '../components/Icon.vue'
import CategoryChip from '../components/CategoryChip.vue'
import SkeletonRows from '../components/SkeletonRows.vue'
import UndoBar from '../components/UndoBar.vue'
import { useLastAction } from '../services/lastAction'
import { useIdSelection } from '../services/selection'
import { buildPayeeSearchIndex, categoryIdsByPayeeId, filterPayees, suggestedCategoryIdFor } from '../services/payeeFilters'
import { usePayeeAutoCategorize } from '../services/payeeAutoCategorize'
import { usePayeeSelectionActions } from '../services/payeeSelectionActions'
import { usePayeeCleanup } from '../services/payeeCleanupActions'

const route = useRoute()

// Donnees issues du cache partage (voir App.vue / dataStore.js), comme sur
// Transactions.vue : cette page avait auparavant son propre chargement local
// (PayeesAPI.list()/CategoriesAPI.list() a part), deconnecte du cache
// partage — une modification faite ici (edition, fusion) ne se repercutait
// donc pas ailleurs (ex : autocomplete tiers de la page Transactions) tant
// que le cache partage n'etait pas revalide par autre chose (navigation,
// F5...).
const payees = computed(() => dataStore.state.payees)
const payeesLoaded = computed(() => dataStore.state.loaded.payees)
const loadFailed = computed(() => dataStore.state.loadFailed)
const categories = computed(() => dataStore.state.categories)
const searchQuery = ref('')
const withoutCategoryOnly = ref(false)
/** Valeurs de départ de la fenêtre « Nouveau tiers » (PayeeCreateModal.vue), null fenêtre fermée. */
const newPayeeDraft = ref(null)

const editingId = ref(null)
const editForm = ref(null)
const firstEditField = ref(null)

const { selectedIds, selectedIdSet, allSelectedIn, toggleAllIn, keepExisting, clearSelection } = useIdSelection()

const withoutCategoryCount = computed(() => payees.value.filter(p => !p.categoryId).length)

/** Filtre « Inutilisés » : tiers sans aucune transaction, les seuls supprimables (voir ServiceTiers#delete). */
const unusedOnly = ref(false)
const unusedCount = computed(() => payees.value.filter(p => !usageOf(p.id).count).length)

const flatCategories = computed(() => flattenCategories(categories.value))
const categoriesById = computed(() => new Map(categories.value.map(c => [c.id, c])))

/** Segments du motif de chaque tiers, découpés une seule fois (affichés en pastilles séparées). */
const patternSegmentsById = computed(() => new Map(payees.value.map(p => [p.id, splitPatternSegments(p.pattern)])))

// Index Map (id -> libelle deja compose "Parent : Enfant"), precalcule une
// seule fois quand `categories` change, pour eviter un scan lineaire de
// flatCategories pour chaque tiers a chaque frappe dans la recherche (voir
// filteredPayees ci-dessous, qui appelle categoryLabel pour chaque ligne).
const categoryLabelById = computed(() => new Map(flatCategories.value.map(c => [c.id, c.label])))

/** Champs de recherche normalisés par tiers, recalculés seulement quand tiers ou catégories changent (voir services/payeeFilters.js). */
const payeeSearchIndexById = computed(() => buildPayeeSearchIndex(payees.value, categoryLabel))

/** Filtres « Sans catégorie » / « Inutilisés » puis recherche (« Bouygues;29,99 », "CAF" exact). */
const filteredPayees = computed(() => filterPayees(payees.value, {
  query: searchQuery.value,
  withoutCategoryOnly: withoutCategoryOnly.value,
  unusedOnly: unusedOnly.value,
  isUnused: p => !usageOf(p.id).count,
  searchIndex: payeeSearchIndexById.value
}))

/** Chemin de la catégorie suggérée, au même format « Parent › Enfant » que les pastilles. */
function suggestionPath(payee) {
  return categoryChipInfo(suggestedCategoryId(payee), categoriesById.value)?.path || '—'
}

function categoryLabel(categoryId) {
  if (!categoryId) return '—'
  return categoryLabelById.value.get(categoryId) || '—'
}

/** Catégories des transactions déjà catégorisées de chaque tiers, indexées une seule fois (voir services/payeeFilters.js). */
const categoryIdsByPayee = computed(() => categoryIdsByPayeeId(dataStore.state.transactions))

/* --- Utilisation et tri (voir services/payeeUsage.js) --- */

/** Nombre de transactions et dernière date par tiers, recalculé seulement quand les transactions changent. */
const payeeUsage = computed(() => usageByPayeeId(dataStore.state.transactions))
const NO_USAGE = { count: 0, lastDate: '' }

function usageOf(payeeId) {
  return payeeUsage.value.get(payeeId) || NO_USAGE
}

/**
 * Info-bulle du bouton Supprimer : un tiers utilisé ne peut pas être supprimé
 * (voir remove), le bouton apparaît alors désactivé et en donne la raison.
 */
function deleteTitle(payee) {
  const usedBy = usageOf(payee.id).count
  return usedBy
    ? `Utilisé par ${pluralize(usedBy, 'transaction', 'transactions')} : fusionnez-le avec un autre tiers plutôt que de le supprimer`
    : 'Supprimer le tiers'
}

/** Unité affichée après le nombre de transactions, seulement en disposition carte (téléphone) où la colonne n'a plus d'en-tête. */
function usageUnitLabel(count) {
  return count > 1 ? 'transactions' : 'transaction'
}

/**
 * Tri choisi en cliquant sur un en-tête ({ key, dir }, null = ordre d'origine).
 * Recliquer la même colonne inverse le sens (voir nextSort).
 */
const sort = ref(null)
const displayedPayees = computed(() => sortPayees(filteredPayees.value, sort.value, payeeUsage.value))

/**
 * Affichage progressif (voir services/progressiveList.js) : les ~500 tiers
 * rendus d'un coup coutaient 30-60 ms a chaque changement de la recherche.
 */
const {
  visibleItems: visiblePayees,
  hasMore: hasMorePayees,
  sentinel: payeesSentinel,
  reset: resetVisiblePayees,
  revealIndex: revealPayeeIndex
} = useProgressiveList(displayedPayees, { pageSize: 50 })

watch([searchQuery, withoutCategoryOnly, unusedOnly, sort], resetVisiblePayees)

function toggleSort(key) {
  sort.value = nextSort(sort.value, key)
}

function ariaSort(key) {
  return ariaSortOf(sort.value, key)
}

function sortIcon(key) {
  return sortIconOf(sort.value, key)
}

/** Catégorie suggérée pour un tiers sans catégorie, null sans signal unanime (voir suggestedCategoryIdFor). */
function suggestedCategoryId(payee) {
  return suggestedCategoryIdFor(payee, categoryIdsByPayee.value, categoriesById.value)
}

const applyingSuggestionId = ref(null)

/** Applique en un clic la categorie suggeree (voir suggestedCategoryId) sans passer par le mode edition complet de la ligne. */
async function applySuggestion(payee) {
  const categoryId = suggestedCategoryId(payee)
  if (!categoryId) return
  applyingSuggestionId.value = payee.id
  try {
    const fields = { name: payee.name, pattern: payee.pattern, amount: payee.amount }
    await PayeesAPI.update(payee.id, { ...fields, categoryId })
    await refreshAfterMutation()
    setLastAction('Catégorie suggérée appliquée', async () => {
      await PayeesAPI.update(payee.id, { ...fields, categoryId: null })
      await refreshAfterMutation()
    })
  } catch (e) {
    alertApiError("Erreur lors de l'application de la catégorie suggérée", e)
  } finally {
    applyingSuggestionId.value = null
  }
}

/** Revalide le cache partage des tiers apres creation/edition/suppression/fusion, et purge la selection des tiers qui n'existent plus. */
async function refreshAfterMutation() {
  await dataStore.refreshPayees()
  keepExisting(payees.value.map(p => p.id))
}

function openCreate() {
  newPayeeDraft.value = {}
}

/** Enregistre le tiers saisi dans PayeeCreateModal.vue (champs obligatoires déjà contrôlés). */
async function save(form) {
  try {
    await PayeesAPI.create(form)
    notify('Tiers créé.', 'success')
    newPayeeDraft.value = null
    await refreshAfterMutation()
  } catch (e) {
    alertApiError('Erreur lors de la création du tiers', e)
  }
}

/** Annulation de la dernière suppression ou modification (voir services/lastAction.js). */
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

/** Version en masse de applySuggestion (voir services/payeeAutoCategorize.js). */
const { autoCategorizing, autoCategorizeResult, runAutoCategorize } =
  usePayeeAutoCategorize({ payees, refreshAfterMutation, setLastAction })

async function remove(payee) {
  // Même règle que le backend (ServiceTiers#delete) : prévenue ici pour ne
  // pas demander une confirmation qui aboutirait forcément à un refus.
  const usedBy = usageOf(payee.id).count
  if (usedBy > 0) {
    notify(`Le tiers "${payee.name}" est utilisé par ${pluralize(usedBy, 'transaction', 'transactions')} : fusionnez-le avec un autre tiers plutôt que de le supprimer.`, 'warning', 6000)
    return
  }
  if (!(await askConfirm(`Supprimer le tiers "${payee.name}" ?`))) return
  try {
    await PayeesAPI.remove(payee.id)
    await refreshAfterMutation()
    notify('Tiers supprimé.', 'success')
    // Un tiers supprimable n'a aucune transaction : le recréer à l'identique
    // (motif, montant, catégorie par défaut) suffit à revenir en arrière.
    const { name, pattern, amount, categoryId } = payee
    setLastAction('Tiers supprimé', async () => {
      await PayeesAPI.create({ name, pattern, amount, categoryId })
      await refreshAfterMutation()
    })
  } catch (e) {
    alertApiError('Erreur lors de la suppression du tiers', e)
  }
}

/* --- Édition inline --- */

/** Erreurs de l'édition en ligne, affichées sous les champs de la ligne (instance distincte de la création). */
const {
  errors: editErrors,
  check: checkEdit,
  clear: clearEditError,
  invalidAttrs: editInvalidAttrs
} = useFieldErrors({ name: 'payee-edit-name', pattern: 'payee-edit-pattern' })

function startEdit(payee) {
  clearEditError()
  editingId.value = payee.id
  editForm.value = {
    name: payee.name,
    pattern: payee.pattern,
    amount: payee.amount,
    categoryId: payee.categoryId
  }
  nextTick(() => firstEditField.value?.[0]?.focus?.())
}

function cancelEdit() {
  const id = editingId.value
  editingId.value = null
  editForm.value = null
  restoreRowFocus(id)
}

/** Entrée sur une ligne : ouvre l'édition, ou enregistre si elle est déjà en édition (voir services/rowKeyboard.js). */
function onRowEnter(event, payee) {
  handleRowEnter(event, {
    isEditing: editingId.value === payee.id,
    confirm: confirmEdit,
    startEdit: () => startEdit(payee)
  })
}

function restoreRowFocus(id) {
  if (id != null) restoreFocusToRow(`tr[data-payee-id="${id}"]`)
}

async function confirmEdit() {
  if (!editingId.value || !editForm.value) return
  const valid = checkEdit({
    name: editForm.value.name.trim() ? '' : 'Saisissez le nom du tiers.',
    pattern: editForm.value.pattern.trim() ? '' : 'Saisissez le texte à reconnaître.'
  })
  if (!valid) return
  const id = editingId.value
  const payload = { ...editForm.value }
  // Le tiers affiché sur une transaction est le nom du tiers lu par le backend
  // (voir MappeurTransaction#payeeLabel) : un renommage change donc aussi les
  // transactions déjà en cache. Motif, montant et catégorie, eux, ne
  // modifient aucune transaction existante.
  const before = payees.value.find(p => p.id === id)
  const renamed = before?.name !== payload.name.trim()
  const previous = before && { name: before.name, pattern: before.pattern, amount: before.amount, categoryId: before.categoryId }
  // editingId/editForm ne sont effaces qu'apres le succes de l'appel :
  // sinon, un echec (validation, reseau) fermait silencieusement l'edition
  // sans rien enregistrer ni l'expliquer a l'utilisateur, qui devait tout
  // retaper sans savoir pourquoi.
  try {
    await PayeesAPI.update(id, payload)
    notify('Tiers modifié.', 'success')
  } catch (e) {
    alertApiError('Erreur lors de la modification', e)
    return
  }
  editingId.value = null
  editForm.value = null
  restoreRowFocus(id)
  await Promise.all([refreshAfterMutation(), renamed && dataStore.refreshTransactions()])
  if (previous) {
    setLastAction('Tiers modifié', async () => {
      await PayeesAPI.update(id, previous)
      await Promise.all([refreshAfterMutation(), renamed && dataStore.refreshTransactions()])
    })
  }
}

/** Ids des tiers actuellement affichés (selon le filtre), pour la case « tout sélectionner ». */
const filteredPayeeIds = computed(() => filteredPayees.value.map(p => p.id))
const allFilteredSelected = computed(() => allSelectedIn(filteredPayeeIds.value))

/** Fusion et suppression groupée des tiers cochés (voir services/payeeSelectionActions.js). */
const {
  selectedPayeeObjects, selectionIsUnused, removingSelection, removeSelected,
  showMergeModal, merging, openMergeModal, confirmMerge
} = usePayeeSelectionActions({
  payees,
  selection: { selectedIds, selectedIdSet, clearSelection },
  isUnused: p => !usageOf(p.id).count,
  refreshAfterMutation,
  setLastAction
})

/** Nettoyage des faux tiers (voir ServiceNettoyageTiers et services/payeeCleanupActions.js). */
const { cleanupItems, showCleanupModal, cleaning, cleanupNotice, loadCleanupItems, confirmCleanup } =
  usePayeeCleanup({ refreshAfterMutation, setLastAction })


onMounted(async () => {
  loadCleanupItems()
  // Si le prechargement lance au demarrage de l'app est deja termine (cas
  // courant), instantane. Sinon (arrivee directe sur /payees), on attend
  // juste cette premiere fois — meme pattern que Transactions.vue.
  try {
    await dataStore.ensureLoaded()
  } catch (e) {
    alertApiError('Erreur lors du chargement des données', e)
    return
  }
  // Revalidation silencieuse en arriere-plan a chaque visite de la page, au
  // cas ou tiers/categories/transactions ont change depuis le prechargement.
  dataStore.revalidate()

  applyRouteQuery(route.query)
})

/**
 * Arrivee depuis "Transactions > À traiter > Créer une règle" ou depuis la
 * palette de commandes (Ctrl+K) :
 * - si le tiers existe deja (cas quasi systematique, il a ete cree
 *   automatiquement des la premiere transaction), on l'ouvre directement
 *   en edition (et on le fait defiler a l'ecran) ;
 * - sinon (repli), on ouvre la modal de creation pre-remplie.
 * Reappliquee a chaque changement de query : la page n'est pas remontee
 * quand on y navigue alors qu'elle est deja affichee.
 */
function applyRouteQuery(query) {
  if (query.editId) {
    const target = payees.value.find(p => p.id === Number(query.editId))
    if (!target) return
    searchQuery.value = ''
    withoutCategoryOnly.value = false
    unusedOnly.value = false
    startEdit(target)
    // Apres le watch qui remet l'affichage progressif au premier lot (filtres
    // effaces ci-dessus) : on etend alors le lot jusqu'au tiers, puis on defile.
    nextTick(async () => {
      revealPayeeIndex(displayedPayees.value.findIndex(p => p.id === target.id))
      await nextTick()
      document.querySelector(`tr[data-payee-id="${target.id}"]`)?.scrollIntoView({ block: 'center' })
    })
  } else if (query.prefillName || query.prefillPattern) {
    newPayeeDraft.value = { name: query.prefillName || '', pattern: query.prefillPattern || '' }
  }
}

watch(() => route.query, (query) => {
  if (dataStore.state.loaded.payees) applyRouteQuery(query)
})

// Actions lancées depuis la palette de commandes (Ctrl+K), voir services/routeActions.js.
useRouteActions({ 'new-payee': openCreate })
</script>

<style scoped>
.auto-categorize-result {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 10px;
  background: var(--mint-dim);
  border: 1px solid var(--mint);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: 14px;
}
.cleanup-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px 12px;
  padding: 10px 14px;
  margin-bottom: 10px;
  background: var(--amber-dim);
  border: 1px solid var(--amber);
  border-radius: var(--radius-md);
  color: var(--text-primary);
  font-size: 14px;
}
.auto-categorize-result-close {
  flex-shrink: 0;
  display: inline-flex;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 13px;
  padding: 4px;
  border-radius: 50%;
}
.auto-categorize-result-close:hover {
  color: var(--text-primary);
  background: var(--ink-800);
}

/*
 * Volontairement pas d'overflow-x ici : un position:sticky sur le thead
 * (ci-dessous) ne colle au scroll de la PAGE que si aucun ancêtre entre lui
 * et le viewport n'a d'overflow non-visible (même overflow-x seul suffit à
 * casser la stickiness, voir memoire du projet). Si le tableau est trop
 * large pour l'ecran, c'est donc toute la page qui scrolle horizontalement
 * plutot qu'un scroll confine au tableau.
 */
.table-scroll {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.payees-page .payees-table tbody tr {
  cursor: pointer;
}
/* Ligne atteinte au clavier (Tab) : cadre visible à l'intérieur de la ligne. */
.payees-page .payees-table tbody tr:focus-visible {
  outline: 2px solid var(--azure);
  outline-offset: -2px;
}
.payees-table tbody tr.row-editing {
  cursor: default;
  background: var(--ink-800);
  box-shadow: inset 3px 0 0 var(--azure);
  position: relative;
  z-index: 5;
}
.payees-table tbody tr.row-editing input,
.payees-table tbody tr.row-editing select {
  width: 100%;
}
/* Permet au menu deroulant de l'autocompletion de categorie de deborder
   de la cellule/ligne sans etre coupe. */
.payees-table tbody tr.row-editing td {
  overflow: visible;
}
/*
 * Bouton de suggestion de categorie (voir suggestedCategoryId) : bordure en
 * pointilles + teinte ambre pour bien le distinguer d'une categorie deja
 * assignee (texte simple) — signale visuellement "proposition a valider",
 * pas une valeur deja actee.
 */
.category-suggestion {
  display: inline-flex;
  align-items: center;
  border: 1px dashed var(--amber);
  background: var(--amber-dim);
  color: var(--amber);
  border-radius: var(--radius-sm);
  padding: 3px 9px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
/* Téléphone : hauteur minimale d'une zone de toucher. */
@media (max-width: 600px) {
  .category-suggestion { min-height: 24px; }
}
.category-suggestion:hover:not(:disabled) {
  background: var(--amber);
  color: var(--on-accent);
}
.category-suggestion:disabled {
  opacity: 0.6;
  cursor: default;
}

/* Pas de display:flex sur la cellule elle-même : elle cesserait d'être une
   cellule de tableau et ne s'étirerait plus à la hauteur de la ligne
   (bande décalée dans la colonne fixe à droite). */
td.row-actions {
  text-align: right;
  white-space: nowrap;
}
td.row-actions > .btn + .btn {
  margin-left: 4px;
}
/* En édition, Enregistrer + Annuler (2 × 30 px) occupent toute la colonne (72 px) */
tr.row-editing td.row-actions {
  padding-left: 4px;
  padding-right: 4px;
}
.payees-table tbody tr:has(input[type="checkbox"]:checked) {
  background: var(--azure-dim);
}

.payees-page .payees-table thead th {
  position: sticky;
  top: 0;
  z-index: 6;
}
.payees-table th:last-child,
.payees-table td:last-child {
  position: sticky;
  right: 0;
  background: var(--ink-900);
  box-shadow: var(--sticky-edge-shadow);
}
/*
 * z-index plus eleve que le thead sticky "simple" ci-dessus car cette
 * cellule cumule top:0 ET right:0 (coin haut-droit), et doit rester
 * au-dessus de tr.row-editing (z-index: 5) et des td sticky (right:0) du
 * corps du tableau pendant le scroll.
 */
.payees-table thead th:last-child {
  background: var(--ink-800);
  z-index: 7;
}
.payees-table tbody tr:hover td:last-child {
  background: var(--ink-800);
}
.payees-table tbody tr.row-editing td:last-child {
  background: var(--ink-800);
}

/* table-layout fixed : les largeurs du <colgroup> sont respectées (sinon le
   navigateur les répartit selon le contenu et le nom s'étale sur 4 lignes). */
.payees-page .payees-table { border-collapse: separate; border-spacing: 0; table-layout: fixed; width: 100%; min-width: 1000px; }
.payees-page .payees-table thead th { background: var(--table-head-bg); color: var(--table-head-text); font-size: 12.5px; font-weight: 600; text-transform: none; }
.payees-page .payees-table tbody td { padding-top: 13px; padding-bottom: 13px; border-bottom: 1px solid var(--line); }
.payees-page .payees-table tbody tr:last-child td { border-bottom: 0; }
.payees-page .payees-table tbody tr:hover { background: var(--row-hover); }
.payees-page .payees-table tbody tr.row-editing { background: var(--azure-dim); }
.payees-page code { border-radius: 5px; padding: 3px 6px; background: var(--ink-800); color: var(--text-muted); }
/* Utilisation : nombre de transactions et date de la dernière, tiers inutilisés atténués. */
.usage-cell { white-space: nowrap; font-variant-numeric: tabular-nums; }
.usage-count { font-weight: 600; color: var(--text-primary); }
/* Nombre de transactions cliquable : ouvre la liste filtrée sur ce tiers. */
.usage-link { color: inherit; text-decoration: none; }
.usage-link:hover .usage-count, .usage-link:focus-visible .usage-count { color: var(--azure); text-decoration: underline; }
.usage-date { color: var(--text-muted); font-size: 13px; }
.usage-cell.unused .usage-count { color: var(--text-faint); font-weight: 500; }

/* Un segment de motif = une pastille : jamais coupée au milieu, les segments passent à la ligne entre eux. */
.pattern-segments { display: flex; flex-wrap: wrap; gap: 4px; }
.pattern-segments code { white-space: nowrap; max-width: 100%; overflow: hidden; text-overflow: ellipsis; }
@media (max-width: 760px) { .payees-page .page-header { align-items: flex-start; flex-direction: column; } .payees-page .toolbar { align-items: stretch; } }
.usage-unit { display: none; }

/* Téléphone : chaque tiers devient une carte de deux lignes, au lieu d'un
   tableau de 1000 px où seul le nom restait visible.
     ligne 1 : sélection | nom                              | suppression
     ligne 2 :           | catégorie (ou suggestion)
     ligne 3 :           | n transactions · dernière le …
   Motif et montant restent accessibles en touchant la carte (édition, où les
   champs s'empilent avec leur libellé). Même principe que Transactions.vue. */
@media (max-width: 600px) {
  .payees-page .table-scroll { overflow: visible; }
  .payees-page .payees-table { display: block; min-width: 0; }
  .payees-table colgroup,
  .payees-table thead { display: none; }
  .payees-table tbody { display: block; }
  .payees-table tbody tr {
    display: grid;
    grid-template-columns: 34px max-content minmax(0, 1fr) auto;
    align-items: center;
    column-gap: 4px;
    border-bottom: 1px solid var(--line);
  }
  .payees-table tbody tr:last-child { border-bottom: 0; }
  .payees-page .payees-table tbody td {
    border-bottom: 0;
    padding: 6px 8px;
    text-align: left;
  }
  /* La colonne d'actions n'est plus collée au bord droit (plus de défilement horizontal). */
  .payees-table td:last-child,
  .payees-table tbody tr:hover td:last-child { position: static; box-shadow: none; background: transparent; }
  .payees-table tr:not(.row-editing) td:is(.cell-pattern, .cell-amount) { display: none; }
  .payees-table td.cell-select { grid-area: 1 / 1; }
  .payees-table td.cell-name { grid-area: 1 / 2 / 2 / 4; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .payees-table td.row-actions { grid-area: 1 / 4; padding-top: 0; padding-bottom: 0; }
  .payees-table td.cell-category { grid-area: 2 / 2 / 3 / 5; padding-top: 0; padding-bottom: 2px; min-width: 0; }
  .payees-table td.usage-cell { grid-area: 3 / 2; padding-top: 2px; padding-right: 0; font-size: 13px; }
  .payees-table td.usage-date { grid-area: 3 / 3 / 4 / 5; padding-left: 0; overflow: hidden; text-overflow: ellipsis; }
  .payees-table td.usage-date::before { content: '· dernière le '; }
  .usage-unit { display: inline; margin-left: 0.25em; color: var(--text-muted); font-weight: 400; }
  /* Suggestion trop longue : tronquée plutôt que d'élargir la carte. */
  .category-suggestion { max-width: 100%; overflow: hidden; text-overflow: ellipsis; display: inline-block; }
  .usage-cell .usage-count { font-weight: 600; }
  /* Survol absent sur téléphone : bouton de suppression toujours visible. */
  .payees-table .hover-actions .icon-btn { opacity: 1; }

  /* Édition : un champ par ligne, sur toute la largeur, avec son libellé. */
  .payees-table tbody tr.row-editing td { grid-column: 1 / -1; grid-row: auto; justify-self: stretch; max-width: none; }
  .payees-table tr.row-editing td:is(.cell-select, .usage-cell) { display: none; }
  .payees-table tr.row-editing td[data-label]::before {
    content: attr(data-label);
    display: block;
    margin-bottom: 4px;
    font-size: 12px;
    font-weight: 500;
    color: var(--text-muted);
  }
  .payees-table tr.row-editing td.row-actions { text-align: right; padding-bottom: 10px; }
}
</style>

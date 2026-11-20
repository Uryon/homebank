<template>
  <div v-row-nav="'.cat-row[data-category-id]'" class="categories-page">
    <div class="page-header">
      <div>
        <h1>Catégories</h1>
        <div class="subtitle">Cliquez sur une catégorie pour la modifier, ou glissez une sous-catégorie sur une autre catégorie pour la déplacer</div>
      </div>
      <button class="btn" @click="openCreate()"><Icon name="plus" size="sm" /> Nouvelle catégorie</button>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label for="categories-search" title="Séparez plusieurs critères par point-virgule pour tous les exiger à la fois (ET). Entourez un critère de guillemets pour une correspondance exacte, ex : &quot;CAF&quot;">Recherche</label>
        <input id="categories-search" v-model="searchQuery" class="toolbar-search" placeholder='Ex : Alimentation ou "CAF"' />
      </div>

      <div v-if="selectedIds.length >= 2" class="form-field toolbar-action">
        <button class="btn" @click="openMergeModal">
          Fusionner ({{ selectedIds.length }} sélectionnées)
        </button>
      </div>
      <div v-if="reparentableIds.length" class="form-field toolbar-action">
        <button class="btn" @click="openReparentModal">
          Changer la catégorie parente ({{ reparentableIds.length }})
        </button>
      </div>
      <div v-if="selectedIds.length > 0" class="form-field toolbar-action">
        <button class="btn secondary" @click="clearSelection">Annuler la sélection</button>
      </div>
    </div>

    <div v-if="expenseRoots.length || incomeRoots.length" class="categories-columns">
      <!-- Colonnes Dépenses et Revenus : même gabarit, voir categoryColumns -->
      <div v-for="column in categoryColumns" :key="column.key" class="card column-card">
        <div class="column-title">
          <input
            type="checkbox"
            :checked="allSelectedIn(column.visibleIds)"
            :title="column.selectAllLabel"
            :aria-label="column.selectAllLabel"
            @change="toggleAllIn(column.visibleIds)"
          />
          <span class="column-dot" :class="column.key"></span> {{ column.title }}
        </div>

        <div v-if="column.roots.length">
          <div v-for="root in column.roots" :key="column.key + '-' + root.id" class="cat-root-group">
            <!-- Ligne racine (absente si la racine elle-meme est masquee, voir hasVisibleContent).
                 Cible de drop pour deplacer une sous-categorie ici par glisser-deposer. -->
            <div
              v-if="!root.hidden"
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === root.id, 'cat-row-dropzone': dragOverRootId === root.id }"
              @click="editingId !== root.id && startEdit(root)"
              :data-category-id="root.id"
              :tabindex="editingId === root.id ? undefined : 0"
              :aria-label="editingId === root.id ? undefined : `${root.name} — Entrée pour modifier`"
              @keydown.enter="onRowEnter($event, root)"
              @keydown.esc="cancelEdit"
              @dragover="onRootDragOver($event, root)"
              @dragleave="onRootDragLeave(root)"
              @drop="onRootDrop($event, root)"
            >
              <template v-if="editingId === root.id">
                <CategoryInlineEditor
                  ref="firstEditField"
                  :form="editForm"
                  :is-child="false"
                  :roots="roots"
                  :root-type-by-id="rootTypeById"
                  :name-error="editErrors.name"
                  :name-attrs="editInvalidAttrs('name')"
                  @update-field="updateEditField"
                  @name-input="clearEditError('name')"
                  @confirm="confirmEdit"
                  @cancel="cancelEdit"
                />
              </template>
              <template v-else>
                <div class="cat-row-main">
                  <input type="checkbox" :value="root.id" v-model="selectedIds" :aria-label="'Sélectionner ' + root.name" @click.stop />
                  <strong>{{ iconPrefix(root.icon) }}{{ root.name }}</strong>
                  <!-- Type déjà donné par la colonne : badge seulement pour « Les deux » -->
                  <span v-if="root.type === 'BOTH'" class="badge" :style="typeBadgeStyle(root.type)">{{ typeLabel(root.type) }}</span>
                  <span v-if="root.annualized" class="badge badge-annual" :title="ANNUAL_BADGE_TITLE">Annuelle</span>
                </div>
                <div class="cat-actions hover-actions" @click.stop>
                  <RouterLink class="icon-btn" :to="transactionsLinkFor(root.id)" title="Voir les transactions de cette catégorie" aria-label="Voir les transactions de cette catégorie"><Icon name="transactions" /></RouterLink>
                  <button class="icon-btn" title="Ajouter une sous-catégorie" aria-label="Ajouter une sous-catégorie" @click="openCreate(root)"><Icon name="plus" /></button>
                  <button class="icon-btn" :class="{ on: root.annualized }" :aria-pressed="!!root.annualized" :title="annualToggleTitle(root)" aria-label="Catégorie annuelle" @click="toggleAnnualized(root)"><Icon name="calendar" /></button>
                  <button class="icon-btn" title="Masquer cette catégorie" aria-label="Masquer cette catégorie" @click="toggleHidden(root)"><Icon name="eyeOff" /></button>
                  <button class="icon-btn danger" title="Supprimer cette catégorie" aria-label="Supprimer cette catégorie" @click="remove(root)"><Icon name="trash" /></button>
                </div>
              </template>
            </div>

            <!-- Sous-catégories : glissables vers une autre catégorie racine pour changer leur parent -->
            <div
              v-for="child in filteredChildren(root.id)"
              :key="child.id"
              class="cat-row cat-row-child"
              :class="{ 'cat-row-editing': editingId === child.id, 'cat-row-dragging': draggedCategoryId === child.id }"
              draggable="true"
              @click="editingId !== child.id && startEdit(child)"
              :data-category-id="child.id"
              :tabindex="editingId === child.id ? undefined : 0"
              :aria-label="editingId === child.id ? undefined : `${child.name} — Entrée pour modifier`"
              @keydown.enter="onRowEnter($event, child)"
              @keydown.esc="cancelEdit"
              @dragstart="onDragStart($event, child)"
              @dragend="onDragEnd"
            >
              <template v-if="editingId === child.id">
                <CategoryInlineEditor
                  ref="firstEditField"
                  :form="editForm"
                  :is-child="true"
                  :roots="roots"
                  :root-type-by-id="rootTypeById"
                  :name-error="editErrors.name"
                  :name-attrs="editInvalidAttrs('name')"
                  @update-field="updateEditField"
                  @name-input="clearEditError('name')"
                  @confirm="confirmEdit"
                  @cancel="cancelEdit"
                />
              </template>
              <template v-else>
                <div class="cat-row-main cat-row-main-child">
                  <input type="checkbox" :value="child.id" v-model="selectedIds" :aria-label="'Sélectionner ' + child.name" @click.stop />
                  <span>{{ iconPrefix(child.icon) }}{{ child.name }}</span>
                  <span v-if="child.annualized" class="badge badge-annual" :title="ANNUAL_BADGE_TITLE">Annuelle</span>
                </div>
                <div class="cat-actions hover-actions" @click.stop>
                  <RouterLink class="icon-btn" :to="transactionsLinkFor(child.id)" title="Voir les transactions de cette catégorie" aria-label="Voir les transactions de cette catégorie"><Icon name="transactions" /></RouterLink>
                  <button class="icon-btn" :class="{ on: child.annualized }" :aria-pressed="!!child.annualized" :title="annualToggleTitle(child)" aria-label="Catégorie annuelle" @click="toggleAnnualized(child)"><Icon name="calendar" /></button>
                  <button class="icon-btn" title="Masquer cette catégorie" aria-label="Masquer cette catégorie" @click="toggleHidden(child)"><Icon name="eyeOff" /></button>
                  <button class="icon-btn danger" title="Supprimer cette catégorie" aria-label="Supprimer cette catégorie" @click="remove(child)"><Icon name="trash" /></button>
                </div>
              </template>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">{{ column.emptyLabel }}</div>
      </div>
    </div>
    <div v-else class="empty-state">
      <span>{{ searchQuery.trim() ? 'Aucune catégorie ne correspond à cette recherche.' : 'Aucune catégorie. Créez-en une pour classer vos transactions.' }}</span>
      <button v-if="searchQuery.trim()" type="button" class="btn secondary" @click="searchQuery = ''">Effacer la recherche</button>
    </div>

    <!-- Catégories masquées : encart à part sous les colonnes Dépenses/Revenus -->
    <div v-if="hiddenCategories.length" class="card hidden-categories-card">
      <div class="column-title">
        <span class="column-dot hidden"></span> Catégories masquées ({{ hiddenCategories.length }})
      </div>

      <div
        v-for="item in hiddenCategories"
        :key="'hidden-' + item.id"
        class="cat-row"
        :class="{ 'cat-row-editing': editingId === item.id }"
        @click="editingId !== item.id && startEdit(item)"
        :data-category-id="item.id"
        :tabindex="editingId === item.id ? undefined : 0"
        :aria-label="editingId === item.id ? undefined : `${item.name} (masquée) — Entrée pour modifier`"
        @keydown.enter="onRowEnter($event, item)"
        @keydown.esc="cancelEdit"
      >
        <template v-if="editingId === item.id">
          <CategoryInlineEditor
            ref="firstEditField"
            :form="editForm"
            :is-child="!!item.parentId"
            :roots="roots"
            :root-type-by-id="rootTypeById"
            :name-error="editErrors.name"
            :name-attrs="editInvalidAttrs('name')"
            @update-field="updateEditField"
            @name-input="clearEditError('name')"
            @confirm="confirmEdit"
            @cancel="cancelEdit"
          />
        </template>
        <template v-else>
          <div class="cat-row-main cat-hidden">
            <span>{{ categoryFullLabel(item) }}</span>
            <span class="badge" :style="typeBadgeStyle(item.type)">{{ typeLabel(item.type) }}</span>
            <span v-if="item.annualized" class="badge badge-annual" :title="ANNUAL_BADGE_TITLE">Annuelle</span>
          </div>
          <div class="cat-actions hover-actions" @click.stop>
            <RouterLink class="icon-btn" :to="transactionsLinkFor(item.id)" title="Voir les transactions de cette catégorie" aria-label="Voir les transactions de cette catégorie"><Icon name="transactions" /></RouterLink>
            <button class="icon-btn" :class="{ on: item.annualized }" :aria-pressed="!!item.annualized" :title="annualToggleTitle(item)" aria-label="Catégorie annuelle" @click="toggleAnnualized(item)"><Icon name="calendar" /></button>
            <button class="icon-btn" title="Réafficher cette catégorie" aria-label="Réafficher cette catégorie" @click="toggleHidden(item)"><Icon name="eye" /></button>
            <button class="icon-btn danger" title="Supprimer cette catégorie" aria-label="Supprimer cette catégorie" @click="remove(item)"><Icon name="trash" /></button>
          </div>
        </template>
      </div>
    </div>

    <CategoryCreateModal
      v-if="newCategoryParentId !== undefined"
      :roots="roots"
      :root-type-by-id="rootTypeById"
      :initial-parent-id="newCategoryParentId"
      @save="save"
      @cancel="newCategoryParentId = undefined"
    />

    <!-- Modal fusion -->
    <MergeModal
      v-if="showMergeModal"
      :title="`Fusionner ${selectedIds.length} catégories`"
      description="Choisissez la catégorie à conserver. Toutes les transactions et sous-catégories des autres catégories sélectionnées lui seront réaffectées, puis les catégories fusionnées seront supprimées. Vous pourrez l'annuler juste après avec le bouton « Annuler » (ou Ctrl+Z). (Une sous-catégorie de l'une des catégories sélectionnées ne peut pas être choisie comme cible.)"
      target-label="Catégorie à conserver"
      :items="mergeableTargets"
      :option-label="categoryFullLabel"
      v-model="mergeTargetId"
      :confirming="merging"
      @confirm="confirmMerge"
      @cancel="showMergeModal = false"
    />

    <!-- Modal changement groupé de catégorie parente -->
    <MergeModal
      v-if="showReparentModal"
      :title="`Changer la catégorie parente de ${reparentableIds.length} sous-catégories`"
      description="Toutes les sous-catégories sélectionnées seront déplacées sous la catégorie choisie ci-dessous. Leur type (dépense/revenu) s'aligne automatiquement sur celui de la nouvelle catégorie parente."
      target-label="Nouvelle catégorie parente"
      :items="roots"
      :option-label="r => iconPrefix(r.icon) + r.name"
      v-model="reparentTargetId"
      :confirming="reparenting"
      confirm-label="Changer la catégorie parente"
      confirming-label="Déplacement…"
      @confirm="confirmReparent"
      @cancel="showReparentModal = false"
    />

    <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
  </div>
</template>

<script setup>
import { notify, askConfirm } from '../services/feedback'
import { ref, onMounted, computed, nextTick } from 'vue'
import { useRouteActions } from '../services/routeActions'
import { CategoriesAPI } from '../services/api'
import { snapshotCategoryState } from '../services/categoryRestore'
import { dataStore } from '../services/dataStore'
import { parseSearchTerms } from '../services/search'
import { alertApiError } from '../services/errors'
import { categoryIconPrefix as iconPrefix, categoryTypeLabel as typeLabel, childrenByRootId, flattenCategories, transactionSearchForCategory } from '../services/categories'
import { handleRowEnter, restoreFocusToRow } from '../services/rowKeyboard'
import MergeModal from '../components/MergeModal.vue'
import CategoryCreateModal from '../components/CategoryCreateModal.vue'
import Icon from '../components/Icon.vue'
import CategoryInlineEditor from '../components/CategoryInlineEditor.vue'
import { useFieldErrors } from '../services/fieldErrors'
import { useLastAction } from '../services/lastAction'
import { useIdSelection } from '../services/selection'
import { categoryFullLabel as fullLabelOf, filterCategoryRoots, hiddenCategoriesMatching, visibleChildrenByRoot } from '../services/categoryFilters'
import { useCategoryStructureActions } from '../services/categoryStructureActions'
import UndoBar from '../components/UndoBar.vue'

/*
 * Catégories lues dans le cache partagé (voir dataStore.js), comme Tiers et
 * Transactions : une catégorie créée, renommée, masquée ou fusionnée ici est
 * aussitôt proposée (ou retirée) dans les autocomplétions des autres pages.
 */
const categories = computed(() => dataStore.state.categories)
const roots = computed(() => categories.value.filter(c => !c.parentId))
const searchQuery = ref('')
/** Fenêtre « Nouvelle catégorie » (CategoryCreateModal.vue) : parente pré-choisie (null = racine), undefined fenêtre fermée. */
const newCategoryParentId = ref(undefined)

const editingId = ref(null)
const editForm = ref(null)
const firstEditField = ref(null)

const { selectedIds, selectedIdSet, allSelectedIn, toggleAllIn, keepExisting, clearSelection } = useIdSelection()

// Index Map (parentId -> enfants), precalcule une seule fois quand
// `categories` change, pour eviter un scan lineaire de toute la liste a
// chaque appel de children() (fait plusieurs fois par racine : filteredRoots,
// filteredChildren, et a chaque frappe dans la recherche).
const childrenByParentId = computed(() => childrenByRootId(categories.value))

/** Lien vers les transactions de chaque catégorie (recherche de la page Transactions), recalculé seulement quand les catégories changent. */
const transactionsSearchById = computed(() =>
  new Map(flattenCategories(categories.value).map(entry => [entry.id, transactionSearchForCategory(entry)]))
)

function transactionsLinkFor(categoryId) {
  return { path: '/transactions', query: { search: transactionsSearchById.value.get(categoryId) ?? '' } }
}

function children(rootId) {
  return childrenByParentId.value.get(rootId) || []
}

/**
 * Plusieurs critères séparés par ";" sont tous exigés à la fois (ET) — même
 * logique que la recherche de Transactions.vue/Payees.vue, voir
 * parseSearchTerms dans services/search.js.
 */
const searchTerms = computed(() => parseSearchTerms(searchQuery.value))

const filteredRoots = computed(() => filterCategoryRoots(roots.value, searchTerms.value, children))

/**
 * Répartition en deux colonnes : Dépenses à gauche, Revenus à droite.
 * Une catégorie marquée "Les deux" apparaît dans les deux colonnes,
 * puisqu'elle s'applique aux deux contextes. Une racine masquée n'a sa
 * place ici que si au moins une de ses sous-catégories reste visible (sa
 * propre ligne restera alors masquée, voir le v-if sur .cat-row racine
 * dans le template) : sinon elle et tout son groupe basculent entierement
 * dans l'encart "Catégories masquées" plus bas.
 */
function hasVisibleContent(root) {
  return !root.hidden || children(root.id).some(child => !child.hidden)
}
const expenseRoots = computed(() =>
  filteredRoots.value.filter(r => (r.type === 'EXPENSE' || r.type === 'BOTH') && hasVisibleContent(r))
)
const incomeRoots = computed(() =>
  filteredRoots.value.filter(r => (r.type === 'INCOME' || r.type === 'BOTH') && hasVisibleContent(r))
)

/**
 * Sous-catégories visibles de chaque racine (voir visibleChildrenByRoot) :
 * calculé une fois par changement de catégories ou de recherche, et non à
 * chaque rendu pour chaque racine affichée.
 */
const visibleChildrenByRootId = computed(() => visibleChildrenByRoot(roots.value, searchTerms.value, children))

function filteredChildren(rootId) {
  return visibleChildrenByRootId.value.get(rootId) || []
}

/** Catégories masquées (racines et sous-catégories), regroupées dans un encart à part sous les colonnes Dépenses/Revenus. */
const hiddenCategories = computed(() => hiddenCategoriesMatching(categories.value, searchTerms.value, categoryById.value))

/**
 * Ids des catégories (racines + enfants) actuellement affichées dans une
 * colonne (Dépenses ou Revenus), selon le filtre de recherche.
 */
function visibleIds(roots) {
  const ids = new Set()
  for (const root of roots) {
    ids.add(root.id)
    for (const child of filteredChildren(root.id)) ids.add(child.id)
  }
  return [...ids]
}

const visibleExpenseIds = computed(() => visibleIds(expenseRoots.value))
const visibleIncomeIds = computed(() => visibleIds(incomeRoots.value))

/** Colonnes Dépenses et Revenus, rendues par le même gabarit (voir le template). */
const categoryColumns = computed(() => [
  {
    key: 'expense',
    title: 'Dépenses',
    roots: expenseRoots.value,
    visibleIds: visibleExpenseIds.value,
    selectAllLabel: 'Sélectionner / désélectionner toutes les catégories de dépenses actuellement affichées',
    emptyLabel: 'Aucune catégorie de dépense.'
  },
  {
    key: 'income',
    title: 'Revenus',
    roots: incomeRoots.value,
    visibleIds: visibleIncomeIds.value,
    selectAllLabel: 'Sélectionner / désélectionner toutes les catégories de revenus actuellement affichées',
    emptyLabel: 'Aucune catégorie de revenu.'
  }
])

const ANNUAL_BADGE_TITLE = 'Les transactions de cette catégorie sont lissées sur 12 mois dans les rapports'

/** Infobulle du bouton « catégorie annuelle », selon son état actuel. */
function annualToggleTitle(category) {
  return category.annualized
    ? 'Catégorie annuelle : lissée sur 12 mois dans les rapports (cliquer pour désactiver)'
    : 'Lisser sur 12 mois dans les rapports (ex : taxe foncière, assurance annuelle)'
}

// Index Map (id racine -> type), pour resoudre le type herite d'une
// categorie parente sans logique metier (.find()) dans le template.
const rootTypeById = computed(() => new Map(roots.value.map(r => [r.id, r.type])))

// Index Map (id -> categorie), pour eviter un scan lineaire de toutes les
// categories a chaque libelle/recherche de parent (categoryFullLabel est
// appele pour chaque ligne affichee).
const categoryById = computed(() => new Map(categories.value.map(c => [c.id, c])))

function typeBadgeStyle(type) {
  const colors = { EXPENSE: 'var(--coral)', INCOME: 'var(--mint)', BOTH: 'var(--azure)' }
  return `background: var(--ink-700); color: ${colors[type] || 'var(--text-muted)'}`
}

function categoryFullLabel(category) {
  return fullLabelOf(category, categoryById.value)
}

/**
 * Recharge le cache partagé après une modification, puis purge la sélection
 * des catégories qui n'existent plus. `also` : autres ressources modifiées
 * côté backend par l'action (tiers, transactions), rechargées en parallèle.
 */
async function load(...also) {
  await Promise.all([dataStore.refreshCategories(), ...also.map(refresh => refresh())])
  keepExisting(categories.value.map(c => c.id))
}

function openCreate(parent) {
  newCategoryParentId.value = parent?.id ?? null
}

/** Enregistre la catégorie saisie dans CategoryCreateModal.vue (nom déjà contrôlé). */
async function save(payload) {
  try {
    await CategoriesAPI.create(payload)
    notify('Catégorie créée.', 'success')
    newCategoryParentId.value = undefined
    await load()
  } catch (e) {
    alertApiError('Erreur lors de la création de la catégorie', e)
  }
}

/** Annulation de la dernière suppression ou modification (voir services/lastAction.js). */
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

/** Fusion, changement groupé de parente et glisser-déposer (voir services/categoryStructureActions.js). */
const {
  offerStateRestore,
  showMergeModal, mergeTargetId, merging, mergeableTargets, openMergeModal, confirmMerge,
  showReparentModal, reparentTargetId, reparenting, reparentableIds, openReparentModal, confirmReparent,
  draggedCategoryId, dragOverRootId, onDragStart, onDragEnd, onRootDragOver, onRootDragLeave, onRootDrop
} = useCategoryStructureActions({
  roots,
  categoryById,
  children,
  selection: { selectedIds, selectedIdSet, clearSelection },
  load,
  setLastAction
})

async function remove(category) {
  // Les sous-catégories partent avec leur parent (cascade côté backend, voir
  // ServiceCategorie#delete) : la confirmation doit le dire explicitement.
  const subCount = children(category.id).length
  let subWarning = ''
  if (subCount === 1) subWarning = ' Sa sous-catégorie sera supprimée avec elle.'
  else if (subCount > 1) subWarning = ` Ses ${subCount} sous-catégories seront supprimées avec elle.`
  if (!(await askConfirm(`Supprimer la catégorie "${category.name}" ?${subWarning}`))) return
  // Photographie prise avant suppression : sous-catégories et tiers liés
  // sont perdus côté backend une fois la catégorie supprimée.
  const before = snapshotCategoryState(dataStore.state)
  try {
    await CategoriesAPI.remove(category.id)
    // Refusée si des transactions l'utilisent ; retire en revanche la
    // catégorie par défaut des tiers concernés (voir ServiceCategorie#delete).
    await load(dataStore.refreshPayees)
    notify('Catégorie supprimée.', 'success')
    offerStateRestore('Catégorie supprimée', before)
  } catch (e) {
    alertApiError('Erreur lors de la suppression', e)
  }
}

/**
 * Masque/réaffiche une catégorie qui n'est plus utilisée : elle disparaît
 * des suggestions d'autocomplete (transactions, tiers) mais reste visible
 * ici et sur les transactions/tiers qui l'utilisent déjà.
 */
async function toggleHidden(category) {
  try {
    await CategoriesAPI.toggleHidden(category.id)
    await load()
    notify(category.hidden ? 'Catégorie réaffichée.' : 'Catégorie masquée.', 'success')
  } catch (e) {
    alertApiError('Erreur lors du changement de visibilité', e)
  }
}

/**
 * Bascule le caractere "annuel" d'une categorie : toute transaction qui lui
 * est affectee sera desormais (ou ne sera plus) lissee sur 12 mois dans les
 * rapports du tableau de bord (voir AttributionTransactions#attributedTransactions),
 * sans avoir a le cocher transaction par transaction.
 */
async function toggleAnnualized(category) {
  try {
    await CategoriesAPI.toggleAnnualized(category.id)
    await load()
    notify(category.annualized ? 'Lissage annuel désactivé.' : 'Lissage annuel activé.', 'success')
  } catch (e) {
    alertApiError('Erreur lors du changement du lissage annuel', e)
  }
}

/* --- Édition inline --- */

/**
 * Erreur de l'édition en ligne, sous le champ Nom. Le curseur est replacé via
 * la ref du champ : une catégorie « Les deux » apparaît dans les deux colonnes,
 * l'id peut donc exister deux fois pendant son édition.
 */
const {
  errors: editErrors,
  check: checkEdit,
  clear: clearEditError,
  invalidAttrs: editInvalidAttrs
} = useFieldErrors({ name: 'category-edit-name' }, { focus: () => firstEditField.value?.[0]?.focus?.() })

function startEdit(category) {
  clearEditError()
  editingId.value = category.id
  editForm.value = {
    name: category.name,
    parentId: category.parentId ?? null,
    type: category.type,
    icon: category.icon || ''
  }
  nextTick(() => firstEditField.value?.[0]?.focus?.())
}

/** Saisie d'un champ de l'édition en ligne (voir CategoryInlineEditor.vue). */
function updateEditField(key, value) {
  if (editForm.value) editForm.value[key] = value
}

function cancelEdit() {
  const id = editingId.value
  editingId.value = null
  editForm.value = null
  restoreRowFocus(id)
}

/** Entrée sur une ligne : ouvre l'édition, ou enregistre si elle est déjà en édition (voir services/rowKeyboard.js). */
function onRowEnter(event, category) {
  handleRowEnter(event, {
    isEditing: editingId.value === category.id,
    confirm: confirmEdit,
    startEdit: () => startEdit(category)
  })
}

function restoreRowFocus(id) {
  if (id != null) restoreFocusToRow(`.cat-row[data-category-id="${id}"]`)
}

async function confirmEdit() {
  if (!editingId.value || !editForm.value) return
  if (!checkEdit({ name: editForm.value.name.trim() ? '' : 'Saisissez le nom de la catégorie.' })) return
  const payload = {
    name: editForm.value.name,
    type: editForm.value.type,
    parentId: editForm.value.parentId,
    icon: editForm.value.icon || null
  }
  const id = editingId.value
  const before = categories.value.find(c => c.id === id)
  const previous = before && { name: before.name, type: before.type, parentId: before.parentId, icon: before.icon || null }
  // editingId/editForm ne sont effaces qu'apres le succes de l'appel :
  // sinon, un echec (validation, reseau) fermait silencieusement l'edition
  // sans rien enregistrer ni l'expliquer a l'utilisateur, qui devait tout
  // retaper sans savoir pourquoi.
  try {
    await CategoriesAPI.update(id, payload)
    notify('Catégorie modifiée.', 'success')
  } catch (e) {
    alertApiError('Erreur lors de la modification', e)
    return
  }
  editingId.value = null
  editForm.value = null
  restoreRowFocus(id)
  await load()
  if (previous) {
    // Le type d'origine est réappliqué à toute la descendance (voir
    // ServiceCategorie#update) : elle partageait déjà ce type avant.
    setLastAction('Catégorie modifiée', async () => {
      await CategoriesAPI.update(id, previous)
      await load()
    })
  }
}

onMounted(async () => {
  // Instantané si le préchargement de l'app est terminé (cas courant), puis
  // revalidation silencieuse — même principe que Tiers et Transactions.
  try {
    await dataStore.ensureLoaded()
  } catch (e) {
    alertApiError('Erreur lors du chargement des données', e)
    return
  }
  dataStore.revalidate()
})

// Actions lancées depuis la palette de commandes (Ctrl+K), voir services/routeActions.js.
useRouteActions({ 'new-category': () => openCreate() })
</script>

<style scoped>
.categories-page .page-header { margin-bottom: 20px; }
.categories-page .toolbar { padding: 14px 16px; background: var(--ink-900); border: 1px solid var(--line); border-radius: var(--radius-lg); margin-bottom: 20px; }
.categories-page .column-card { border: 1px solid var(--line); box-shadow: 0 2px 8px rgba(20, 30, 50, .035); }
.categories-page .column-title { font-size: 11px; letter-spacing: .075em; }
.categories-page .cat-row { min-height: 42px; padding: 5px 8px; border-radius: var(--radius-sm); transition: background .14s ease; }
.categories-page .cat-row:hover { background: var(--row-hover); }
/* Retrait des sous-catégories : même spécificité que la règle ci-dessus, sinon son padding l'annulait. */
.categories-page .cat-row-child { padding-left: 32px; }
/* Ligne atteinte au clavier (Tab) : cadre visible, et ses actions apparaissent (voir .hover-actions dans style.css). */
.categories-page .cat-row { cursor: pointer; }
.categories-page .cat-row:focus-visible { outline: 2px solid var(--azure); outline-offset: -2px; border-radius: var(--radius-sm); }
.categories-page .cat-row:has(input[type="checkbox"]:checked) { background: var(--azure-dim); }
.categories-page .hidden-categories-card { border: 1px solid var(--line); }
.categories-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  align-items: start;
}
@media (max-width: 860px) {
  .categories-columns {
    grid-template-columns: 1fr;
  }
}

.column-card {
  min-width: 0;
}

.column-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 650;
  text-transform: none;
  color: var(--text-muted);
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 2px solid var(--line);
}
.column-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}
.column-dot.expense { background: var(--coral); }
.column-dot.income { background: var(--mint); }
.column-dot.hidden { background: var(--text-faint); }

.hidden-categories-card {
  margin-top: 20px;
}

.cat-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  row-gap: 6px;
  cursor: pointer;
}

/* Boutons d'action d'une ligne (categorie/sous-categorie) : en flex-wrap
   plutot que de laisser les <button> deborder en incrementant .cat-row en
   hauteur de facon desordonnee (constate avec 4 boutons sur une ligne de
   categorie racine, voir "Marquer annuelle" ajoute recemment) — s'ils ne
   tiennent pas sur une ligne, ils repassent proprement a la ligne, alignes
   a droite. */
.cat-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}
.cat-row-editing {
  cursor: default;
  background: var(--ink-800);
  box-shadow: inset 3px 0 0 var(--azure);
  padding: 6px 8px;
}
.cat-row:has(input[type="checkbox"]:checked) {
  background: var(--azure-dim);
  border-radius: var(--radius-sm);
}
.cat-row[draggable="true"] {
  cursor: grab;
}
.cat-row-dragging {
  opacity: 0.4;
}
.cat-row-dropzone {
  background: var(--azure-dim);
  box-shadow: inset 0 0 0 2px var(--azure);
  border-radius: var(--radius-sm);
}
.cat-hidden {
  opacity: 0.55;
}
.cat-root-group { margin-bottom: 18px; }
.cat-row { border-bottom: 1px solid var(--line); }
/* Contenu d'une ligne hors édition : case, nom, badges. */
.cat-row-main {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
}
.cat-row-main-child { color: var(--text-muted); }
.badge-annual { background: var(--amber-dim); color: var(--amber); }

</style>

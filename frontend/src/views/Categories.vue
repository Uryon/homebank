<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Catégories</h1>
        <div class="subtitle">Cliquez sur une catégorie pour la modifier</div>
      </div>
      <button class="btn" @click="openCreate()">+ Nouvelle catégorie</button>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label>Recherche</label>
        <input v-model="searchQuery" placeholder="Ex : Alimentation" style="min-width: 240px;" />
      </div>

      <div v-if="selectedIds.length >= 2" class="form-field" style="align-self: flex-end;">
        <button class="btn" @click="openMergeModal">
          Fusionner ({{ selectedIds.length }} sélectionnées)
        </button>
      </div>
      <div v-if="selectedIds.length > 0" class="form-field" style="align-self: flex-end;">
        <button class="btn secondary" @click="selectedIds = []">Annuler la sélection</button>
      </div>
    </div>

    <div v-if="expenseRoots.length || incomeRoots.length" class="categories-columns">
      <!-- Colonne Dépenses -->
      <div class="card column-card">
        <div class="column-title">
          <input
            type="checkbox"
            :checked="allExpenseFilteredSelected"
            title="Sélectionner / désélectionner toutes les catégories de dépenses actuellement affichées"
            @change="toggleSelectAllExpense"
          />
          <span class="column-dot expense"></span> Dépenses
        </div>

        <div v-if="expenseRoots.length">
          <div v-for="root in expenseRoots" :key="'exp-' + root.id" style="margin-bottom: 18px;">
            <!-- Ligne racine -->
            <div
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === root.id }"
              style="border-bottom: 1px solid var(--line);"
              @click="editingId !== root.id && startEdit(root)"
              @keydown.enter.prevent="editingId === root.id && confirmEdit()"
              @keydown.esc="cancelEdit"
            >
              <template v-if="editingId === root.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.name" ref="firstEditField" placeholder="Nom" style="flex: 1;" />
                  <select v-model="editForm.type">
                    <option value="EXPENSE">Dépense</option>
                    <option value="INCOME">Revenu</option>
                    <option value="BOTH">Les deux</option>
                  </select>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
                </div>
              </template>
              <template v-else>
                <div style="display:flex; align-items:center; gap:10px; padding: 8px 0;" :class="{ 'cat-hidden': root.hidden }">
                  <input type="checkbox" :value="root.id" v-model="selectedIds" @click.stop />
                  <strong>{{ root.name }}</strong>
                  <span class="badge" :style="typeBadgeStyle(root.type)">{{ typeLabel(root.type) }}</span>
                  <span v-if="root.hidden" class="badge" style="background: var(--ink-700); color: var(--text-faint);">Masquée</span>
                </div>
                <div @click.stop>
                  <button class="btn secondary" style="margin-right:6px;" @click="openCreate(root)">+ Sous-catégorie</button>
                  <button class="btn secondary" style="margin-right:6px;" @click="toggleHidden(root)">{{ root.hidden ? 'Réafficher' : 'Masquer' }}</button>
                  <button class="btn danger" @click="remove(root)">Supprimer</button>
                </div>
              </template>
            </div>

            <!-- Sous-catégories -->
            <div
              v-for="child in filteredChildren(root.id)"
              :key="child.id"
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === child.id }"
              style="padding-left: 24px; border-bottom: 1px solid var(--line);"
              @click="editingId !== child.id && startEdit(child)"
              @keydown.enter.prevent="editingId === child.id && confirmEdit()"
              @keydown.esc="cancelEdit"
            >
              <template v-if="editingId === child.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.name" ref="firstEditField" placeholder="Nom" style="flex: 1;" />
                  <select v-model.number="editForm.parentId">
                    <option v-for="r in roots" :key="r.id" :value="r.id">{{ r.name }}</option>
                  </select>
                  <span class="text-muted" style="font-size: 12px;">
                    {{ typeLabel(rootTypeById.get(editForm.parentId)) }} (hérité du parent)
                  </span>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
                </div>
              </template>
              <template v-else>
                <div style="display:flex; align-items:center; gap:10px; padding: 8px 0; color: var(--text-muted);" :class="{ 'cat-hidden': child.hidden }">
                  <input type="checkbox" :value="child.id" v-model="selectedIds" @click.stop />
                  <span>{{ child.name }}</span>
                  <span v-if="child.hidden" class="badge" style="background: var(--ink-700); color: var(--text-faint);">Masquée</span>
                </div>
                <div @click.stop>
                  <button class="btn secondary" style="margin-right:6px;" @click="toggleHidden(child)">{{ child.hidden ? 'Réafficher' : 'Masquer' }}</button>
                  <button class="btn danger" @click="remove(child)">Supprimer</button>
                </div>
              </template>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">Aucune catégorie de dépense.</div>
      </div>

      <!-- Colonne Revenus -->
      <div class="card column-card">
        <div class="column-title">
          <input
            type="checkbox"
            :checked="allIncomeFilteredSelected"
            title="Sélectionner / désélectionner toutes les catégories de revenus actuellement affichées"
            @change="toggleSelectAllIncome"
          />
          <span class="column-dot income"></span> Revenus
        </div>

        <div v-if="incomeRoots.length">
          <div v-for="root in incomeRoots" :key="'inc-' + root.id" style="margin-bottom: 18px;">
            <!-- Ligne racine -->
            <div
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === root.id }"
              style="border-bottom: 1px solid var(--line);"
              @click="editingId !== root.id && startEdit(root)"
              @keydown.enter.prevent="editingId === root.id && confirmEdit()"
              @keydown.esc="cancelEdit"
            >
              <template v-if="editingId === root.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.name" ref="firstEditField" placeholder="Nom" style="flex: 1;" />
                  <select v-model="editForm.type">
                    <option value="EXPENSE">Dépense</option>
                    <option value="INCOME">Revenu</option>
                    <option value="BOTH">Les deux</option>
                  </select>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
                </div>
              </template>
              <template v-else>
                <div style="display:flex; align-items:center; gap:10px; padding: 8px 0;" :class="{ 'cat-hidden': root.hidden }">
                  <input type="checkbox" :value="root.id" v-model="selectedIds" @click.stop />
                  <strong>{{ root.name }}</strong>
                  <span class="badge" :style="typeBadgeStyle(root.type)">{{ typeLabel(root.type) }}</span>
                  <span v-if="root.hidden" class="badge" style="background: var(--ink-700); color: var(--text-faint);">Masquée</span>
                </div>
                <div @click.stop>
                  <button class="btn secondary" style="margin-right:6px;" @click="openCreate(root)">+ Sous-catégorie</button>
                  <button class="btn secondary" style="margin-right:6px;" @click="toggleHidden(root)">{{ root.hidden ? 'Réafficher' : 'Masquer' }}</button>
                  <button class="btn danger" @click="remove(root)">Supprimer</button>
                </div>
              </template>
            </div>

            <!-- Sous-catégories -->
            <div
              v-for="child in filteredChildren(root.id)"
              :key="child.id"
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === child.id }"
              style="padding-left: 24px; border-bottom: 1px solid var(--line);"
              @click="editingId !== child.id && startEdit(child)"
              @keydown.enter.prevent="editingId === child.id && confirmEdit()"
              @keydown.esc="cancelEdit"
            >
              <template v-if="editingId === child.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.name" ref="firstEditField" placeholder="Nom" style="flex: 1;" />
                  <select v-model.number="editForm.parentId">
                    <option v-for="r in roots" :key="r.id" :value="r.id">{{ r.name }}</option>
                  </select>
                  <span class="text-muted" style="font-size: 12px;">
                    {{ typeLabel(rootTypeById.get(editForm.parentId)) }} (hérité du parent)
                  </span>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
                </div>
              </template>
              <template v-else>
                <div style="display:flex; align-items:center; gap:10px; padding: 8px 0; color: var(--text-muted);" :class="{ 'cat-hidden': child.hidden }">
                  <input type="checkbox" :value="child.id" v-model="selectedIds" @click.stop />
                  <span>{{ child.name }}</span>
                  <span v-if="child.hidden" class="badge" style="background: var(--ink-700); color: var(--text-faint);">Masquée</span>
                </div>
                <div @click.stop>
                  <button class="btn secondary" style="margin-right:6px;" @click="toggleHidden(child)">{{ child.hidden ? 'Réafficher' : 'Masquer' }}</button>
                  <button class="btn danger" @click="remove(child)">Supprimer</button>
                </div>
              </template>
            </div>
          </div>
        </div>
        <div v-else class="empty-state">Aucune catégorie de revenu.</div>
      </div>
    </div>
    <div v-else class="empty-state">
      {{ searchQuery.trim() ? 'Aucune catégorie ne correspond à cette recherche.' : 'Aucune catégorie. Créez-en une pour classer vos transactions.' }}
    </div>

    <!-- Modal création uniquement -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>Nouvelle catégorie</h2>
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2;">
            <label>Nom</label>
            <input v-model="form.name" placeholder="Ex : Alimentation" />
          </div>
          <div class="form-field">
            <label>Catégorie parente</label>
            <select v-model.number="form.parentId">
              <option :value="null">Aucune (catégorie racine)</option>
              <option v-for="r in roots" :key="r.id" :value="r.id">{{ r.name }}</option>
            </select>
          </div>
          <div class="form-field" v-if="!form.parentId">
            <label>Type</label>
            <select v-model="form.type">
              <option value="EXPENSE">Dépense</option>
              <option value="INCOME">Revenu</option>
              <option value="BOTH">Les deux</option>
            </select>
          </div>
          <div class="form-field" v-else>
            <label>Type</label>
            <div class="text-muted" style="padding-top: 8px; font-size: 13px;">
              {{ typeLabel(rootTypeById.get(form.parentId)) }} (hérité de la catégorie parente)
            </div>
          </div>
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>

    <!-- Modal fusion -->
    <MergeModal
      v-if="showMergeModal"
      :title="`Fusionner ${selectedIds.length} catégories`"
      description="Choisissez la catégorie à conserver. Toutes les transactions et sous-catégories des autres catégories sélectionnées lui seront réaffectées, puis les catégories fusionnées seront supprimées. Cette action est irréversible."
      target-label="Catégorie à conserver"
      :items="selectedCategoryObjects"
      :option-label="categoryFullLabel"
      v-model="mergeTargetId"
      :confirming="merging"
      @confirm="confirmMerge"
      @cancel="showMergeModal = false"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { CategoriesAPI } from '../services/api'
import { normalizeSearch } from '../services/search'
import MergeModal from '../components/MergeModal.vue'

const categories = ref([])
const roots = ref([])
const showModal = ref(false)
const searchQuery = ref('')
const form = ref(emptyForm())

const editingId = ref(null)
const editForm = ref(null)
const firstEditField = ref(null)

const selectedIds = ref([])
const showMergeModal = ref(false)
const mergeTargetId = ref(null)
const merging = ref(false)

function emptyForm() {
  return { name: '', parentId: null, type: 'EXPENSE' }
}

// Index Map (parentId -> enfants), precalcule une seule fois quand
// `categories` change, pour eviter un scan lineaire de toute la liste a
// chaque appel de children() (fait plusieurs fois par racine : filteredRoots,
// filteredChildren, et a chaque frappe dans la recherche).
const childrenByParentId = computed(() => {
  const map = new Map()
  for (const c of categories.value) {
    if (!c.parentId) continue
    if (!map.has(c.parentId)) map.set(c.parentId, [])
    map.get(c.parentId).push(c)
  }
  return map
})

function children(rootId) {
  return childrenByParentId.value.get(rootId) || []
}

const filteredRoots = computed(() => {
  const q = normalizeSearch(searchQuery.value)
  if (!q) return roots.value
  return roots.value.filter(root =>
    normalizeSearch(root.name).includes(q) ||
    children(root.id).some(child => normalizeSearch(child.name).includes(q))
  )
})

/**
 * Répartition en deux colonnes : Dépenses à gauche, Revenus à droite.
 * Une catégorie marquée "Les deux" apparaît dans les deux colonnes,
 * puisqu'elle s'applique aux deux contextes.
 */
const expenseRoots = computed(() => filteredRoots.value.filter(r => r.type === 'EXPENSE' || r.type === 'BOTH'))
const incomeRoots = computed(() => filteredRoots.value.filter(r => r.type === 'INCOME' || r.type === 'BOTH'))

function filteredChildren(rootId) {
  const q = normalizeSearch(searchQuery.value)
  const kids = children(rootId)
  if (!q) return kids
  const root = roots.value.find(r => r.id === rootId)
  if (root && normalizeSearch(root.name).includes(q)) return kids
  return kids.filter(child => normalizeSearch(child.name).includes(q))
}

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

const allExpenseFilteredSelected = computed(() =>
  visibleExpenseIds.value.length > 0 && visibleExpenseIds.value.every(id => selectedIds.value.includes(id))
)
const allIncomeFilteredSelected = computed(() =>
  visibleIncomeIds.value.length > 0 && visibleIncomeIds.value.every(id => selectedIds.value.includes(id))
)

/**
 * Sélectionne, ou désélectionne, toutes les catégories actuellement
 * affichées dans une colonne, sans toucher à une éventuelle sélection
 * existante en dehors (autre colonne, ou filtre précédent).
 */
function toggleSelectAllIn(visibleIdList, allSelected) {
  const idSet = new Set(visibleIdList)
  selectedIds.value = allSelected
    ? selectedIds.value.filter(id => !idSet.has(id))
    : [...new Set([...selectedIds.value, ...idSet])]
}

function toggleSelectAllExpense() {
  toggleSelectAllIn(visibleExpenseIds.value, allExpenseFilteredSelected.value)
}

function toggleSelectAllIncome() {
  toggleSelectAllIn(visibleIncomeIds.value, allIncomeFilteredSelected.value)
}

function typeLabel(type) {
  return { EXPENSE: 'Dépense', INCOME: 'Revenu', BOTH: 'Les deux' }[type] || type
}

// Index Map (id racine -> type), pour resoudre le type herite d'une
// categorie parente sans logique metier (.find()) dans le template.
const rootTypeById = computed(() => new Map(roots.value.map(r => [r.id, r.type])))

function typeBadgeStyle(type) {
  const colors = { EXPENSE: 'var(--coral)', INCOME: 'var(--mint)', BOTH: 'var(--azure)' }
  return `background: var(--ink-700); color: ${colors[type] || 'var(--text-muted)'}`
}

function categoryFullLabel(category) {
  if (!category.parentId) return category.name
  const parent = categories.value.find(c => c.id === category.parentId)
  return parent ? `${parent.name} : ${category.name}` : category.name
}

async function load() {
  categories.value = await CategoriesAPI.list()
  roots.value = categories.value.filter(c => !c.parentId)
  const existingIds = new Set(categories.value.map(c => c.id))
  selectedIds.value = selectedIds.value.filter(id => existingIds.has(id))
}

function openCreate(parent) {
  form.value = emptyForm()
  if (parent) form.value.parentId = parent.id
  showModal.value = true
}

async function save() {
  if (!form.value.name.trim()) {
    alert('Le nom de la catégorie est obligatoire.')
    return
  }
  const payload = {
    name: form.value.name,
    type: form.value.type,
    parentId: form.value.parentId
  }
  await CategoriesAPI.create(payload)
  showModal.value = false
  await load()
}

async function remove(category) {
  if (!confirm(`Supprimer la catégorie "${category.name}" ?`)) return
  await CategoriesAPI.remove(category.id)
  await load()
}

/**
 * Masque/réaffiche une catégorie qui n'est plus utilisée : elle disparaît
 * des suggestions d'autocomplete (transactions, tiers) mais reste visible
 * ici et sur les transactions/tiers qui l'utilisent déjà.
 */
async function toggleHidden(category) {
  await CategoriesAPI.toggleHidden(category.id)
  await load()
}

/* --- Édition inline --- */

function startEdit(category) {
  editingId.value = category.id
  editForm.value = {
    name: category.name,
    parentId: category.parentId ?? null,
    type: category.type
  }
  nextTick(() => firstEditField.value?.[0]?.focus?.())
}

function cancelEdit() {
  editingId.value = null
  editForm.value = null
}

async function confirmEdit() {
  if (!editingId.value || !editForm.value) return
  if (!editForm.value.name.trim()) {
    alert('Le nom de la catégorie est obligatoire.')
    return
  }
  const payload = {
    name: editForm.value.name,
    type: editForm.value.type,
    parentId: editForm.value.parentId
  }
  const id = editingId.value
  editingId.value = null
  editForm.value = null

  await CategoriesAPI.update(id, payload)
  await load()
}

/* --- Fusion --- */

const selectedCategoryObjects = computed(() =>
  categories.value.filter(c => selectedIds.value.includes(c.id))
)

function openMergeModal() {
  mergeTargetId.value = selectedIds.value[0]
  showMergeModal.value = true
}

async function confirmMerge() {
  if (!mergeTargetId.value) return
  merging.value = true
  try {
    const sourceIds = selectedIds.value.filter(id => id !== mergeTargetId.value)
    await CategoriesAPI.merge(sourceIds, mergeTargetId.value)
    showMergeModal.value = false
    selectedIds.value = []
    await load()
  } catch (e) {
    alert('Erreur lors de la fusion : ' + (e.response?.data?.error || e.message))
  } finally {
    merging.value = false
  }
}

onMounted(load)
</script>

<style scoped>
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
  font-size: 13px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.05em;
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

.cat-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
}
.cat-row-editing {
  cursor: default;
  background: var(--ink-800);
  box-shadow: inset 3px 0 0 var(--azure);
  padding: 6px 8px;
}
.cat-row-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 4px 0;
}
.cat-row:has(input[type="checkbox"]:checked) {
  background: var(--azure-dim);
  border-radius: var(--radius-sm);
}
.text-muted {
  color: var(--text-muted);
}
.cat-hidden {
  opacity: 0.55;
}
</style>

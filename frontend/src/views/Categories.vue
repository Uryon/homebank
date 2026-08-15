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

    <div v-if="filteredRoots.length" class="card">
      <div v-for="root in filteredRoots" :key="root.id" style="margin-bottom: 18px;">
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
            <div style="display:flex; align-items:center; gap:10px; padding: 8px 0;">
              <input type="checkbox" :value="root.id" v-model="selectedIds" @click.stop />
              <strong>{{ root.name }}</strong>
              <span class="badge" :style="typeBadgeStyle(root.type)">{{ typeLabel(root.type) }}</span>
            </div>
            <div @click.stop>
              <button class="btn secondary" style="margin-right:6px;" @click="openCreate(root)">+ Sous-catégorie</button>
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
            <div style="display:flex; align-items:center; gap:10px; padding: 8px 0; color: var(--text-muted);">
              <input type="checkbox" :value="child.id" v-model="selectedIds" @click.stop />
              <span>↳ {{ child.name }}</span>
            </div>
            <div @click.stop>
              <button class="btn danger" @click="remove(child)">Supprimer</button>
            </div>
          </template>
        </div>
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
          <div class="form-field">
            <label>Type</label>
            <select v-model="form.type">
              <option value="EXPENSE">Dépense</option>
              <option value="INCOME">Revenu</option>
              <option value="BOTH">Les deux</option>
            </select>
          </div>
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>

    <!-- Modal fusion -->
    <div v-if="showMergeModal" class="modal-overlay" @click.self="showMergeModal = false">
      <div class="modal">
        <h2>Fusionner {{ selectedIds.length }} catégories</h2>
        <p style="color: var(--text-muted); font-size: 14px; margin-bottom: 16px;">
          Choisissez la catégorie à conserver. Toutes les transactions et sous-catégories
          des autres catégories sélectionnées lui seront réaffectées, puis les catégories
          fusionnées seront supprimées. Cette action est irréversible.
        </p>
        <div class="form-field">
          <label>Catégorie à conserver</label>
          <select v-model.number="mergeTargetId">
            <option v-for="c in selectedCategoryObjects" :key="c.id" :value="c.id">
              {{ categoryFullLabel(c) }}
            </option>
          </select>
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showMergeModal = false">Annuler</button>
          <button class="btn" :disabled="merging" @click="confirmMerge">
            {{ merging ? 'Fusion en cours…' : 'Confirmer la fusion' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { CategoriesAPI } from '../services/api'
import { normalizeSearch } from '../services/search'

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

function children(rootId) {
  return categories.value.filter(c => c.parent?.id === rootId)
}

const filteredRoots = computed(() => {
  const q = normalizeSearch(searchQuery.value)
  if (!q) return roots.value
  return roots.value.filter(root =>
    normalizeSearch(root.name).includes(q) ||
    children(root.id).some(child => normalizeSearch(child.name).includes(q))
  )
})

function filteredChildren(rootId) {
  const q = normalizeSearch(searchQuery.value)
  const kids = children(rootId)
  if (!q) return kids
  const root = roots.value.find(r => r.id === rootId)
  if (root && normalizeSearch(root.name).includes(q)) return kids
  return kids.filter(child => normalizeSearch(child.name).includes(q))
}

function typeLabel(type) {
  return { EXPENSE: 'Dépense', INCOME: 'Revenu', BOTH: 'Les deux' }[type] || type
}

function typeBadgeStyle(type) {
  const colors = { EXPENSE: 'var(--coral)', INCOME: 'var(--mint)', BOTH: 'var(--azure)' }
  return `background: var(--ink-700); color: ${colors[type] || 'var(--text-muted)'}`
}

function categoryFullLabel(category) {
  return category.parent ? `${category.parent.name} : ${category.name}` : category.name
}

async function load() {
  categories.value = await CategoriesAPI.list()
  roots.value = categories.value.filter(c => !c.parent)
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
    parent: form.value.parentId ? { id: form.value.parentId } : null
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

/* --- Édition inline --- */

function startEdit(category) {
  editingId.value = category.id
  editForm.value = {
    name: category.name,
    parentId: category.parent?.id ?? null,
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
    parent: editForm.value.parentId ? { id: editForm.value.parentId } : null
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
</style>

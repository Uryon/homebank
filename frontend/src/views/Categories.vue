<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Catégories</h1>
        <div class="subtitle">Organisez vos catégories et sous-catégories</div>
      </div>
      <button class="btn" @click="openCreate">+ Nouvelle catégorie</button>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label>Recherche</label>
        <input v-model="searchQuery" placeholder="Ex : Alimentation" style="min-width: 240px;" />
      </div>
    </div>

    <div v-if="filteredRoots.length" class="card">
      <div v-for="root in filteredRoots" :key="root.id" style="margin-bottom: 18px;">
        <div style="display:flex; align-items:center; justify-content:space-between; padding: 8px 0; border-bottom: 1px solid var(--line);">
          <div style="display:flex; align-items:center; gap:10px;">
            <strong>{{ root.name }}</strong>
            <span class="badge" :style="typeBadgeStyle(root.type)">{{ typeLabel(root.type) }}</span>
          </div>
          <div>
            <button class="btn secondary" style="margin-right:6px;" @click="openCreate(root)">+ Sous-catégorie</button>
            <button class="btn secondary" style="margin-right:6px;" @click="openEdit(root)">Modifier</button>
            <button class="btn danger" @click="remove(root)">Supprimer</button>
          </div>
        </div>
        <div v-for="child in filteredChildren(root.id)" :key="child.id"
             style="display:flex; align-items:center; justify-content:space-between; padding: 8px 0 8px 24px; border-bottom: 1px solid var(--line);">
          <div style="display:flex; align-items:center; gap:10px; color: var(--text-muted);">
            <span>↳ {{ child.name }}</span>
          </div>
          <div>
            <button class="btn secondary" style="margin-right:6px;" @click="openEdit(child)">Modifier</button>
            <button class="btn danger" @click="remove(child)">Supprimer</button>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="empty-state">
      {{ searchQuery.trim() ? 'Aucune catégorie ne correspond à cette recherche.' : 'Aucune catégorie. Créez-en une pour classer vos transactions.' }}
    </div>

    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? 'Modifier la catégorie' : 'Nouvelle catégorie' }}</h2>
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2;">
            <label>Nom</label>
            <input v-model="form.name" placeholder="Ex : Alimentation" />
          </div>
          <div class="form-field">
            <label>Catégorie parente</label>
            <select v-model.number="form.parentId">
              <option :value="null">Aucune (catégorie racine)</option>
              <option v-for="r in roots" :key="r.id" :value="r.id" :disabled="editing && editing.id === r.id">{{ r.name }}</option>
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
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { CategoriesAPI } from '../services/api'

const categories = ref([])
const roots = ref([])
const showModal = ref(false)
const editing = ref(null)
const searchQuery = ref('')
const form = ref(emptyForm())

function emptyForm() {
  return { name: '', parentId: null, type: 'EXPENSE' }
}

function children(rootId) {
  return categories.value.filter(c => c.parent?.id === rootId)
}

/**
 * Catégories racines à afficher : celles qui correspondent elles-mêmes
 * à la recherche, ou qui ont au moins une sous-catégorie correspondante.
 */
const filteredRoots = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return roots.value
  return roots.value.filter(root =>
    root.name.toLowerCase().includes(q) ||
    children(root.id).some(child => child.name.toLowerCase().includes(q))
  )
})

/**
 * Sous-catégories à afficher sous une racine donnée : si la racine
 * elle-même correspond à la recherche, on affiche toutes ses sous-catégories ;
 * sinon on ne garde que celles qui correspondent.
 */
function filteredChildren(rootId) {
  const q = searchQuery.value.trim().toLowerCase()
  const kids = children(rootId)
  if (!q) return kids
  const root = roots.value.find(r => r.id === rootId)
  if (root && root.name.toLowerCase().includes(q)) return kids
  return kids.filter(child => child.name.toLowerCase().includes(q))
}

function typeLabel(type) {
  return { EXPENSE: 'Dépense', INCOME: 'Revenu', BOTH: 'Les deux' }[type] || type
}

function typeBadgeStyle(type) {
  const colors = { EXPENSE: 'var(--coral)', INCOME: 'var(--mint)', BOTH: 'var(--azure)' }
  return `background: var(--ink-700); color: ${colors[type] || 'var(--text-muted)'}`
}

async function load() {
  categories.value = await CategoriesAPI.list()
  roots.value = categories.value.filter(c => !c.parent)
}

function openCreate(parent) {
  editing.value = null
  form.value = emptyForm()
  if (parent) form.value.parentId = parent.id
  showModal.value = true
}

function openEdit(category) {
  editing.value = category
  form.value = { name: category.name, parentId: category.parent?.id ?? null, type: category.type }
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
  if (editing.value) {
    await CategoriesAPI.update(editing.value.id, payload)
  } else {
    await CategoriesAPI.create(payload)
  }
  showModal.value = false
  await load()
}

async function remove(category) {
  if (!confirm(`Supprimer la catégorie "${category.name}" ?`)) return
  await CategoriesAPI.remove(category.id)
  await load()
}

onMounted(load)
</script>

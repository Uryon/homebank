<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Tiers</h1>
        <div class="subtitle">Catégorisation automatique par reconnaissance (regexp) lors de l'import</div>
      </div>
      <div style="display:flex; gap:10px;">
        <button class="btn secondary" :disabled="initializing" @click="initializeFromTransactions">
          {{ initializing ? 'Initialisation…' : 'Initialiser depuis les transactions existantes' }}
        </button>
        <button class="btn" @click="openCreate">+ Nouveau tiers</button>
      </div>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label>Recherche</label>
        <input v-model="searchQuery" placeholder="Ex : Carrefour ou Alimentation" style="min-width: 240px;" />
      </div>
    </div>

    <table v-if="filteredPayees.length">
      <thead>
        <tr>
          <th>Nom</th>
          <th>Regexp de reconnaissance</th>
          <th class="num">Montant</th>
          <th>Catégorie associée</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in filteredPayees" :key="p.id">
          <td>{{ p.name }}</td>
          <td><code>{{ p.pattern }}</code></td>
          <td class="num">{{ p.amount != null ? formatAmount(p.amount) : 'Tous montants' }}</td>
          <td>{{ categoryLabel(p.categoryId) }}</td>
          <td>
            <button class="btn secondary" style="margin-right: 6px;" @click="openEdit(p)">Modifier</button>
            <button class="btn danger" @click="remove(p)">Supprimer</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty-state">
      {{
        searchQuery.trim()
          ? 'Aucun tiers ne correspond à cette recherche.'
          : 'Aucun tiers enregistré. Créez-en un pour catégoriser automatiquement vos imports (ex : nom "Carrefour", regexp (?i)carrefour, catégorie "Alimentation").'
      }}
    </div>

    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? 'Modifier le tiers' : 'Nouveau tiers' }}</h2>
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2;">
            <label>Nom du tiers</label>
            <input v-model="form.name" placeholder="Ex : Carrefour" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Regexp de reconnaissance (testée sur le champ "tiers" importé)</label>
            <input v-model="form.pattern" placeholder="Ex : (?i)carrefour|market" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Montant associé (laisser vide = s'applique à tous les montants)</label>
            <input v-model.number="form.amount" type="number" step="0.01" min="0" placeholder="Ex : 10 (pour distinguer de 20€, etc.)" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Catégorie à appliquer automatiquement</label>
            <select v-model.number="form.categoryId">
              <option :value="null">Aucune (pas de catégorisation auto)</option>
              <option v-for="c in flatCategories" :key="c.id" :value="c.id">{{ c.label }}</option>
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
import { PayeesAPI, CategoriesAPI } from '../services/api'

const payees = ref([])
const categories = ref([])
const showModal = ref(false)
const editing = ref(null)
const initializing = ref(false)
const searchQuery = ref('')
const form = ref(emptyForm())

function emptyForm() {
  return { name: '', pattern: '', amount: null, categoryId: null }
}

/**
 * Filtre les tiers par nom, regexp, ou libellé de catégorie associée.
 */
const filteredPayees = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return payees.value
  return payees.value.filter(p =>
    p.name.toLowerCase().includes(q) ||
    p.pattern.toLowerCase().includes(q) ||
    categoryLabel(p.categoryId).toLowerCase().includes(q)
  )
})

const flatCategories = computed(() => {
  const roots = categories.value.filter(c => !c.parentId)
  const result = []
  for (const root of roots) {
    result.push({ id: root.id, label: root.name })
    const children = categories.value.filter(c => c.parentId === root.id)
    for (const child of children) {
      result.push({ id: child.id, label: `${root.name} : ${child.name}` })
    }
  }
  return result
})

function categoryLabel(categoryId) {
  if (!categoryId) return '—'
  return flatCategories.value.find(c => c.id === categoryId)?.label || '—'
}

function formatAmount(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v || 0)
}

async function load() {
  payees.value = await PayeesAPI.list()
}

async function loadRefs() {
  categories.value = await CategoriesAPI.list()
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(payee) {
  editing.value = payee
  form.value = { name: payee.name, pattern: payee.pattern, amount: payee.amount, categoryId: payee.categoryId }
  showModal.value = true
}

async function save() {
  if (!form.value.name.trim() || !form.value.pattern.trim()) {
    alert('Le nom et la regexp sont obligatoires.')
    return
  }
  try {
    new RegExp(form.value.pattern)
  } catch (e) {
    alert('Cette regexp est invalide : ' + e.message)
    return
  }
  if (editing.value) {
    await PayeesAPI.update(editing.value.id, form.value)
  } else {
    await PayeesAPI.create(form.value)
  }
  showModal.value = false
  await load()
}

async function remove(payee) {
  if (!confirm(`Supprimer le tiers "${payee.name}" ?`)) return
  await PayeesAPI.remove(payee.id)
  await load()
}

async function initializeFromTransactions() {
  initializing.value = true
  try {
    const result = await PayeesAPI.initializeFromTransactions()
    alert(`${result.created} tiers créé(s), ${result.alreadyKnown} déjà connu(s).`)
    await load()
  } catch (e) {
    alert("Erreur lors de l'initialisation : " + (e.response?.data?.error || e.message))
  } finally {
    initializing.value = false
  }
}

onMounted(async () => {
  await loadRefs()
  await load()
})
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Tiers</h1>
        <div class="subtitle">Cliquez sur un tiers pour le modifier — catégorisation automatique à l'import</div>
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

      <div v-if="selectedIds.length >= 2" class="form-field" style="align-self: flex-end;">
        <button class="btn" @click="openMergeModal">
          Fusionner ({{ selectedIds.length }} sélectionnés)
        </button>
      </div>
      <div v-if="selectedIds.length > 0" class="form-field" style="align-self: flex-end;">
        <button class="btn secondary" @click="selectedIds = []">Annuler la sélection</button>
      </div>
    </div>

    <div class="table-scroll" v-if="filteredPayees.length">
      <table class="payees-table">
        <colgroup>
          <col style="width: 36px;" />
          <col style="width: 400px;" />
          <col style="width: 160px;" />
          <col style="width: 130px;" />
          <col style="width: 220px;" />
          <col style="width: 90px;" />
        </colgroup>
        <thead>
          <tr>
            <th></th>
            <th>Nom</th>
            <th title="Insensible aux majuscules. Tapez juste 'Carrefour' pour reconnaître tout libellé qui le contient.">Motif de reconnaissance</th>
            <th class="num">Montant</th>
            <th>Catégorie associée</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="p in filteredPayees"
            :key="p.id"
            :class="{ 'row-editing': editingId === p.id }"
            @click="editingId !== p.id && startEdit(p)"
            @keydown.enter.prevent="editingId === p.id && confirmEdit()"
            @keydown.esc="cancelEdit"
          >
            <td @click.stop><input type="checkbox" :value="p.id" v-model="selectedIds" /></td>

            <template v-if="editingId === p.id">
              <td @click.stop><input v-model="editForm.name" ref="firstEditField" /></td>
              <td @click.stop><input v-model="editForm.pattern" placeholder="Ex : Carrefour" /></td>
              <td class="num" @click.stop>
                <input v-model.number="editForm.amount" type="number" step="0.01" style="text-align:right;" placeholder="Tous montants" />
              </td>
              <td @click.stop>
                <select v-model.number="editForm.categoryId">
                  <option :value="null">Aucune</option>
                  <option v-for="c in flatCategories" :key="c.id" :value="c.id">{{ c.label }}</option>
                </select>
              </td>
              <td @click.stop class="row-actions">
                <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
              </td>
            </template>
            <template v-else>
              <td>{{ p.name }}</td>
              <td><code>{{ p.pattern }}</code></td>
              <td class="num">{{ p.amount != null ? formatAmount(p.amount) : '' }}</td>
              <td>{{ categoryLabel(p.categoryId) }}</td>
              <td class="row-actions" @click.stop>
                <button class="btn danger" @click="remove(p)">Suppr.</button>
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="empty-state">
      {{
        searchQuery.trim()
          ? 'Aucun tiers ne correspond à cette recherche.'
          : 'Aucun tiers enregistré. Créez-en un pour catégoriser automatiquement vos imports (ex : nom "Carrefour", motif Carrefour, catégorie "Alimentation").'
      }}
    </div>

    <!-- Modal création uniquement -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>Nouveau tiers</h2>
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2;">
            <label>Nom du tiers</label>
            <input v-model="form.name" placeholder="Ex : Carrefour" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Motif de reconnaissance</label>
            <input v-model="form.pattern" placeholder='Ex : Carrefour' />
            <div class="text-hint">
              Tapez simplement le texte à repérer (ex : <code>Carrefour</code>) : il sera
              reconnu partout où il apparaît dans le libellé de la transaction, quelle que
              soit la casse (CARREFOUR, Carrefour, carrefour...). Pas besoin d'ajouter d'étoiles,
              c'est fait automatiquement. Pour des cas plus précis, vous pouvez tout de même
              utiliser <code>*</code> comme joker (ex : <code>Carrefour*Paris</code>).
            </div>
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Montant associé (laisser vide = s'applique à tous les montants)</label>
            <input v-model.number="form.amount" type="number" step="0.01" min="0" placeholder="Ex : 10" />
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

    <!-- Modal fusion -->
    <div v-if="showMergeModal" class="modal-overlay" @click.self="showMergeModal = false">
      <div class="modal">
        <h2>Fusionner {{ selectedIds.length }} tiers</h2>
        <p style="color: var(--text-muted); font-size: 14px; margin-bottom: 16px;">
          Choisissez le tiers à conserver. Toutes les transactions des autres tiers
          sélectionnés lui seront réaffectées (elles porteront désormais son nom),
          puis les tiers fusionnés seront supprimés. Cette action est irréversible.
        </p>
        <div class="form-field">
          <label>Tiers à conserver</label>
          <select v-model.number="mergeTargetId">
            <option v-for="p in selectedPayeeObjects" :key="p.id" :value="p.id">
              {{ p.name }}{{ p.amount != null ? ' — ' + formatAmount(p.amount) : '' }}
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
import { PayeesAPI, CategoriesAPI } from '../services/api'
import { normalizeSearch } from '../services/search'

const payees = ref([])
const categories = ref([])
const showModal = ref(false)
const initializing = ref(false)
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
  return { name: '', pattern: '', amount: null, categoryId: null }
}

const filteredPayees = computed(() => {
  const q = normalizeSearch(searchQuery.value)
  if (!q) return payees.value
  return payees.value.filter(p =>
    normalizeSearch(p.name).includes(q) ||
    normalizeSearch(p.pattern).includes(q) ||
    normalizeSearch(categoryLabel(p.categoryId)).includes(q)
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
  const existingIds = new Set(payees.value.map(p => p.id))
  selectedIds.value = selectedIds.value.filter(id => existingIds.has(id))
}

async function loadRefs() {
  categories.value = await CategoriesAPI.list()
}

function openCreate() {
  form.value = emptyForm()
  showModal.value = true
}

async function save() {
  if (!form.value.name.trim() || !form.value.pattern.trim()) {
    alert('Le nom et le motif sont obligatoires.')
    return
  }
  await PayeesAPI.create(form.value)
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

/* --- Édition inline --- */

function startEdit(payee) {
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
  editingId.value = null
  editForm.value = null
}

async function confirmEdit() {
  if (!editingId.value || !editForm.value) return
  if (!editForm.value.name.trim() || !editForm.value.pattern.trim()) {
    alert('Le nom et le motif sont obligatoires.')
    return
  }
  const id = editingId.value
  const payload = { ...editForm.value }
  editingId.value = null
  editForm.value = null

  await PayeesAPI.update(id, payload)
  await load()
}

/* --- Fusion --- */

const selectedPayeeObjects = computed(() =>
  payees.value.filter(p => selectedIds.value.includes(p.id))
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
    await PayeesAPI.merge(sourceIds, mergeTargetId.value)
    showMergeModal.value = false
    selectedIds.value = []
    await load()
  } catch (e) {
    alert('Erreur lors de la fusion : ' + (e.response?.data?.error || e.message))
  } finally {
    merging.value = false
  }
}

onMounted(async () => {
  await loadRefs()
  await load()
})
</script>

<style scoped>
.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.payees-table tbody tr {
  cursor: pointer;
}
.payees-table tbody tr.row-editing {
  cursor: default;
  background: var(--ink-800);
  box-shadow: inset 3px 0 0 var(--azure);
}
.payees-table tbody tr.row-editing input,
.payees-table tbody tr.row-editing select {
  width: 100%;
}
.row-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}
.payees-table tbody tr:has(input[type="checkbox"]:checked) {
  background: var(--azure-dim);
}

.payees-table th:last-child,
.payees-table td:last-child {
  position: sticky;
  right: 0;
  background: var(--ink-900);
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
}
.payees-table thead th:last-child {
  background: var(--ink-800);
  z-index: 2;
}
.payees-table tbody tr:hover td:last-child {
  background: var(--ink-800);
}
.payees-table tbody tr.row-editing td:last-child {
  background: var(--ink-800);
}
.text-hint {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 4px;
  line-height: 1.4;
}
.text-hint code {
  background: var(--ink-800);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: var(--font-mono);
}
</style>

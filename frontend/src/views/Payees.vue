<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Tiers</h1>
        <div class="subtitle">Cliquez sur un tiers pour le modifier — catégorisation automatique à l'import</div>
      </div>
      <div style="display:flex; gap:10px;">
        <button
          class="btn secondary"
          :disabled="autoCategorizing"
          title="Categorise chaque tiers sans categorie dont toutes les transactions deja categorisees s'accordent sur une seule, puis applique cette categorie aux transactions de ce tiers encore sans categorie. Ne modifie jamais une categorie deja choisie."
          @click="runAutoCategorize"
        >
          {{ autoCategorizing ? 'Catégorisation…' : 'Catégoriser automatiquement' }}
        </button>
        <button class="btn" @click="openCreate">+ Nouveau tiers</button>
      </div>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label>Recherche</label>
        <input v-model="searchQuery" placeholder="Ex : Carrefour ou Alimentation" style="min-width: 240px;" />
      </div>

      <div class="form-field" style="align-self: flex-end;">
        <button
          class="btn"
          :class="{ secondary: !withoutCategoryOnly }"
          @click="withoutCategoryOnly = !withoutCategoryOnly"
          :title="withoutCategoryOnly ? 'Revenir à tous les tiers' : 'N\'afficher que les tiers sans catégorie associée'"
        >
          Sans catégorie ({{ withoutCategoryCount }})
        </button>
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

    <div v-if="autoCategorizeResult" class="auto-categorize-result">
      <span>
        {{ autoCategorizeResult.payeesUpdated }} tiers catégorisé(s), {{ autoCategorizeResult.transactionsUpdated }} transaction(s) mise(s) à jour.
      </span>
      <button type="button" class="auto-categorize-result-close" @click="autoCategorizeResult = null">✕</button>
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
            <th>
              <input
                type="checkbox"
                :checked="allFilteredSelected"
                title="Sélectionner / désélectionner tous les tiers actuellement affichés"
                @change="toggleSelectAllFiltered"
              />
            </th>
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
                <AmountInput v-model="editForm.amount" style="text-align:right;" placeholder="Tous montants" />
              </td>
              <td @click.stop>
                <CategoryAutocomplete
                  v-model="editForm.categoryId"
                  :categories="flatCategories"
                  placeholder="Aucune"
                />
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
              <td>
                <template v-if="p.categoryId">{{ categoryLabel(p.categoryId) }}</template>
                <button
                  v-else-if="suggestedCategoryId(p)"
                  type="button"
                  class="category-suggestion"
                  :disabled="applyingSuggestionId === p.id"
                  :title="'Toutes les transactions déjà catégorisées de ce tiers utilisent ' + categoryLabel(suggestedCategoryId(p)) + ' — cliquer pour appliquer cette catégorie au tiers'"
                  @click.stop="applySuggestion(p)"
                >
                  {{ applyingSuggestionId === p.id ? 'Application…' : `Suggestion : ${categoryLabel(suggestedCategoryId(p))}` }}
                </button>
                <template v-else>—</template>
              </td>
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
            <AmountInput v-model="form.amount" placeholder="Ex : 10" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Catégorie associée (appliquée automatiquement aux transactions de ce tiers)</label>
            <CategoryAutocomplete
              v-model="form.categoryId"
              :categories="flatCategories"
              placeholder="Rechercher une catégorie… (ex : ali)"
            />
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
      :title="`Fusionner ${selectedIds.length} tiers`"
      description="Choisissez le tiers à conserver. Toutes les transactions des autres tiers sélectionnés lui seront réaffectées (elles porteront désormais son nom), puis les tiers fusionnés seront supprimés. Cette action est irréversible."
      target-label="Tiers à conserver"
      :items="selectedPayeeObjects"
      :option-label="payeeOptionLabel"
      v-model="mergeTargetId"
      :confirming="merging"
      @confirm="confirmMerge"
      @cancel="showMergeModal = false"
    >
      <div class="form-field">
        <label>Motifs actuels des tiers sélectionnés</label>
        <ul class="merge-patterns-list">
          <li v-for="p in selectedPayeeObjects" :key="p.id">
            <strong>{{ p.name }}</strong> : <code>{{ p.pattern }}</code>
          </li>
        </ul>
      </div>

      <div class="form-field">
        <label>Motif de reconnaissance final</label>
        <input v-model="mergePattern" placeholder="Ex : Carrefour" />
        <div class="text-hint">
          Pré-rempli avec le motif du tiers conservé, ci-dessus pour référence.
          Modifiez-le comme vous le souhaitez : les motifs des tiers fusionnés ne
          sont plus ajoutés automatiquement.
        </div>
      </div>
    </MergeModal>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { PayeesAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { normalizeSearch } from '../services/search'
import { flattenCategories } from '../services/categories'
import { formatAmount } from '../services/format'
import CategoryAutocomplete from '../components/CategoryAutocomplete.vue'
import AmountInput from '../components/AmountInput.vue'
import MergeModal from '../components/MergeModal.vue'

const route = useRoute()

// Donnees issues du cache partage (voir App.vue / dataStore.js), comme sur
// Transactions.vue : cette page avait auparavant son propre chargement local
// (PayeesAPI.list()/CategoriesAPI.list() a part), deconnecte du cache
// partage — une modification faite ici (edition, fusion) ne se repercutait
// donc pas ailleurs (ex : autocomplete tiers de la page Transactions) tant
// que le cache partage n'etait pas revalide par autre chose (navigation,
// F5...).
const payees = computed(() => dataStore.state.payees)
const categories = computed(() => dataStore.state.categories)
const showModal = ref(false)
const searchQuery = ref('')
const withoutCategoryOnly = ref(false)
const form = ref(emptyForm())

const autoCategorizing = ref(false)
const autoCategorizeResult = ref(null)

const editingId = ref(null)
const editForm = ref(null)
const firstEditField = ref(null)

const selectedIds = ref([])
const showMergeModal = ref(false)
const mergeTargetId = ref(null)
const mergePattern = ref('')
const merging = ref(false)

function emptyForm() {
  return { name: '', pattern: '', amount: null, categoryId: null }
}

const withoutCategoryCount = computed(() => payees.value.filter(p => !p.categoryId).length)

const filteredPayees = computed(() => {
  const q = normalizeSearch(searchQuery.value)
  const base = withoutCategoryOnly.value ? payees.value.filter(p => !p.categoryId) : payees.value
  if (!q) return base
  return base.filter(p =>
    normalizeSearch(p.name).includes(q) ||
    normalizeSearch(p.pattern).includes(q) ||
    normalizeSearch(categoryLabel(p.categoryId)).includes(q)
  )
})

const flatCategories = computed(() => flattenCategories(categories.value))

// Index Map (id -> libelle deja compose "Parent : Enfant"), precalcule une
// seule fois quand `categories` change, pour eviter un scan lineaire de
// flatCategories pour chaque tiers a chaque frappe dans la recherche (voir
// filteredPayees ci-dessous, qui appelle categoryLabel pour chaque ligne).
const categoryLabelById = computed(() => new Map(flatCategories.value.map(c => [c.id, c.label])))

function categoryLabel(categoryId) {
  if (!categoryId) return '—'
  return categoryLabelById.value.get(categoryId) || '—'
}

/**
 * Categorie(s) distincte(s) utilisee(s) par les transactions DEJA
 * categorisees de chaque tiers (indexees une seule fois par tiers, pas a
 * chaque ligne affichee — meme principe que categoryLabelById ci-dessus) :
 * sert a suggerer une categorie pour un tiers qui n'en a pas encore, quand
 * toutes ses transactions categorisees s'accordent sur UNE seule categorie.
 * Les transactions non categorisees du meme tiers n'entrent pas en compte
 * (ni pour, ni contre) : seul un signal consistant compte.
 */
const categoryIdsByPayeeId = computed(() => {
  const map = new Map()
  for (const t of dataStore.state.transactions) {
    if (!t.payeeId || t.categoryId == null) continue
    let set = map.get(t.payeeId)
    if (!set) {
      set = new Set()
      map.set(t.payeeId, set)
    }
    set.add(t.categoryId)
  }
  return map
})

/** Categorie suggeree pour un tiers sans categorie, ou null si aucun signal exploitable (aucune transaction categorisee, ou categories divergentes). */
function suggestedCategoryId(payee) {
  if (payee.categoryId) return null
  const ids = categoryIdsByPayeeId.value.get(payee.id)
  if (!ids || ids.size !== 1) return null
  return ids.values().next().value
}

const applyingSuggestionId = ref(null)

/** Applique en un clic la categorie suggeree (voir suggestedCategoryId) sans passer par le mode edition complet de la ligne. */
async function applySuggestion(payee) {
  const categoryId = suggestedCategoryId(payee)
  if (!categoryId) return
  applyingSuggestionId.value = payee.id
  try {
    await PayeesAPI.update(payee.id, {
      name: payee.name,
      pattern: payee.pattern,
      amount: payee.amount,
      categoryId
    })
    await refreshAfterMutation()
  } finally {
    applyingSuggestionId.value = null
  }
}

/** Revalide le cache partage des tiers apres creation/edition/suppression/fusion, et purge la selection des tiers qui n'existent plus. */
async function refreshAfterMutation() {
  await dataStore.refreshPayees()
  const existingIds = new Set(payees.value.map(p => p.id))
  selectedIds.value = selectedIds.value.filter(id => existingIds.has(id))
}

/**
 * Version en masse de applySuggestion ci-dessus (voir ServiceTiers#autoCategorize
 * cote backend) : categorise en une fois tous les tiers eligibles, et
 * propage la categorie ainsi determinee aux transactions de ces tiers
 * encore sans categorie. Revalide aussi le cache des transactions (pas
 * seulement celui des tiers), puisque des transactions peuvent avoir change.
 */
async function runAutoCategorize() {
  autoCategorizing.value = true
  autoCategorizeResult.value = null
  try {
    autoCategorizeResult.value = await PayeesAPI.autoCategorize()
    await Promise.all([dataStore.refreshPayees(), dataStore.refreshTransactions()])
  } finally {
    autoCategorizing.value = false
  }
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
  await refreshAfterMutation()
}

async function remove(payee) {
  if (!confirm(`Supprimer le tiers "${payee.name}" ?`)) return
  await PayeesAPI.remove(payee.id)
  await refreshAfterMutation()
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
  await refreshAfterMutation()
}

/** Vrai si tous les tiers actuellement affichés (selon le filtre) sont sélectionnés. */
const allFilteredSelected = computed(() =>
  filteredPayees.value.length > 0 && filteredPayees.value.every(p => selectedIds.value.includes(p.id))
)

/**
 * Case à cocher d'en-tête : sélectionne, ou désélectionne, tous les tiers
 * actuellement affichés (selon le filtre), sans toucher à une éventuelle
 * sélection existante en dehors du filtre courant.
 */
function toggleSelectAllFiltered() {
  const filteredIds = new Set(filteredPayees.value.map(p => p.id))
  selectedIds.value = allFilteredSelected.value
    ? selectedIds.value.filter(id => !filteredIds.has(id))
    : [...new Set([...selectedIds.value, ...filteredIds])]
}

/* --- Fusion --- */

const selectedPayeeObjects = computed(() =>
  payees.value.filter(p => selectedIds.value.includes(p.id))
)

function payeeOptionLabel(p) {
  return p.name + (p.amount != null ? ' — ' + formatAmount(p.amount) : '')
}

function openMergeModal() {
  mergeTargetId.value = selectedIds.value[0]
  mergePattern.value = selectedPayeeObjects.value.find(p => p.id === mergeTargetId.value)?.pattern || ''
  showMergeModal.value = true
}

// Motif pré-rempli avec celui du tiers conservé à chaque changement de
// cible, pour que l'utilisateur parte d'une base cohérente qu'il peut
// ensuite librement modifier (voir motifs affichés au-dessus pour référence).
watch(mergeTargetId, (id) => {
  mergePattern.value = selectedPayeeObjects.value.find(p => p.id === id)?.pattern || ''
})

async function confirmMerge() {
  if (!mergeTargetId.value) return
  if (!mergePattern.value.trim()) {
    alert('Le motif de reconnaissance final est obligatoire.')
    return
  }
  merging.value = true
  try {
    const sourceIds = selectedIds.value.filter(id => id !== mergeTargetId.value)
    await PayeesAPI.merge(sourceIds, mergeTargetId.value, mergePattern.value.trim())
    showMergeModal.value = false
    selectedIds.value = []
    // La fusion reaffecte aussi le tiers de chaque transaction des tiers
    // source (voir ServiceTiers#merge cote backend) : revalider aussi le
    // cache des transactions, pas seulement celui des tiers.
    await Promise.all([dataStore.refreshPayees(), dataStore.refreshTransactions()])
  } catch (e) {
    alert('Erreur lors de la fusion : ' + (e.response?.data?.error || e.message))
  } finally {
    merging.value = false
  }
}

onMounted(async () => {
  // Si le prechargement lance au demarrage de l'app est deja termine (cas
  // courant), instantane. Sinon (arrivee directe sur /payees), on attend
  // juste cette premiere fois — meme pattern que Transactions.vue.
  await dataStore.ensureLoaded()
  // Revalidation silencieuse en arriere-plan a chaque visite de la page, au
  // cas ou tiers/categories/transactions ont change depuis le prechargement.
  dataStore.refreshAll()

  // Arrivee depuis "Transactions > À traiter > Créer une règle" :
  // - si le tiers existe deja (cas quasi systematique, il a ete cree
  //   automatiquement des la premiere transaction), on l'ouvre directement
  //   en edition pour lui affecter une categorie ;
  // - sinon (repli), on ouvre la modal de creation pre-remplie.
  if (route.query.editId) {
    const target = payees.value.find(p => p.id === Number(route.query.editId))
    if (target) startEdit(target)
  } else if (route.query.prefillName || route.query.prefillPattern) {
    form.value = emptyForm()
    form.value.name = route.query.prefillName || ''
    form.value.pattern = route.query.prefillPattern || ''
    showModal.value = true
  }
})
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
.auto-categorize-result-close {
  flex-shrink: 0;
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
.payees-table tbody tr {
  cursor: pointer;
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
.category-suggestion:hover:not(:disabled) {
  background: var(--amber);
  color: #FFFFFF;
}
.category-suggestion:disabled {
  opacity: 0.6;
  cursor: default;
}

.row-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}
.payees-table tbody tr:has(input[type="checkbox"]:checked) {
  background: var(--azure-dim);
}

.payees-table thead th {
  position: sticky;
  top: 0;
  z-index: 6;
}
.payees-table th:last-child,
.payees-table td:last-child {
  position: sticky;
  right: 0;
  background: var(--ink-900);
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
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
.merge-patterns-list {
  margin: 0;
  padding-left: 18px;
  max-height: 160px;
  overflow-y: auto;
  font-size: 13px;
}
.merge-patterns-list li {
  margin: 2px 0;
}
.merge-patterns-list code {
  background: var(--ink-800);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: var(--font-mono);
}
</style>

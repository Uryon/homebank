<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Catégories</h1>
        <div class="subtitle">Cliquez sur une catégorie pour la modifier, ou glissez une sous-catégorie sur une autre catégorie pour la déplacer</div>
      </div>
      <button class="btn" @click="openCreate()">+ Nouvelle catégorie</button>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label for="categories-search" title="Séparez plusieurs critères par point-virgule pour tous les exiger à la fois (ET). Entourez un critère de guillemets pour une correspondance exacte, ex : &quot;CAF&quot;">Recherche</label>
        <input id="categories-search" v-model="searchQuery" placeholder='Ex : Alimentation ou "CAF"' style="min-width: 240px;" />
      </div>

      <div v-if="selectedIds.length >= 2" class="form-field" style="align-self: flex-end;">
        <button class="btn" @click="openMergeModal">
          Fusionner ({{ selectedIds.length }} sélectionnées)
        </button>
      </div>
      <div v-if="reparentableIds.length" class="form-field" style="align-self: flex-end;">
        <button class="btn" @click="openReparentModal">
          Changer la catégorie parente ({{ reparentableIds.length }})
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
            aria-label="Sélectionner / désélectionner toutes les catégories de dépenses actuellement affichées"
            @change="toggleSelectAllExpense"
          />
          <span class="column-dot expense"></span> Dépenses
        </div>

        <div v-if="expenseRoots.length">
          <div v-for="root in expenseRoots" :key="'exp-' + root.id" style="margin-bottom: 18px;">
            <!-- Ligne racine (absente si la racine elle-meme est masquee, voir hasVisibleContent).
                 Cible de drop pour deplacer une sous-categorie ici par glisser-deposer. -->
            <div
              v-if="!root.hidden"
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === root.id, 'cat-row-dropzone': dragOverRootId === root.id }"
              style="border-bottom: 1px solid var(--line);"
              @click="editingId !== root.id && startEdit(root)"
              @keydown.enter.prevent="editingId === root.id && confirmEdit()"
              @keydown.esc="cancelEdit"
              @dragover="onRootDragOver($event, root)"
              @dragleave="onRootDragLeave(root)"
              @drop="onRootDrop($event, root)"
            >
              <template v-if="editingId === root.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.icon" aria-label="Icône" placeholder="🙂" maxlength="4" class="icon-field" />
                  <input v-model="editForm.name" ref="firstEditField" aria-label="Nom" placeholder="Nom" style="flex: 1;" />
                  <select v-model="editForm.type" aria-label="Type">
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
                  <input type="checkbox" :value="root.id" v-model="selectedIds" :aria-label="'Sélectionner ' + root.name" @click.stop />
                  <strong>{{ iconPrefix(root.icon) }}{{ root.name }}</strong>
                  <span class="badge" :style="typeBadgeStyle(root.type)">{{ typeLabel(root.type) }}</span>
                  <span v-if="root.annualized" class="badge" style="background: var(--amber-dim); color: var(--amber);" title="Les transactions de cette catégorie sont lissées sur 12 mois dans les rapports">Annuelle</span>
                </div>
                <div class="cat-actions" @click.stop>
                  <button class="btn secondary cat-btn" @click="openCreate(root)">+ Sous-catégorie</button>
                  <button class="btn secondary cat-btn" @click="toggleAnnualized(root)" title="Lisser les transactions de cette catégorie sur 12 mois dans les rapports (ex : taxe foncière, assurance annuelle)">{{ root.annualized ? 'Annuelle ✓' : 'Annuelle' }}</button>
                  <button class="btn secondary cat-btn" @click="toggleHidden(root)">Masquer</button>
                  <button class="btn danger cat-btn" @click="remove(root)">Supprimer</button>
                </div>
              </template>
            </div>

            <!-- Sous-catégories : glissables vers une autre catégorie racine pour changer leur parent -->
            <div
              v-for="child in filteredChildren(root.id)"
              :key="child.id"
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === child.id, 'cat-row-dragging': draggedCategoryId === child.id }"
              style="padding-left: 24px; border-bottom: 1px solid var(--line);"
              draggable="true"
              @click="editingId !== child.id && startEdit(child)"
              @keydown.enter.prevent="editingId === child.id && confirmEdit()"
              @keydown.esc="cancelEdit"
              @dragstart="onDragStart($event, child)"
              @dragend="onDragEnd"
            >
              <template v-if="editingId === child.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.icon" aria-label="Icône" placeholder="🙂" maxlength="4" class="icon-field" />
                  <input v-model="editForm.name" ref="firstEditField" aria-label="Nom" placeholder="Nom" style="flex: 1;" />
                  <select v-model.number="editForm.parentId" aria-label="Catégorie parente">
                    <option v-for="r in roots" :key="r.id" :value="r.id">{{ iconPrefix(r.icon) }}{{ r.name }}</option>
                  </select>
                  <span class="text-muted" style="font-size: 12px;">
                    {{ typeLabel(rootTypeById.get(editForm.parentId)) }} (hérité du parent)
                  </span>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
                </div>
              </template>
              <template v-else>
                <div style="display:flex; align-items:center; gap:10px; padding: 8px 0; color: var(--text-muted);">
                  <input type="checkbox" :value="child.id" v-model="selectedIds" :aria-label="'Sélectionner ' + child.name" @click.stop />
                  <span>{{ iconPrefix(child.icon) }}{{ child.name }}</span>
                  <span v-if="child.annualized" class="badge" style="background: var(--amber-dim); color: var(--amber);" title="Les transactions de cette catégorie sont lissées sur 12 mois dans les rapports">Annuelle</span>
                </div>
                <div class="cat-actions" @click.stop>
                  <button class="btn secondary cat-btn" @click="toggleAnnualized(child)" title="Lisser les transactions de cette catégorie sur 12 mois dans les rapports (ex : taxe foncière, assurance annuelle)">{{ child.annualized ? 'Annuelle ✓' : 'Annuelle' }}</button>
                  <button class="btn secondary cat-btn" @click="toggleHidden(child)">Masquer</button>
                  <button class="btn danger cat-btn" @click="remove(child)">Supprimer</button>
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
            aria-label="Sélectionner / désélectionner toutes les catégories de revenus actuellement affichées"
            @change="toggleSelectAllIncome"
          />
          <span class="column-dot income"></span> Revenus
        </div>

        <div v-if="incomeRoots.length">
          <div v-for="root in incomeRoots" :key="'inc-' + root.id" style="margin-bottom: 18px;">
            <!-- Ligne racine (absente si la racine elle-meme est masquee, voir hasVisibleContent).
                 Cible de drop pour deplacer une sous-categorie ici par glisser-deposer. -->
            <div
              v-if="!root.hidden"
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === root.id, 'cat-row-dropzone': dragOverRootId === root.id }"
              style="border-bottom: 1px solid var(--line);"
              @click="editingId !== root.id && startEdit(root)"
              @keydown.enter.prevent="editingId === root.id && confirmEdit()"
              @keydown.esc="cancelEdit"
              @dragover="onRootDragOver($event, root)"
              @dragleave="onRootDragLeave(root)"
              @drop="onRootDrop($event, root)"
            >
              <template v-if="editingId === root.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.icon" aria-label="Icône" placeholder="🙂" maxlength="4" class="icon-field" />
                  <input v-model="editForm.name" ref="firstEditField" aria-label="Nom" placeholder="Nom" style="flex: 1;" />
                  <select v-model="editForm.type" aria-label="Type">
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
                  <input type="checkbox" :value="root.id" v-model="selectedIds" :aria-label="'Sélectionner ' + root.name" @click.stop />
                  <strong>{{ iconPrefix(root.icon) }}{{ root.name }}</strong>
                  <span class="badge" :style="typeBadgeStyle(root.type)">{{ typeLabel(root.type) }}</span>
                  <span v-if="root.annualized" class="badge" style="background: var(--amber-dim); color: var(--amber);" title="Les transactions de cette catégorie sont lissées sur 12 mois dans les rapports">Annuelle</span>
                </div>
                <div class="cat-actions" @click.stop>
                  <button class="btn secondary cat-btn" @click="openCreate(root)">+ Sous-catégorie</button>
                  <button class="btn secondary cat-btn" @click="toggleAnnualized(root)" title="Lisser les transactions de cette catégorie sur 12 mois dans les rapports (ex : taxe foncière, assurance annuelle)">{{ root.annualized ? 'Annuelle ✓' : 'Annuelle' }}</button>
                  <button class="btn secondary cat-btn" @click="toggleHidden(root)">Masquer</button>
                  <button class="btn danger cat-btn" @click="remove(root)">Supprimer</button>
                </div>
              </template>
            </div>

            <!-- Sous-catégories : glissables vers une autre catégorie racine pour changer leur parent -->
            <div
              v-for="child in filteredChildren(root.id)"
              :key="child.id"
              class="cat-row"
              :class="{ 'cat-row-editing': editingId === child.id, 'cat-row-dragging': draggedCategoryId === child.id }"
              style="padding-left: 24px; border-bottom: 1px solid var(--line);"
              draggable="true"
              @click="editingId !== child.id && startEdit(child)"
              @keydown.enter.prevent="editingId === child.id && confirmEdit()"
              @keydown.esc="cancelEdit"
              @dragstart="onDragStart($event, child)"
              @dragend="onDragEnd"
            >
              <template v-if="editingId === child.id">
                <div class="cat-row-inline" @click.stop>
                  <input v-model="editForm.icon" aria-label="Icône" placeholder="🙂" maxlength="4" class="icon-field" />
                  <input v-model="editForm.name" ref="firstEditField" aria-label="Nom" placeholder="Nom" style="flex: 1;" />
                  <select v-model.number="editForm.parentId" aria-label="Catégorie parente">
                    <option v-for="r in roots" :key="r.id" :value="r.id">{{ iconPrefix(r.icon) }}{{ r.name }}</option>
                  </select>
                  <span class="text-muted" style="font-size: 12px;">
                    {{ typeLabel(rootTypeById.get(editForm.parentId)) }} (hérité du parent)
                  </span>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
                  <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
                </div>
              </template>
              <template v-else>
                <div style="display:flex; align-items:center; gap:10px; padding: 8px 0; color: var(--text-muted);">
                  <input type="checkbox" :value="child.id" v-model="selectedIds" :aria-label="'Sélectionner ' + child.name" @click.stop />
                  <span>{{ iconPrefix(child.icon) }}{{ child.name }}</span>
                  <span v-if="child.annualized" class="badge" style="background: var(--amber-dim); color: var(--amber);" title="Les transactions de cette catégorie sont lissées sur 12 mois dans les rapports">Annuelle</span>
                </div>
                <div class="cat-actions" @click.stop>
                  <button class="btn secondary cat-btn" @click="toggleAnnualized(child)" title="Lisser les transactions de cette catégorie sur 12 mois dans les rapports (ex : taxe foncière, assurance annuelle)">{{ child.annualized ? 'Annuelle ✓' : 'Annuelle' }}</button>
                  <button class="btn secondary cat-btn" @click="toggleHidden(child)">Masquer</button>
                  <button class="btn danger cat-btn" @click="remove(child)">Supprimer</button>
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
        style="border-bottom: 1px solid var(--line);"
        @click="editingId !== item.id && startEdit(item)"
        @keydown.enter.prevent="editingId === item.id && confirmEdit()"
        @keydown.esc="cancelEdit"
      >
        <template v-if="editingId === item.id">
          <div class="cat-row-inline" @click.stop>
            <input v-model="editForm.icon" aria-label="Icône" placeholder="🙂" maxlength="4" class="icon-field" />
            <input v-model="editForm.name" ref="firstEditField" aria-label="Nom" placeholder="Nom" style="flex: 1;" />
            <template v-if="!item.parentId">
              <select v-model="editForm.type" aria-label="Type">
                <option value="EXPENSE">Dépense</option>
                <option value="INCOME">Revenu</option>
                <option value="BOTH">Les deux</option>
              </select>
            </template>
            <template v-else>
              <select v-model.number="editForm.parentId" aria-label="Catégorie parente">
                <option v-for="r in roots" :key="r.id" :value="r.id">{{ iconPrefix(r.icon) }}{{ r.name }}</option>
              </select>
              <span class="text-muted" style="font-size: 12px;">
                {{ typeLabel(rootTypeById.get(editForm.parentId)) }} (hérité du parent)
              </span>
            </template>
            <button class="btn secondary" style="padding: 6px 10px;" @click="confirmEdit">✓</button>
            <button class="btn secondary" style="padding: 6px 10px;" @click="cancelEdit">✕</button>
          </div>
        </template>
        <template v-else>
          <div class="cat-hidden" style="display:flex; align-items:center; gap:10px; padding: 8px 0;">
            <span>{{ categoryFullLabel(item) }}</span>
            <span class="badge" :style="typeBadgeStyle(item.type)">{{ typeLabel(item.type) }}</span>
            <span v-if="item.annualized" class="badge" style="background: var(--amber-dim); color: var(--amber);" title="Les transactions de cette catégorie sont lissées sur 12 mois dans les rapports">Annuelle</span>
          </div>
          <div class="cat-actions" @click.stop>
            <button class="btn secondary cat-btn" @click="toggleAnnualized(item)" title="Lisser les transactions de cette catégorie sur 12 mois dans les rapports (ex : taxe foncière, assurance annuelle)">{{ item.annualized ? 'Annuelle ✓' : 'Annuelle' }}</button>
            <button class="btn secondary cat-btn" @click="toggleHidden(item)">Réafficher</button>
            <button class="btn danger cat-btn" @click="remove(item)">Supprimer</button>
          </div>
        </template>
      </div>
    </div>

    <!-- Modal création uniquement -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>Nouvelle catégorie</h2>
        <div class="form-grid">
          <div class="form-field">
            <label for="new-category-icon">Icône</label>
            <input id="new-category-icon" v-model="form.icon" placeholder="🙂" maxlength="4" class="icon-field" style="width: 100%;" />
          </div>
          <div class="form-field">
            <label for="new-category-name">Nom</label>
            <input id="new-category-name" v-model="form.name" placeholder="Ex : Alimentation" />
          </div>
          <div class="form-field">
            <label for="new-category-parent">Catégorie parente</label>
            <select id="new-category-parent" v-model.number="form.parentId">
              <option :value="null">Aucune (catégorie racine)</option>
              <option v-for="r in roots" :key="r.id" :value="r.id">{{ iconPrefix(r.icon) }}{{ r.name }}</option>
            </select>
          </div>
          <div class="form-field" v-if="!form.parentId">
            <label for="new-category-type">Type</label>
            <select id="new-category-type" v-model="form.type">
              <option value="EXPENSE">Dépense</option>
              <option value="INCOME">Revenu</option>
              <option value="BOTH">Les deux</option>
            </select>
          </div>
          <div class="form-field" v-else>
            <span class="form-field-label">Type</span>
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
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { CategoriesAPI } from '../services/api'
import { normalizeSearch, parseSearchTerms } from '../services/search'
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

const showReparentModal = ref(false)
const reparentTargetId = ref(null)
const reparenting = ref(false)

// Id de la sous-catégorie en cours de glissement (drag-and-drop), et id de la
// catégorie racine actuellement survolée par ce glissement (pour son
// surlignage, voir .cat-row-dropzone).
const draggedCategoryId = ref(null)
const dragOverRootId = ref(null)

function emptyForm() {
  return { name: '', parentId: null, type: 'EXPENSE', icon: '' }
}

/** Prefixe un nom de catégorie avec son icône (emoji), suivie d'un espace — chaîne vide si aucune icône. */
function iconPrefix(icon) {
  return icon ? `${icon} ` : ''
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

/**
 * Un critère matche un nom de catégorie : en mode "contient" (défaut) il
 * suffit que le terme soit inclus dans le nom normalisé ; en mode "exact"
 * (terme entre guillemets, ex : "CAF") il faut une égalité stricte, pour
 * retrouver "CAF" sans remonter aussi une catégorie contenant "caf".
 */
function nameMatchesTerm(name, { term, exact }) {
  const normalized = normalizeSearch(name)
  return exact ? normalized === term : normalized.includes(term)
}

/**
 * Plusieurs critères séparés par ";" sont tous exigés à la fois (ET) — même
 * logique que la recherche de Transactions.vue/Payees.vue, voir
 * parseSearchTerms dans services/search.js.
 */
const searchTerms = computed(() => parseSearchTerms(searchQuery.value))

const filteredRoots = computed(() => {
  const terms = searchTerms.value
  if (!terms.length) return roots.value
  return roots.value.filter(root =>
    terms.every(criterion =>
      nameMatchesTerm(root.name, criterion) ||
      children(root.id).some(child => nameMatchesTerm(child.name, criterion))
    )
  )
})

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

/** Sous-catégories visibles d'une racine, pour les colonnes Dépenses/Revenus : les masquées en sont exclues (elles apparaissent dans l'encart dédié). */
function filteredChildren(rootId) {
  const terms = searchTerms.value
  const kids = children(rootId).filter(child => !child.hidden)
  if (!terms.length) return kids
  const root = roots.value.find(r => r.id === rootId)
  if (root && terms.every(criterion => nameMatchesTerm(root.name, criterion))) return kids
  return kids.filter(child => terms.every(criterion => nameMatchesTerm(child.name, criterion)))
}

/** Catégories masquées (racines et sous-catégories), regroupées dans un encart à part sous les colonnes Dépenses/Revenus. */
const hiddenCategories = computed(() => {
  const terms = searchTerms.value
  return categories.value
    .filter(c => c.hidden)
    .filter(c => !terms.length || terms.every(criterion => nameMatchesTerm(categoryFullLabel(c), criterion)))
    .sort((a, b) => categoryFullLabel(a).localeCompare(categoryFullLabel(b), 'fr'))
})

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
  if (!category.parentId) return `${iconPrefix(category.icon)}${category.name}`
  const parent = categories.value.find(c => c.id === category.parentId)
  const ownLabel = `${iconPrefix(category.icon)}${category.name}`
  return parent ? `${iconPrefix(parent.icon)}${parent.name} : ${ownLabel}` : ownLabel
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
    parentId: form.value.parentId,
    icon: form.value.icon || null
  }
  await CategoriesAPI.create(payload)
  showModal.value = false
  await load()
}

async function remove(category) {
  if (!confirm(`Supprimer la catégorie "${category.name}" ?`)) return
  try {
    await CategoriesAPI.remove(category.id)
    await load()
  } catch (e) {
    alert('Erreur lors de la suppression : ' + (e.response?.data?.error || e.message))
  }
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

/**
 * Bascule le caractere "annuel" d'une categorie : toute transaction qui lui
 * est affectee sera desormais (ou ne sera plus) lissee sur 12 mois dans les
 * rapports du tableau de bord (voir ServiceRapport#attributedTransactions),
 * sans avoir a le cocher transaction par transaction.
 */
async function toggleAnnualized(category) {
  await CategoriesAPI.toggleAnnualized(category.id)
  await load()
}

/* --- Édition inline --- */

function startEdit(category) {
  editingId.value = category.id
  editForm.value = {
    name: category.name,
    parentId: category.parentId ?? null,
    type: category.type,
    icon: category.icon || ''
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
    parentId: editForm.value.parentId,
    icon: editForm.value.icon || null
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

/* --- Changement groupé de catégorie parente ---
 * Comme pour l'édition inline d'une sous-catégorie (seule ligne qui expose
 * déjà un sélecteur de parent), seules les sous-catégories sélectionnées
 * sont concernées : une catégorie racine n'a pas de parent à changer ici
 * (elle a ses propres sous-catégories, la reparenter les laisserait
 * orphelines de niveau). Les racines éventuellement cochées en même temps
 * (ex. via "sélectionner tout") sont simplement ignorées par cette action.
 */
const reparentableIds = computed(() =>
  selectedCategoryObjects.value.filter(c => c.parentId != null).map(c => c.id)
)

function openReparentModal() {
  reparentTargetId.value = roots.value[0]?.id ?? null
  showReparentModal.value = true
}

async function confirmReparent() {
  if (!reparentTargetId.value) return
  reparenting.value = true
  try {
    const categoriesById = new Map(categories.value.map(c => [c.id, c]))
    await Promise.all(reparentableIds.value.map(id => {
      const category = categoriesById.get(id)
      return CategoriesAPI.update(id, { name: category.name, type: category.type, parentId: reparentTargetId.value, icon: category.icon })
    }))
    showReparentModal.value = false
    selectedIds.value = []
    await load()
  } catch (e) {
    alert('Erreur lors du changement de catégorie parente : ' + (e.response?.data?.error || e.message))
  } finally {
    reparenting.value = false
  }
}

/* --- Glisser-déposer une sous-catégorie vers une autre catégorie racine ---
 * Seules les sous-catégories sont glissables (même limitation que le
 * changement groupé ci-dessus : une racine avec ses propres sous-catégories
 * ne peut pas devenir une sous-catégorie sans les rendre orphelines
 * d'affichage). Les racines sont uniquement des cibles de dépôt.
 */
function onDragStart(event, child) {
  draggedCategoryId.value = child.id
  event.dataTransfer.effectAllowed = 'move'
  // Certains navigateurs exigent des données pour autoriser le drop ; la
  // valeur elle-meme n'est pas relue, draggedCategoryId suffit cote appli.
  event.dataTransfer.setData('text/plain', String(child.id))
}

function onDragEnd() {
  draggedCategoryId.value = null
  dragOverRootId.value = null
}

function onRootDragOver(event, root) {
  if (draggedCategoryId.value == null) return
  event.preventDefault() // necessaire pour autoriser le drop (comportement par defaut = refus)
  event.dataTransfer.dropEffect = 'move'
  dragOverRootId.value = root.id
}

function onRootDragLeave(root) {
  if (dragOverRootId.value === root.id) dragOverRootId.value = null
}

async function onRootDrop(event, root) {
  event.preventDefault()
  const id = draggedCategoryId.value
  dragOverRootId.value = null
  draggedCategoryId.value = null
  if (id == null) return

  const category = categories.value.find(c => c.id === id)
  if (!category || category.parentId === root.id) return // pas de sous-categorie, ou deja sous cette racine

  try {
    await CategoriesAPI.update(id, { name: category.name, type: category.type, parentId: root.id, icon: category.icon })
    await load()
  } catch (e) {
    alert('Erreur lors du déplacement : ' + (e.response?.data?.error || e.message))
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
/* Gabarit reduit par rapport a .btn par defaut (10px 16px / 14px) : quatre
   boutons cote a cote sur une ligne de categorie n'ont pas besoin d'etre
   aussi imposants qu'un bouton de page (ex : "+ Nouvelle categorie"). */
.cat-btn {
  padding: 5px 10px;
  font-size: 12px;
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
/* Champ icône (emoji) : gabarit court et centré, plutôt que la largeur pleine par défaut d'un input. */
.icon-field {
  width: 44px;
  flex: 0 0 auto;
  text-align: center;
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
.text-muted {
  color: var(--text-muted);
}
.cat-hidden {
  opacity: 0.55;
}
</style>

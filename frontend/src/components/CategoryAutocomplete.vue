<template>
  <div class="cat-autocomplete" ref="rootEl">
    <div class="cat-autocomplete-input">
      <input
        ref="inputEl"
        v-model="query"
        type="text"
        autocomplete="off"
        :id="id"
        :aria-label="ariaLabel"
        :placeholder="placeholder"
        @focus="openDropdown"
        @input="handleInput"
        @keydown.down.prevent="moveHighlight(1)"
        @keydown.up.prevent="moveHighlight(-1)"
        @keydown.enter.prevent="selectHighlighted"
        @keydown.esc="closeDropdown"
        @blur="handleBlur"
      />
      <button
        v-if="query"
        type="button"
        class="cat-autocomplete-clear"
        title="Supprimer la catégorie sélectionnée"
        @mousedown.prevent="clearSelection"
      >
        ✕
      </button>
    </div>

    <div v-if="open" class="cat-autocomplete-dropdown">
      <div v-if="!flatAllVisibleOptions.length" class="cat-autocomplete-empty">
        Aucune catégorie ne correspond à « {{ query }} ».
      </div>
      <template v-for="group in groupedOptions" :key="group.key">
        <div v-if="group.options.length" class="cat-autocomplete-group-label">
          {{ group.title }}
        </div>
        <div
          v-for="option in group.options"
          :key="group.key + '-' + option.id"
          class="cat-autocomplete-option"
          :class="{
            highlighted: isHighlighted(group.key, option.id),
            'parent-only': !option.selectable
          }"
          :title="option.selectable ? undefined : 'Catégorie parente : choisissez une sous-catégorie ci-dessous'"
          @mousedown.prevent="option.selectable && selectOption(option)"
          @mouseenter="option.selectable && (highlightedKey = group.key + '-' + option.id)"
        >
          {{ option.label }}
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { normalizeSearch } from '../services/search'

const props = defineProps({
  modelValue: { type: Number, default: null },
  // Liste des catégories existantes, sous la forme { id, label, type, selectable }.
  // "type" est celui deja porte par le modele existant (Category#type :
  // INCOME / EXPENSE / BOTH), jamais recalcule ici a partir du nom.
  // "selectable" (voir services/categories.js) distingue les sous-categories
  // (toujours selectionnables) des categories racine qui ont des enfants
  // (affichees pour le contexte de la hierarchie, jamais selectionnables).
  categories: { type: Array, required: true },
  placeholder: { type: String, default: 'Rechercher une catégorie…' },
  // Transmis tel quel au <input> interne, pour permettre a l'appelant
  // d'associer un <label for="..."> (accessibilite).
  id: { type: String, default: null },
  // Nom accessible direct quand aucun <label> visible n'existe (ex : cellule
  // de tableau, champ d'edition en masse) — alternative a id+<label for>.
  ariaLabel: { type: String, default: null },
  // 'EXPENSE' | 'INCOME' | null : quand fourni, ne montre que les categories
  // du type correspondant (+ celles marquees BOTH), pour ne jamais pouvoir
  // affecter une categorie de revenu a une depense ou inversement. Laisser
  // null quand la direction n'a pas de sens (ex : categorie par defaut d'un
  // tiers, qui peut s'appliquer aux deux).
  transactionType: { type: String, default: null }
})

const emit = defineEmits(['update:modelValue'])

const rootEl = ref(null)
const inputEl = ref(null)
const query = ref('')
const open = ref(false)
// Cle du "group.key + '-' + option.id" actuellement surligne au clavier/survol.
const highlightedKey = ref(null)

function labelFor(id) {
  if (!id) return ''
  return props.categories.find(c => c.id === id)?.label || ''
}

// Synchronise le texte affiché avec la valeur (id) reçue depuis le parent,
// y compris quand elle change de l'extérieur (ex : annulation, rechargement).
watch(() => props.modelValue, (id) => {
  query.value = labelFor(id)
}, { immediate: true })

/**
 * Regroupe visuellement les catégories par type reel (Dépenses / Revenus),
 * en reutilisant le meme critere que la page Categories.vue : une categorie
 * "BOTH" apparait dans les deux groupes (utilisable aussi bien pour une
 * depense que pour un revenu). La recherche continue de filtrer a
 * l'interieur de chaque groupe, et un groupe sans aucun resultat est masque.
 */
// Pretraitement (normalisation) qui ne depend que de `categories`, jamais
// de `query`, pour ne pas renormaliser tous les libelles a chaque frappe
// (meme principe que sortedNormalizedPayees dans PayeeAutocomplete).
const normalizedCategories = computed(() =>
  props.categories.map(c => ({ ...c, normalizedLabel: normalizeSearch(c.label) }))
)

const groupedOptions = computed(() => {
  const q = normalizeSearch(query.value)
  // Une categorie masquee (Categories.vue > "Masquer") n'est plus proposee
  // comme suggestion, mais reste dans props.categories pour que labelFor()
  // affiche correctement une categorie deja affectee avant masquage.
  const matches = (c) => !c.hidden && (!q || c.normalizedLabel.includes(q))

  const groups = []
  if (props.transactionType !== 'INCOME') {
    groups.push({
      key: 'expense',
      title: 'Dépenses',
      options: normalizedCategories.value.filter(c => (c.type === 'EXPENSE' || c.type === 'BOTH') && matches(c))
    })
  }
  if (props.transactionType !== 'EXPENSE') {
    groups.push({
      key: 'income',
      title: 'Revenus',
      options: normalizedCategories.value.filter(c => (c.type === 'INCOME' || c.type === 'BOTH') && matches(c))
    })
  }
  return groups
})

// Deux vues a plat sur les options visibles :
// - flatAllVisibleOptions (categories racine "affichage seul" comprises)
//   sert uniquement a savoir si le dropdown a quelque chose a montrer ;
// - flatSelectableOptions (uniquement les sous-categories/racines sans
//   enfant) sert a la navigation clavier et a la selection.
const flatAllVisibleOptions = computed(() =>
  groupedOptions.value.flatMap(g => g.options.map(o => ({ groupKey: g.key, option: o })))
)
const flatVisibleOptions = computed(() =>
  flatAllVisibleOptions.value.filter(e => e.option.selectable)
)

function isHighlighted(groupKey, optionId) {
  return highlightedKey.value === groupKey + '-' + optionId
}

function openDropdown() {
  open.value = true
  highlightedKey.value = null
}

function closeDropdown() {
  open.value = false
  highlightedKey.value = null
}

function handleInput() {
  open.value = true
  highlightedKey.value = null
}

function selectOption(option) {
  query.value = option.label
  emit('update:modelValue', option.id)
  closeDropdown()
}

function clearSelection() {
  query.value = ''
  emit('update:modelValue', null)
  closeDropdown()
  nextTick(() => inputEl.value?.focus())
}

function moveHighlight(delta) {
  if (!open.value) {
    openDropdown()
    return
  }
  const flat = flatVisibleOptions.value
  if (!flat.length) return
  const currentIndex = flat.findIndex(e => e.groupKey + '-' + e.option.id === highlightedKey.value)
  const nextIndex = (currentIndex + delta + flat.length) % flat.length
  const next = flat[nextIndex]
  highlightedKey.value = next.groupKey + '-' + next.option.id
}

function selectHighlighted() {
  const flat = flatVisibleOptions.value
  const entry = flat.find(e => e.groupKey + '-' + e.option.id === highlightedKey.value)
  if (entry) {
    selectOption(entry.option)
  } else {
    // Aucune option surlignee : seule une correspondance exacte avec une
    // categorie existante est acceptee (on ne cree jamais de categorie
    // depuis ce champ), sinon on revient a la selection precedente.
    reconcileTypedText()
    closeDropdown()
  }
}

/**
 * Au blur (ou a la validation sans selection explicite), on ne conserve le
 * texte tape que s'il correspond exactement (insensible a la casse) a une
 * categorie existante ; sinon on revient a la derniere categorie
 * effectivement selectionnee, ou on vide le champ si aucune ne l'etait.
 * Garantit qu'on "ne propose que des categories reellement existantes".
 */
function reconcileTypedText() {
  const trimmed = query.value.trim().toLowerCase()
  if (!trimmed) {
    if (props.modelValue !== null) emit('update:modelValue', null)
    return
  }
  const exact = props.categories.find(c => c.selectable && !c.hidden && c.label.toLowerCase() === trimmed)
  if (exact) {
    if (exact.id !== props.modelValue) emit('update:modelValue', exact.id)
    query.value = exact.label
  } else {
    // Pas de correspondance exacte : on revient a l'etat precedent.
    query.value = labelFor(props.modelValue)
  }
}

function handleBlur() {
  // Laisse le temps a un @mousedown.prevent sur une option de s'executer
  // avant de refermer/reconcilier (le mousedown est traite avant le blur).
  reconcileTypedText()
  closeDropdown()
}

defineExpose({ focus: () => inputEl.value?.focus() })
</script>

<style scoped>
.cat-autocomplete {
  position: relative;
  width: 100%;
}
.cat-autocomplete-input {
  position: relative;
  display: flex;
  align-items: center;
}
.cat-autocomplete-input input {
  width: 100%;
  padding-right: 26px;
}
.cat-autocomplete-clear {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: transparent;
  color: var(--text-faint);
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
  padding: 4px;
  border-radius: 50%;
}
.cat-autocomplete-clear:hover {
  color: var(--text-primary);
  background: var(--ink-700);
}
.cat-autocomplete-dropdown {
  position: absolute;
  z-index: 30;
  top: calc(100% + 4px);
  left: 0;
  /* Largeur au moins egale au champ, mais qui s'elargit jusqu'a 380px pour
     eviter de tronquer les libelles longs ("Parent : Sous-categorie"),
     notamment dans les colonnes etroites du tableau des transactions. */
  min-width: 100%;
  width: max-content;
  max-width: 380px;
  max-height: 280px;
  overflow-y: auto;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: 0 8px 20px rgba(23, 32, 46, 0.14);
}
.cat-autocomplete-group-label {
  position: sticky;
  top: 0;
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .04em;
  color: var(--text-faint);
  background: var(--ink-800);
  cursor: default;
  user-select: none;
}
.cat-autocomplete-option {
  padding: 8px 12px 8px 18px;
  cursor: pointer;
  font-size: 14px;
  color: var(--text-primary);
}
.cat-autocomplete-option.highlighted,
.cat-autocomplete-option:hover {
  background: var(--azure-dim);
  color: var(--azure);
}
/*
 * Categorie racine avec sous-categories : affichee pour le contexte de la
 * hierarchie uniquement, jamais selectionnable (voir services/categories.js).
 * En gras + curseur par defaut, et le survol/surlignage normal est
 * desactive juste au-dessus (le handler ignore deja le mousedown/mouseenter).
 */
.cat-autocomplete-option.parent-only {
  font-weight: 700;
  cursor: default;
  padding-left: 12px;
}
.cat-autocomplete-option.parent-only:hover {
  background: transparent;
  color: var(--text-primary);
}
.cat-autocomplete-empty {
  padding: 10px 12px;
  font-size: 13px;
  color: var(--text-faint);
}
</style>

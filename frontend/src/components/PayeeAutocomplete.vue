<template>
  <div class="payee-autocomplete" ref="rootEl">
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
      @keydown.esc="onEscape"
      @blur="closeDropdown"
    />

    <div v-if="open && visibleOptions.length" class="payee-autocomplete-dropdown">
      <div
        v-for="(name, index) in visibleOptions"
        :key="name"
        class="payee-autocomplete-option"
        :class="{ highlighted: index === highlightedIndex }"
        @mousedown.prevent="selectOption(name)"
        @mouseenter="highlightedIndex = index"
      >
        {{ name }}
      </div>
      <div v-if="truncated" class="payee-autocomplete-more">
        et {{ truncated }} autre(s)… affinez la recherche
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Autocomplete "tiers" en JS, en remplacement du datalist HTML natif
 * (<input list="payee-options">) qui a ete identifie comme la cause du
 * ralentissement au clavier sur la page Transactions : un datalist natif
 * doit re-filtrer TOUTES ses <option> a chaque frappe cote navigateur, et
 * ce filtrage devient tres lent des que la liste des tiers atteint
 * plusieurs centaines/milliers d'entrees (historique bancaire reel).
 *
 * Ici le filtrage se fait cote JS (rapide, meme sur de grandes listes) et
 * surtout le DOM affiche est plafonne a `maxResults` options, ce qui evite
 * de reproduire le meme probleme (un DOM avec des milliers de noeuds serait
 * lui aussi lent a mettre a jour).
 *
 * Contrairement a CategoryAutocomplete, le tiers reste un champ libre : le
 * texte tape est TOUJOURS la valeur (un nouveau tiers peut etre cree a la
 * volee, resolu cote backend, voir PayeeService#resolveOrCreate). Le
 * dropdown n'est qu'une suggestion parmi les tiers deja connus.
 */
import { ref, computed, watch } from 'vue'
import { normalizeSearch } from '../services/search'

const props = defineProps({
  modelValue: { type: String, default: '' },
  // Liste des tiers existants, sous la forme { id, name, ... }.
  payees: { type: Array, required: true },
  placeholder: { type: String, default: '' },
  maxResults: { type: Number, default: 30 },
  // Transmis tel quel au <input> interne, pour permettre a l'appelant
  // d'associer un <label for="..."> (accessibilite).
  id: { type: String, default: null },
  // Nom accessible direct quand aucun <label> visible n'existe (ex : cellule
  // de tableau, champ d'edition en masse) — alternative a id+<label for>.
  ariaLabel: { type: String, default: null }
})

const emit = defineEmits(['update:modelValue'])

const rootEl = ref(null)
const inputEl = ref(null)
const query = ref(props.modelValue || '')
const open = ref(false)
const highlightedIndex = ref(-1)

// Reste synchronise si la valeur change depuis l'exterieur (annulation,
// ouverture d'une autre transaction en edition, etc.).
watch(() => props.modelValue, (value) => {
  if (value !== query.value) query.value = value || ''
})

/**
 * Pretraitement des tiers (dedoublonnage, normalisation, tri) : ce computed
 * ne depend QUE de `payees`, jamais de `query`. C'est essentiel pour la
 * performance : s'il dependait aussi de `query` (comme matchingNames
 * ci-dessous), Vue reexecuterait tout ce travail (potentiellement sur des
 * milliers de tiers) a CHAQUE frappe au clavier, au lieu d'une seule fois
 * quand la liste des tiers change reellement.
 */
const sortedNormalizedPayees = computed(() => {
  const seen = new Set()
  const entries = []
  for (const p of props.payees) {
    const name = p.name
    // Deduplique (des tiers peuvent partager un nom affiche identique dans
    // de rares cas de donnees importees).
    if (!name || seen.has(name)) continue
    seen.add(name)
    entries.push({ name, normalized: normalizeSearch(name) })
  }
  entries.sort((a, b) => a.name.localeCompare(b.name, 'fr'))
  return entries
})

// Ne fait plus, a chaque frappe, qu'un filtre sur des chaines deja
// normalisees et une liste deja triee : pas de re-normalisation ni de
// re-tri de l'ensemble des tiers.
const matchingNames = computed(() => {
  const q = normalizeSearch(query.value)
  const list = sortedNormalizedPayees.value
  const filtered = q ? list.filter(e => e.normalized.includes(q)) : list
  return filtered.map(e => e.name)
})

const visibleOptions = computed(() => matchingNames.value.slice(0, props.maxResults))
const truncated = computed(() => Math.max(0, matchingNames.value.length - props.maxResults))

function openDropdown() {
  open.value = true
  highlightedIndex.value = -1
}

function closeDropdown() {
  // Laisse le temps a un @mousedown.prevent sur une option de s'executer
  // avant de refermer (le mousedown est traite avant le blur).
  open.value = false
  highlightedIndex.value = -1
}

/**
 * Échap ferme d'abord la liste visible, sans remonter au parent (sinon elle
 * annulait aussi l'édition de la ligne ou fermait la fenêtre). Liste déjà
 * fermée : Échap remonte normalement.
 */
function onEscape(event) {
  if (!open.value || !visibleOptions.value.length) return
  event.stopPropagation()
  closeDropdown()
}

function handleInput() {
  emit('update:modelValue', query.value)
  open.value = true
  highlightedIndex.value = -1
}

function selectOption(name) {
  query.value = name
  emit('update:modelValue', name)
  closeDropdown()
}

function moveHighlight(delta) {
  if (!open.value) {
    openDropdown()
    return
  }
  const options = visibleOptions.value
  if (!options.length) return
  highlightedIndex.value = (highlightedIndex.value + delta + options.length) % options.length
}

function selectHighlighted() {
  const options = visibleOptions.value
  if (highlightedIndex.value >= 0 && options[highlightedIndex.value]) {
    selectOption(options[highlightedIndex.value])
  } else {
    closeDropdown()
  }
}

defineExpose({ focus: () => inputEl.value?.focus() })
</script>

<style scoped>
.payee-autocomplete {
  position: relative;
  width: 100%;
}
.payee-autocomplete input {
  width: 100%;
}
.payee-autocomplete-dropdown {
  position: absolute;
  z-index: 30;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  max-height: 240px;
  overflow-y: auto;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover);
}
.payee-autocomplete-option {
  padding: 8px 12px;
  cursor: pointer;
  font-size: 14px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.payee-autocomplete-option.highlighted,
.payee-autocomplete-option:hover {
  background: var(--azure-dim);
  color: var(--azure);
}
.payee-autocomplete-more {
  padding: 6px 12px;
  font-size: 12px;
  color: var(--text-faint);
  font-style: italic;
  cursor: default;
}
</style>

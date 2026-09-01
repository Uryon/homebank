<template>
  <div class="myf" ref="rootEl">
    <button type="button" class="myf-trigger" :class="{ active: month != null || year != null }" @click="toggleOpen">
      <span class="myf-icon">📅</span>
      <span>{{ triggerLabel }}</span>
    </button>

    <div v-if="open" class="myf-panel">
      <div class="myf-panel-header">
        <button
          type="button"
          class="myf-nav-btn"
          :disabled="!canGoPrevYear"
          title="Année précédente"
          @click="viewYear--"
        >‹</button>
        <button
          type="button"
          class="myf-year-btn"
          title="Filtrer sur toute l'année"
          @click="selectYearOnly"
        >{{ viewYear }}</button>
        <button
          type="button"
          class="myf-nav-btn"
          :disabled="!canGoNextYear"
          title="Année suivante"
          @click="viewYear++"
        >›</button>
      </div>

      <div class="myf-months">
        <button
          v-for="m in MONTHS"
          :key="m.value"
          type="button"
          class="myf-month-btn"
          :class="{ active: month === m.value && year === viewYear }"
          @click="selectMonth(m.value)"
        >{{ m.short }}</button>
      </div>

      <button type="button" class="myf-reset" :disabled="month == null && year == null" @click="reset">
        Toutes les périodes
      </button>
    </div>
  </div>
</template>

<script setup>
/**
 * Remplace les deux <select> Mois/Année par un calendrier compact (popover) :
 * navigation par année + grille des 12 mois, comme un sélecteur "mois/année"
 * classique. Un clic sur un mois filtre sur ce mois précis de l'année
 * affichée ; un clic sur l'année filtre sur l'année entière (tous les mois) ;
 * "Toutes les périodes" réinitialise les deux filtres.
 *
 * Contrepartie assumée par rapport aux anciens <select> indépendants : on ne
 * peut plus filtrer "un mois donné, toutes années confondues" (ex : tous les
 * mois de décembre) — cas rare, et qui ne correspond à aucune interaction
 * naturelle dans un calendrier mois/année.
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  month: { type: Number, default: null },
  year: { type: Number, default: null },
  // Années presentes dans les donnees (voir Transactions.vue#availableYears),
  // utilisees pour borner la navigation ‹ › aux annees qui contiennent
  // effectivement des transactions (meme perimetre que l'ancien <select>).
  availableYears: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:month', 'update:year'])

const MONTHS = [
  { value: 1, short: 'Jan', label: 'Janvier' },
  { value: 2, short: 'Fév', label: 'Février' },
  { value: 3, short: 'Mar', label: 'Mars' },
  { value: 4, short: 'Avr', label: 'Avril' },
  { value: 5, short: 'Mai', label: 'Mai' },
  { value: 6, short: 'Jun', label: 'Juin' },
  { value: 7, short: 'Jul', label: 'Juillet' },
  { value: 8, short: 'Aoû', label: 'Août' },
  { value: 9, short: 'Sep', label: 'Septembre' },
  { value: 10, short: 'Oct', label: 'Octobre' },
  { value: 11, short: 'Nov', label: 'Novembre' },
  { value: 12, short: 'Déc', label: 'Décembre' }
]
const monthLabelByValue = new Map(MONTHS.map(m => [m.value, m.label]))

const rootEl = ref(null)
const open = ref(false)

const currentCalendarYear = new Date().getFullYear()
const sortedYears = computed(() => [...props.availableYears].sort((a, b) => a - b))
const minYear = computed(() => sortedYears.value.length ? sortedYears.value[0] : currentCalendarYear)
const maxYear = computed(() => sortedYears.value.length ? sortedYears.value[sortedYears.value.length - 1] : currentCalendarYear)

// Annee affichee dans le panneau (independante du filtre tant qu'aucun mois/
// annee n'est choisi) : demarre sur le filtre courant, sinon la derniere
// annee disponible.
const viewYear = ref(props.year ?? maxYear.value)

watch(() => props.year, (y) => {
  if (y != null) viewYear.value = y
})

const canGoPrevYear = computed(() => viewYear.value > minYear.value)
const canGoNextYear = computed(() => viewYear.value < maxYear.value)

const triggerLabel = computed(() => {
  if (props.month != null && props.year != null) return `${monthLabelByValue.get(props.month)} ${props.year}`
  if (props.year != null) return `Année ${props.year}`
  if (props.month != null) return `${monthLabelByValue.get(props.month)} (toutes années)`
  return 'Toutes les périodes'
})

function toggleOpen() {
  open.value = !open.value
  if (open.value) viewYear.value = props.year ?? viewYear.value
}

function selectMonth(m) {
  emit('update:year', viewYear.value)
  emit('update:month', m)
  open.value = false
}

function selectYearOnly() {
  emit('update:year', viewYear.value)
  emit('update:month', null)
  open.value = false
}

function reset() {
  emit('update:year', null)
  emit('update:month', null)
  open.value = false
}

function handleDocumentMousedown(event) {
  if (open.value && rootEl.value && !rootEl.value.contains(event.target)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('mousedown', handleDocumentMousedown))
onBeforeUnmount(() => document.removeEventListener('mousedown', handleDocumentMousedown))
</script>

<style scoped>
.myf {
  position: relative;
}

.myf-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 190px;
  background: var(--ink-800);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 9px 11px;
  color: var(--text-primary);
  font-size: 14px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.myf-trigger:hover {
  border-color: var(--ink-600);
}
.myf-trigger.active {
  border-color: var(--azure);
  color: var(--azure);
  background: var(--azure-dim);
}
.myf-icon {
  font-size: 13px;
  line-height: 1;
}

.myf-panel {
  position: absolute;
  z-index: 30;
  top: calc(100% + 6px);
  left: 0;
  width: 240px;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: 0 8px 20px rgba(23, 32, 46, 0.14);
  padding: 12px;
}

.myf-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.myf-nav-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--ink-800);
  color: var(--text-primary);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.myf-nav-btn:hover:not(:disabled) {
  background: var(--ink-700);
}
.myf-nav-btn:disabled {
  opacity: 0.35;
  cursor: default;
}
.myf-year-btn {
  border: none;
  background: transparent;
  font-weight: 700;
  font-size: 15px;
  color: var(--text-primary);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
}
.myf-year-btn:hover {
  background: var(--azure-dim);
  color: var(--azure);
}

.myf-months {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin-bottom: 10px;
}
.myf-month-btn {
  border: 1px solid transparent;
  background: var(--ink-800);
  border-radius: var(--radius-sm);
  padding: 8px 4px;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.12s, color 0.12s, border-color 0.12s;
}
.myf-month-btn:hover {
  background: var(--azure-dim);
  color: var(--azure);
}
.myf-month-btn.active {
  background: var(--azure);
  border-color: var(--azure);
  color: #FFFFFF;
}

.myf-reset {
  width: 100%;
  border: 1px solid var(--line);
  background: transparent;
  border-radius: var(--radius-sm);
  padding: 7px 8px;
  color: var(--text-muted);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.myf-reset:hover:not(:disabled) {
  background: var(--ink-800);
  color: var(--text-primary);
}
.myf-reset:disabled {
  opacity: 0.4;
  cursor: default;
}
</style>

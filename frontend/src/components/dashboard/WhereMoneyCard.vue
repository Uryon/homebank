<template>
  <div class="card compact-card where-money-card" :class="{ 'section-collapsed': collapsed }">
    <div class="where-money-header">
      <div class="card-title has-subtitle"><SectionToggle section="ou-va-mon-argent" label="Où va mon argent ?" />Où va mon argent ?</div>
      <div class="scope-toggle" role="group" aria-label="Période de la répartition">
        <button v-for="option in scopeOptions" :key="option.value" type="button" class="scope-option"
                :class="{ active: scope === option.value }" :aria-pressed="scope === option.value"
                @click="emit('update:scope', option.value)">{{ option.label }}</button>
      </div>
    </div>
    <div class="section-body">
    <div class="card-subtitle">
      Répartition des dépenses — {{ periodLabel }} (virements entre comptes exclus).<span class="screen-only"> Cliquez sur une catégorie pour voir ses transactions.</span>
    </div>
    <div v-if="data.length" class="where-money-layout">
      <div class="donut-wrap">
        <canvas ref="canvas" width="150" height="150"></canvas>
        <div class="donut-center">
          <div class="donut-center-value">{{ formatAmountCompact(total) }}</div>
          <div class="donut-center-label">{{ scope === 'month' ? 'sur le mois' : 'sur 12 mois' }}</div>
        </div>
      </div>
      <div class="where-table-wrap">
        <table class="where-table">
          <thead>
            <tr>
              <th>Catégorie</th>
              <th class="where-share-col"><span class="sr-only">Part des dépenses</span></th>
              <th class="num">Montant</th>
              <th class="num">%</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in data" :key="row.category" class="drillable-row"
                title="Voir les transactions"
                @click="openRow(row)">
              <td>
                <span class="legend-dot category-color" :style="rowColorStyle(row)"></span>
                {{ iconPrefix(row.categoryId) }}{{ row.category }}
              </td>
              <td class="where-share-col" aria-hidden="true">
                <span class="where-share-track">
                  <span class="where-share-fill category-color" :style="{ ...rowColorStyle(row), width: row.percentage + '%' }"></span>
                </span>
              </td>
              <td class="num amount negative"
                  @mouseenter="scheduleHover($event, rowTitle(row), () => fetchCellTransactions(range.from, range.to, 'expense', rowFilter(row)))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(row.total) }}
              </td>
              <td class="num where-pct">{{ formatShare(row.percentage) }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr>
              <td>Total</td>
              <td class="where-share-col"></td>
              <td class="num amount negative">{{ formatAmount(total) }}</td>
              <td class="num where-pct">{{ formatShare(100) }}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
    <div v-else class="empty-state">Aucune dépense — {{ periodLabel }}.</div>
    </div>
  </div>
</template>

<script setup>
/**
 * « Où va mon argent ? » : donut compact (le tableau à droite lui sert de
 * légende détaillée, total au centre) et répartition des dépenses par
 * catégorie, sur le mois affiché ou ses 12 mois (voir ServiceRapport#expensesByCategory).
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Chart from '../../services/chart'
import { readChartColors, tooltipOptions } from '../../services/chartTheme'
import { categoryColorString, categoryColorStyle, readCategoryColorScale, useCategoryColors, UNCATEGORIZED_COLOR } from '../../services/categoryColors'
import { effectiveTheme } from '../../services/theme'
import { formatAmount } from '../../services/format'
import { useCellDetail } from '../../services/cellDetail'
import { useCollapsibleSection } from '../../services/collapsibleSections'
import SectionToggle from './SectionToggle.vue'
import { useCategoryIconPrefix } from '../../services/categoryIcons'
import { dataStore } from '../../services/dataStore'

const props = defineProps({
  /** Lignes { category, categoryId, uncategorized, total, percentage }, plus grosse dépense d'abord. */
  data: { type: Array, required: true },
  /** Bornes { from, to } de la répartition affichée. */
  range: { type: Object, required: true },
  /** 'month' (mois affiché) ou 'year' (ses 12 mois). */
  scope: { type: String, required: true },
  monthLabel: { type: String, required: true },
  periodLabel: { type: String, required: true }
})
const emit = defineEmits(['update:scope'])

const { scheduleHover, onCellLeave, fetchCellTransactions, openTransactions } = useCellDetail()
const iconPrefix = useCategoryIconPrefix()
const categoryColor = useCategoryColors()
const canvas = ref(null)
const { collapsed } = useCollapsibleSection('ou-va-mon-argent')
let chart = null

const scopeOptions = computed(() => [
  { value: 'month', label: props.monthLabel },
  { value: 'year', label: '12 mois' }
])
const total = computed(() => props.data.reduce((sum, row) => sum + Number(row.total || 0), 0))

const shareFormatter = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 1 })
// Instance unique : construire un Intl.NumberFormat à chaque appel est coûteux.
const compactAmountFormatter = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 })

/** Part reçue du backend (ex : 24.6) affichée à la française : « 24,6 % ». */
function formatShare(percentage) {
  return shareFormatter.format(Number(percentage || 0) / 100)
}

/** Total compact au centre du donut (« 3 780 € » plutôt que « 3 780,00 € »). */
function formatAmountCompact(value) {
  return compactAmountFormatter.format(value || 0)
}

/** Couleur fixe de la catégorie d'une ligne (voir services/categoryColors.js), grise sans catégorie. */
function rowColor(row) {
  return row.uncategorized ? UNCATEGORIZED_COLOR : categoryColor(row.categoryId)
}

function rowColorStyle(row) {
  return categoryColorStyle(rowColor(row))
}

/** Filtre catégorie d'une ligne (catégorie exacte, ou « Sans catégorie »). */
function rowFilter(row) {
  return row.uncategorized ? { uncategorized: true } : { categoryId: row.categoryId }
}

function rowTitle(row) {
  return `${row.category} — ${props.periodLabel}`
}

function openRow(row) {
  openTransactions(props.range.from, props.range.to, 'expense', rowFilter(row), rowTitle(row))
}

function render() {
  chart?.destroy()
  chart = null
  const rows = props.data
  if (!canvas.value || !rows.length) return
  const colors = readChartColors()
  const scale = readCategoryColorScale()
  chart = new Chart(canvas.value, {
    type: 'doughnut',
    data: {
      labels: rows.map(d => d.category),
      datasets: [{
        data: rows.map(d => d.total),
        backgroundColor: rows.map(row => categoryColorString(rowColor(row), scale)),
        // Séparation entre les parts = couleur de la carte (claire ou sombre selon le thème)
        borderColor: colors.surface,
        borderWidth: 3,
        hoverOffset: 5,
        spacing: 2
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: true,
      cutout: '74%',
      // Clic sur une part : mêmes transactions qu'un clic sur sa ligne du tableau.
      onClick: (_event, elements) => {
        if (elements.length) openRow(rows[elements[0].index])
      },
      onHover: (event, elements) => {
        event.native.target.style.cursor = elements.length ? 'pointer' : 'default'
      },
      plugins: {
        legend: { display: false },
        tooltip: tooltipOptions(colors, {
          padding: 11,
          displayColors: true,
          callbacks: {
            label: (ctx) => {
              const row = rows[ctx.dataIndex]
              return ` ${row.category} : ${formatAmount(row.total)} (${formatShare(row.percentage)})`
            }
          }
        })
      }
    }
  })
}

// Aussi quand les catégories arrivent ou changent : elles donnent les couleurs.
watch([() => props.data, effectiveTheme, () => dataStore.state.categories], render, { flush: 'post' })
onMounted(render)
onBeforeUnmount(() => chart?.destroy())
</script>

<style scoped>
.where-money-card {
  padding: 20px 22px;
  margin-bottom: 28px;
}
.where-money-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.card-subtitle { color: var(--text-muted); font-size: 12px; margin-bottom: 12px; }

/* Bascule mois affiché / 12 mois. */
.scope-toggle {
  display: inline-flex;
  flex-shrink: 0;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--ink-800);
}
.scope-option {
  padding: 3px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.scope-option:hover { color: var(--text-primary); }
.scope-option.active { background: var(--ink-900); color: var(--text-primary); box-shadow: var(--shadow-xs); }

.where-money-layout {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  gap: 32px;
  align-items: center;
}

.donut-wrap {
  position: relative;
  width: 176px;
  height: 176px;
  margin: 0 auto;
}
.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  pointer-events: none;
}
.donut-center-value {
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -.035em;
  /* Rend visible l'espace fine des milliers (« 5 065 € » et non « 5065 € »). */
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
  line-height: 1.2;
}
.donut-center-label {
  font-size: 11px;
  letter-spacing: .04em;
  color: var(--text-faint);
  margin-top: 3px;
}

.legend-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-right: 8px;
}

/* En-tête (25 px) + 5 lignes entières (35,75 px) : la coupure tombe entre
   deux lignes (à 190 px elle amputait la queue des virgules). */
.where-table-wrap {
  max-height: 204px;
  overflow-y: auto;
}
.where-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.where-table th {
  position: sticky;
  top: 0;
  background: var(--ink-900);
  text-align: left;
  padding: 0 8px 9px;
  font-size: 10px;
  text-transform: none;
  letter-spacing: .06em;
  color: var(--text-faint);
  font-weight: 700;
}
/* En-têtes « Montant » et « % » alignés à droite comme leurs chiffres */
.where-table th.num {
  text-align: right;
}
.where-table td {
  padding: 8px;
  border-bottom: 1px solid var(--grid-line);
}
.where-table tbody tr:hover td {
  background: var(--row-hover);
}
/* Barre de proportion : espace libre entre libellé et montant, plafonnée sur grand écran. */
.where-share-col {
  width: 34%;
  max-width: 320px;
}
.where-share-track {
  display: block;
  height: 6px;
  border-radius: 999px;
  background: var(--ink-700);
  overflow: hidden;
}
.where-share-fill {
  display: block;
  height: 100%;
  min-width: 2px;
  border-radius: inherit;
}
.where-table tbody tr:last-child td {
  border-bottom: none;
}
.where-pct {
  color: var(--text-muted);
  width: 52px;
}
.where-table tfoot td {
  padding-top: 12px;
  border-top: 2px solid var(--line);
  border-bottom: none;
  font-weight: 700;
}

@media (max-width: 640px) {
  .where-money-card { padding: 16px; }
  .where-money-layout {
    grid-template-columns: 1fr;
    gap: 16px;
    justify-items: center;
  }
  /* Sur mobile, la barre de proportion prendrait la place du libellé. */
  .where-share-col { display: none; }
  /* Zones de toucher agrandies sans changer le dessin. */
  .scope-option { position: relative; }
  .scope-option::after { content: ''; position: absolute; inset: -5px 0; }
}

@media print {
  /* Même marge que les autres cartes compactes du PDF. */
  .where-money-card { padding: 16px; }
  /* Seule l'option affichée reste, sans cadre de bouton. */
  .scope-option:not(.active) { display: none; }
  .scope-toggle {
    border: none;
    background: none;
  }
  /* La carte tient sur une page : hauteur plafonnée, et pas d'en-tête figé
     (artefact d'impression qui tronquait le « C » de « Catégorie »). */
  .where-table-wrap {
    max-height: 460px;
    overflow: hidden;
  }
  .where-table th {
    position: static;
  }
}
</style>

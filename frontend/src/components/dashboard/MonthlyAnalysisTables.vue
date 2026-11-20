<template>
  <div class="card monthly-analysis-card monthly-summary-card" :class="{ 'section-collapsed': summarySection.collapsed.value }">
    <div class="card-title has-subtitle"><SectionToggle section="resume-mensuel" label="Résumé mensuel" />Résumé mensuel — {{ periodLabel }}</div>
    <div class="section-body">
    <AnalysisLegend description="Revenus, dépenses et solde par mois." :has-forecast="hasForecastMonths" />

    <div v-if="analysis" class="monthly-analysis-wrap">
      <table v-grid-nav class="monthly-analysis-table" aria-describedby="grid-nav-hint">
        <thead>
          <tr>
            <th class="category-sticky">Catégorie</th>
            <th v-for="month in analysis.months" :key="month.key" class="num"
                :class="{ 'forecast-cell': month.forecast }"
                :title="month.forecast ? FORECAST_MONTH_TITLE : null">
              {{ month.label }}
            </th>
            <th class="num summary-col col-average">Moyenne</th>
            <th class="num summary-col col-total">Total</th>
            <th class="num summary-col col-previous" :title="previousColumnTitle">12 mois préc.<span v-if="yearOverYear.partial" class="yoy-partial" aria-hidden="true">*</span></th>
            <th class="num summary-col col-change" :title="CHANGE_COLUMN_TITLE">Évol.</th>
          </tr>
        </thead>

        <tbody>
          <tr v-for="row in summaryRows" :key="row.key" :class="{ 'monthly-balance-row': row.balance }">
            <td class="category-sticky summary-label">
              <strong v-if="row.balance">{{ row.rowLabel }}</strong>
              <template v-else>{{ row.rowLabel }}</template>
            </td>
            <td v-for="month in analysis.summary" :key="'summary-' + row.key + '-' + month.key" class="num"
                :class="[summaryAmountClass(row, month[row.key], true), anomalyFor(row.key, month.key), { 'forecast-cell': month.forecast }]"
                v-bind="monthCell(month, row.flow, NO_FILTER, row.label)">
              <component :is="row.balance ? 'strong' : 'span'">{{ formatAmount(month[row.key]) }}</component>
            </td>
            <td class="num summary-col col-average" :class="summaryAmountClass(row, row.average)" v-bind="periodCell(row.flow, NO_FILTER, row.label)">
              <component :is="row.balance ? 'strong' : 'span'">{{ formatAmount(row.average) }}</component>
            </td>
            <td class="num summary-col col-total" :class="summaryAmountClass(row, row.total)" v-bind="periodCell(row.flow, NO_FILTER, row.label)">
              <component :is="row.balance ? 'strong' : 'span'">{{ formatAmount(row.total) }}</component>
            </td>
            <td class="num summary-col col-previous" v-bind="previousCell(row.previous, row.flow, NO_FILTER, row.label)">
              <component :is="row.balance ? 'strong' : 'span'">{{ previousAmountText(row.previous) }}</component>
            </td>
            <td class="num summary-col col-change" :class="changeFor(row.changeKey).tone" :title="changeFor(row.changeKey).title"><component :is="row.balance ? 'strong' : 'span'">{{ changeFor(row.changeKey).text }}</component></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>
  </div>

  <div v-for="section in categorySections" :key="section.flow" class="card monthly-analysis-card" :class="{ 'section-collapsed': section.collapsed }">
    <div class="card-title has-subtitle"><SectionToggle :section="section.sectionId" :label="section.title" />{{ section.title }} — {{ periodLabel }}</div>
    <div class="section-body">
    <AnalysisLegend description="Évolution mensuelle par catégorie et sous-catégorie." :has-forecast="hasForecastMonths" />

    <div v-if="analysis" class="monthly-analysis-wrap">
      <table v-grid-nav class="monthly-analysis-table" aria-describedby="grid-nav-hint">
        <thead>
          <tr>
            <th class="category-sticky">Catégorie</th>
            <th v-for="month in analysis.months" :key="month.key" class="num"
                :class="{ 'forecast-cell': month.forecast }"
                :title="month.forecast ? FORECAST_MONTH_TITLE : null">
              {{ month.label }}
            </th>
            <th class="num summary-col col-average">Moyenne</th>
            <th class="num summary-col col-total">Total</th>
            <th class="num summary-col col-previous" :title="previousColumnTitle">12 mois préc.<span v-if="yearOverYear.partial" class="yoy-partial" aria-hidden="true">*</span></th>
            <th class="num summary-col col-change" :title="CHANGE_COLUMN_TITLE">Évol.</th>
          </tr>
        </thead>

        <tbody v-for="(group, i) in section.groups" :key="section.flow + '-' + group.key" :class="{ 'category-group-alt': i % 2 === 1 }">
          <tr class="category-row">
            <td class="category-sticky category-name">
              <button v-if="group.children.length" class="collapse-button"
                      @click="toggleGroup(section.flow, group.key)"
                      :aria-expanded="!isGroupCollapsed(section.flow, group.key)"
                      :aria-label="(isGroupCollapsed(section.flow, group.key) ? 'Développer ' : 'Replier ') + group.name"
                      :title="isGroupCollapsed(section.flow, group.key) ? 'Développer' : 'Replier'">
                <Icon :name="isGroupCollapsed(section.flow, group.key) ? 'chevron-right' : 'chevron-down'" size="sm" />
              </button>
              <span v-else class="collapse-placeholder"></span>
              <span class="legend-dot category-color" :style="groupColorStyle(group)" aria-hidden="true"></span>
              <strong>{{ iconPrefix(group.categoryId) }}{{ group.name }}</strong>
            </td>
            <td v-for="month in analysis.months" :key="month.key" class="num"
                :class="[{ 'zero-value': !Number(group.values[month.key]) }, anomalyFor(group, month.key), { 'forecast-cell': month.forecast }]"
                v-bind="monthCell(month, section.flow, groupCategoryFilter(group), group.name)">
              {{ formatAmount(group.values[month.key]) }}
            </td>
            <td class="num summary-col col-average" :class="{ 'zero-value': !Number(group.average) }"
                v-bind="periodCell(section.flow, groupCategoryFilter(group), group.name)">
              {{ formatAmount(group.average) }}
            </td>
            <td class="num summary-col col-total" :class="{ 'zero-value': !Number(group.total) }"
                v-bind="periodCell(section.flow, groupCategoryFilter(group), group.name)">
              {{ formatAmount(group.total) }}
            </td>
            <td class="num summary-col col-previous" v-bind="previousCell(group.previousTotal, section.flow, groupCategoryFilter(group), group.name)">
              {{ previousAmountText(group.previousTotal) }}
            </td>
            <td class="num summary-col col-change" :class="changeFor(group).tone" :title="changeFor(group).title">{{ changeFor(group).text }}</td>
          </tr>

          <template v-if="!isGroupCollapsed(section.flow, group.key)">
            <tr v-for="child in group.children" :key="section.flow + '-child-' + child.key"
                class="subcategory-row">
              <td class="category-sticky category-name subcategory-name">
                └ {{ iconPrefix(child.categoryId) }}{{ child.name }}
              </td>
              <td v-for="month in analysis.months" :key="month.key" class="num"
                  :class="[{ 'zero-value': !Number(child.values[month.key]) }, anomalyFor(child, month.key), { 'forecast-cell': month.forecast }]"
                  v-bind="monthCell(month, section.flow, childCategoryFilter(child), child.name)">
                {{ formatAmount(child.values[month.key]) }}
              </td>
              <td class="num summary-col col-average" :class="{ 'zero-value': !Number(child.average) }"
                  v-bind="periodCell(section.flow, childCategoryFilter(child), child.name)">
                {{ formatAmount(child.average) }}
              </td>
              <td class="num summary-col col-total" :class="{ 'zero-value': !Number(child.total) }"
                  v-bind="periodCell(section.flow, childCategoryFilter(child), child.name)">
                {{ formatAmount(child.total) }}
              </td>
              <td class="num summary-col col-previous" v-bind="previousCell(child.previousTotal, section.flow, childCategoryFilter(child), child.name)">
                {{ previousAmountText(child.previousTotal) }}
              </td>
              <td class="num summary-col col-change" :class="changeFor(child).tone" :title="changeFor(child).title">{{ changeFor(child).text }}</td>
            </tr>
          </template>
        </tbody>

        <tbody>
          <tr class="total-row" :class="section.flow + '-total-row'">
            <td class="category-sticky"><strong>{{ section.total.name }}</strong></td>
            <td v-for="month in analysis.months" :key="month.key" class="num"
                :class="{ 'forecast-cell': month.forecast }"
                v-bind="monthCell(month, section.flow, NO_FILTER, section.totalLabel)">
              <strong>{{ formatAmount(section.total.values[month.key]) }}</strong>
            </td>
            <td class="num summary-col col-average" v-bind="periodCell(section.flow, NO_FILTER, section.totalLabel)">
              <strong>{{ formatAmount(section.total.average) }}</strong>
            </td>
            <td class="num summary-col col-total" v-bind="periodCell(section.flow, NO_FILTER, section.totalLabel)">
              <strong>{{ formatAmount(section.total.total) }}</strong>
            </td>
            <td class="num summary-col col-previous" v-bind="previousCell(section.total.previousTotal, section.flow, NO_FILTER, section.totalLabel)">
              <strong>{{ previousAmountText(section.total.previousTotal) }}</strong>
            </td>
            <td class="num summary-col col-change" :class="changeFor(section.total).tone" :title="changeFor(section.total).title"><strong>{{ changeFor(section.total).text }}</strong></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>
  </div>

  <p id="grid-nav-hint" class="sr-only">
    Flèches pour passer d'un montant à l'autre ; le détail des transactions s'affiche pour le montant sélectionné, Échap pour le fermer.
  </p>
</template>

<script setup>
/**
 * Tableaux d'analyse mensuelle du tableau de bord (voir
 * ServiceRapport#monthlyCategoryAnalysis) : résumé revenus / dépenses / solde,
 * puis dépenses et revenus par catégorie et sous-catégorie, sur 12 mois, avec
 * moyenne, total et comparaison aux mêmes 12 mois de l'année précédente.
 * Chaque montant donne le détail de ses transactions au survol et ouvre la
 * liste filtrée au clic (voir services/cellDetail.js).
 */
import { computed, ref } from 'vue'
import { formatAmount } from '../../services/format'
import { useCellDetail } from '../../services/cellDetail'
import { useCategoryIconPrefix } from '../../services/categoryIcons'
import { categoryColorStyle, useCategoryColors, UNCATEGORIZED_COLOR } from '../../services/categoryColors'
import {
  analysisGroup,
  monthTitleFromKey,
  monthWindowLabel,
  rowAnomalyClasses,
  yearOverYearChange,
  yearOverYearCoverage
} from '../../services/dashboardAnalysis'
import AnalysisLegend from '../AnalysisLegend.vue'
import Icon from '../Icon.vue'
import SectionToggle from './SectionToggle.vue'
import { useCollapsibleSection } from '../../services/collapsibleSections'

const props = defineProps({
  /** Rapport d'analyse mensuelle, null pendant le premier chargement. */
  analysis: { type: Object, default: null },
  /** Fenêtre de 12 mois en clair, pour les titres (« 12 derniers mois », « déc. 2025 – nov. 2026 »). */
  periodLabel: { type: String, required: true }
})

const {
  scheduleHover, scheduleMonthHover, onCellLeave,
  fetchCellTransactions, fetchMonthCellTransactions,
  openTransactions, openMonthTransactions
} = useCellDetail()
const iconPrefix = useCategoryIconPrefix()
const categoryColor = useCategoryColors()

/** Pastille de la couleur fixe de la catégorie (la même que dans « Où va mon argent ? »). */
function groupColorStyle(group) {
  return categoryColorStyle(group.uncategorized ? UNCATEGORIZED_COLOR : categoryColor(group.categoryId))
}

const FORECAST_MONTH_TITLE = 'Mois pas encore terminé : prévisionnel (moyenne des mois précédents)'
const CHANGE_COLUMN_TITLE = 'Évolution par rapport aux mêmes 12 mois un an plus tôt'
const NO_FILTER = Object.freeze({})

const analysisMonths = computed(() => props.analysis?.months || [])
const incomeGroups = computed(() => analysisGroup(props.analysis?.income || [], analysisMonths.value))
const expenseGroups = computed(() => analysisGroup(props.analysis?.expenses || [], analysisMonths.value))

/** Les deux tableaux « … par catégorie » (dépenses puis revenus), rendus par le même gabarit ; affichés même pendant le chargement. */
const summarySection = useCollapsibleSection('resume-mensuel')
const expenseSection = useCollapsibleSection('depenses-par-categorie')
const incomeSection = useCollapsibleSection('revenus-par-categorie')

const categorySections = computed(() => [
  { flow: 'expense', sectionId: 'depenses-par-categorie', collapsed: expenseSection.collapsed.value, title: 'Dépenses par catégorie', groups: expenseGroups.value, total: props.analysis?.expenseTotal, totalLabel: 'Total dépenses' },
  { flow: 'income', sectionId: 'revenus-par-categorie', collapsed: incomeSection.collapsed.value, title: 'Revenus par catégorie', groups: incomeGroups.value, total: props.analysis?.incomeTotal, totalLabel: 'Total revenus' }
])

/** Au moins un mois prévisionnel dans la fenêtre (navigation vers le futur comprise). */
const hasForecastMonths = computed(() => analysisMonths.value.some(m => m.forecast))

/** Solde moyen et total sur 12 mois (ligne « Solde du mois »). */
const netAverage = computed(() => Number(props.analysis?.incomeTotal.average) - Number(props.analysis?.expenseTotal.average))
const netTotal = computed(() => Number(props.analysis?.incomeTotal.total) - Number(props.analysis?.expenseTotal.total))

// --- Comparaison avec l'année précédente (colonnes « 12 mois préc. » et « Évol. ») ---
const yearOverYear = computed(() => yearOverYearCoverage(props.analysis))
const previousPeriodLabel = computed(() => (props.analysis?.previousFrom
  ? monthWindowLabel(props.analysis.previousFrom, props.analysis.previousTo)
  : ''))
const previousNetTotal = computed(() =>
  Number(props.analysis?.incomeTotal.previousTotal) - Number(props.analysis?.expenseTotal.previousTotal))

/**
 * Lignes du « Résumé mensuel ». key : champ du mois et clé d'anomalie ;
 * changeKey : clé de yearOverYearChanges ; balance : ligne de solde (en gras, teintée selon le signe).
 */
const summaryRows = computed(() => {
  const analysis = props.analysis
  if (!analysis) return []
  const { incomeTotal, expenseTotal } = analysis
  return [
    { key: 'income', label: 'Revenus', rowLabel: 'Revenus', flow: 'income', average: incomeTotal.average, total: incomeTotal.total, previous: incomeTotal.previousTotal, changeKey: incomeTotal, balance: false },
    { key: 'expenses', label: 'Dépenses', rowLabel: 'Dépenses', flow: 'expense', average: expenseTotal.average, total: expenseTotal.total, previous: expenseTotal.previousTotal, changeKey: expenseTotal, balance: false },
    { key: 'net', label: 'Solde', rowLabel: 'Solde du mois', flow: null, average: netAverage.value, total: netTotal.value, previous: previousNetTotal.value, changeKey: 'net', balance: true }
  ]
})

/**
 * Teinte d'une case de solde : vert si positif, corail si négatif, estompée si
 * nulle (un mois sans mouvement n'est pas un « bon » mois).
 */
function balanceToneClass(value) {
  const amount = Number(value)
  if (!amount) return 'zero-value'
  return amount > 0 ? 'amount positive' : 'amount negative'
}

/** Classe d'un montant du résumé : solde teinté ; ailleurs, case de mois estompée si nulle. */
function summaryAmountClass(row, value, dimZero = false) {
  if (row.balance) return balanceToneClass(value)
  return dimZero ? { 'zero-value': !Number(value) } : null
}

/** Infobulle de l'en-tête « 12 mois préc. » : période comparée, et historique incomplet le cas échéant. */
const previousColumnTitle = computed(() => {
  if (!yearOverYear.value.available) return 'Pas encore d\'historique sur l\'année précédente : rien à comparer.'
  const title = `Mêmes 12 mois un an plus tôt (${previousPeriodLabel.value})`
  if (!yearOverYear.value.partial) return title
  const start = new Date(`${props.analysis.historyStart}T00:00:00`).toLocaleDateString('fr-FR')
  return `${title} — historique incomplet : vos données commencent le ${start}.`
})

/** Évolution de chaque ligne (clé : l'objet ligne, ou 'net'), calculée une fois par chargement. */
const yearOverYearChanges = computed(() => {
  const changes = new Map()
  const analysis = props.analysis
  if (!analysis || !yearOverYear.value.available) return changes
  for (const [groups, kind] of [[expenseGroups.value, 'expense'], [incomeGroups.value, 'income']]) {
    for (const group of groups) {
      changes.set(group, yearOverYearChange(group.total, group.previousTotal, kind))
      for (const child of group.children) changes.set(child, yearOverYearChange(child.total, child.previousTotal, kind))
    }
  }
  changes.set(analysis.incomeTotal, yearOverYearChange(analysis.incomeTotal.total, analysis.incomeTotal.previousTotal, 'income'))
  changes.set(analysis.expenseTotal, yearOverYearChange(analysis.expenseTotal.total, analysis.expenseTotal.previousTotal, 'expense'))
  changes.set('net', yearOverYearChange(netTotal.value, previousNetTotal.value, 'net'))
  return changes
})
const NO_CHANGE = Object.freeze({ text: '—', tone: '', title: "Pas encore d'historique sur l'année précédente" })

function changeFor(row) {
  return yearOverYearChanges.value.get(row) ?? NO_CHANGE
}

/** Montant de la colonne « 12 mois préc. » (tiret sans historique en face). */
function previousAmountText(previous) {
  return yearOverYear.value.available ? formatAmount(previous) : '—'
}

/**
 * Cases atypiques de chaque ligne (clé : l'objet ligne, ou 'income' /
 * 'expenses' / 'net' pour le résumé), calculées une fois par chargement.
 */
const anomalyClasses = computed(() => {
  const classes = new Map()
  const analysis = props.analysis
  if (!analysis) return classes
  for (const [groups, higherIsBetter] of [[expenseGroups.value, false], [incomeGroups.value, true]]) {
    for (const group of groups) {
      classes.set(group, rowAnomalyClasses(group.values, higherIsBetter))
      for (const child of group.children) classes.set(child, rowAnomalyClasses(child.values, higherIsBetter))
    }
  }
  for (const [field, higherIsBetter] of [['income', true], ['expenses', false], ['net', true]]) {
    classes.set(field, rowAnomalyClasses(Object.fromEntries(analysis.summary.map(m => [m.key, m[field]])), higherIsBetter))
  }
  return classes
})
const NO_ANOMALY = Object.freeze({})

function anomalyFor(row, monthKey) {
  return anomalyClasses.value.get(row)?.[monthKey] ?? NO_ANOMALY
}

// --- Catégories repliées (une liste par sens) ---
const collapsedGroups = ref({ income: {}, expense: {} })

function toggleGroup(flow, key) {
  collapsedGroups.value[flow][key] = !collapsedGroups.value[flow][key]
}
function isGroupCollapsed(flow, key) {
  return !!collapsedGroups.value[flow][key]
}

/** Filtre d'une ligne « catégorie racine » (ou « Sans catégorie »). */
function groupCategoryFilter(group) {
  return group.uncategorized ? { uncategorized: true } : { rootCategoryId: group.categoryId }
}

/** Filtre d'une ligne « sous-catégorie » (toujours une feuille exacte). */
function childCategoryFilter(child) {
  return { categoryId: child.categoryId }
}

// --- Comportement des cases : détail au survol, transactions au clic ---

/** Case d'un mois ; sans effet sur un mois prévisionnel (pas encore de transactions réelles). */
function monthCell(month, flow, filters, name) {
  const title = `${name} — ${monthTitleFromKey(month.key)}`
  return {
    onMouseenter: (event) => scheduleMonthHover(event, title, () => fetchMonthCellTransactions(month.key, flow, filters), month.forecast),
    onMouseleave: onCellLeave,
    onClick: () => openMonthTransactions(month.key, flow, filters, title, month.forecast)
  }
}

/** Case « Moyenne » ou « Total » : toute la fenêtre de 12 mois. */
function periodCell(flow, filters, name) {
  const { from, to } = props.analysis
  const title = `${name} — ${props.periodLabel}`
  return {
    onMouseenter: (event) => scheduleHover(event, title, () => fetchCellTransactions(from, to, flow, filters)),
    onMouseleave: onCellLeave,
    onClick: () => openTransactions(from, to, flow, filters, title)
  }
}

/** Case « 12 mois préc. » : même comportement, sur la période précédente. */
function previousCell(previous, flow, filters, name) {
  const analysis = props.analysis
  if (!analysis || !yearOverYear.value.available) return { class: 'zero-value' }
  const title = `${name} — ${previousPeriodLabel.value}`
  return {
    class: { 'zero-value': !Number(previous) },
    onMouseenter: (event) => scheduleHover(event, title,
      () => fetchCellTransactions(analysis.previousFrom, analysis.previousTo, flow, filters)),
    onMouseleave: onCellLeave,
    onClick: () => openTransactions(analysis.previousFrom, analysis.previousTo, flow, filters, title)
  }
}
</script>

<style scoped>
.monthly-analysis-card {
  margin-bottom: 28px;
}

.monthly-analysis-wrap {
  width: 100%;
  overflow: auto;
  max-height: 720px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.monthly-analysis-table {
  width: max-content;
  min-width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}

.monthly-analysis-table th,
.monthly-analysis-table td {
  /* 6px latéraux : les 12 mois + catégorie + synthèses tiennent sans défilement sur un écran large. */
  padding: 8px 6px;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.monthly-analysis-table thead th {
  position: sticky;
  top: 0;
  z-index: 4;
  padding-top: 11px;
  padding-bottom: 11px;
  background: var(--table-head-bg);
  color: var(--table-head-text);
  font-size: 10px;
  text-transform: none;
  letter-spacing: .05em;
  /* Séparation nette entre l'en-tête figé et le contenu qui défile dessous. */
  box-shadow: 0 1px 0 var(--line);
}

.monthly-analysis-table .category-sticky {
  position: sticky;
  left: 0;
  z-index: 3;
  min-width: 160px;
  text-align: left;
  font-weight: 600;
  background: var(--ink-900);
  border-right: 1px solid var(--line);
}

.monthly-analysis-table thead .category-sticky {
  z-index: 6;
}

.monthly-analysis-table .num {
  min-width: 68px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* Montants cliquables : ouvrent la liste des transactions correspondantes. */
.monthly-analysis-table td.num:not(.forecast-cell) { cursor: pointer; }
.monthly-analysis-table td.num:not(.forecast-cell):hover { text-decoration: underline; text-underline-offset: 3px; }

/* Cellule atteinte au clavier (flèches, voir directives/gridNav.js) */
.monthly-analysis-table td.num:focus-visible {
  outline: 2px solid var(--azure);
  outline-offset: -2px;
  border-radius: 4px;
}

/* Montants à 0 : atténués par la couleur du texte (pas par opacity, qui
   rendait aussi le fond translucide sous les colonnes figées). */
.monthly-analysis-table .num.zero-value {
  color: color-mix(in srgb, var(--text-faint) 45%, transparent);
}

/* Mois pas encore terminé : prévisionnel en italique atténué, placé après
   .zero-value pour rester visible même à 0. */
.monthly-analysis-table .num.forecast-cell,
.monthly-analysis-table th.forecast-cell {
  color: var(--text-muted);
  font-style: italic;
}

/* Case qui s'écarte fortement de la moyenne de sa ligne : liseret corail
   (vert si l'écart est favorable). box-shadow inset : ne décale pas les voisines. */
.monthly-analysis-table .num.monthly-anomaly {
  box-shadow: inset 0 0 0 2px var(--coral);
  color: var(--coral);
  font-weight: 700;
}
.monthly-analysis-table .num.monthly-anomaly.anomaly-favorable {
  box-shadow: inset 0 0 0 2px var(--mint);
  color: var(--mint);
}

/* « Moyenne » / « Total » : colonnes d'agrégat sur fond gris. Deux classes
   (.num.summary-col) pour l'emporter sur le fond des lignes de catégorie. */
.monthly-analysis-table .num.summary-col {
  background: var(--ink-800);
  font-weight: 600;
}
.monthly-analysis-table .col-average {
  border-left: 2px solid var(--line);
}
/* Cellules figées de l'en-tête : même bandeau que le reste du thead. */
.monthly-analysis-table thead th.category-sticky,
.monthly-analysis-table thead th.num.summary-col { background: var(--table-head-bg); }

/* « Évol. » : couleur selon le sens de l'écart (voir yearOverYearChange). */
.monthly-analysis-table .num.col-change.good { color: var(--mint); }
.monthly-analysis-table .num.col-change.bad { color: var(--coral); }
/* Ligne absente l'an dernier (●) ou tombée à zéro (○) : marqueurs dessinés en
   CSS, le texte masqué (font-size: 0) reste lu par les lecteurs d'écran. */
.monthly-analysis-table .num.col-change.new,
.monthly-analysis-table .num.col-change.gone { color: var(--text-faint); font-size: 0; cursor: help; }
.monthly-analysis-table .num.col-change.new::after,
.monthly-analysis-table .num.col-change.gone::after {
  content: '';
  display: inline-block;
  width: 7px;
  height: 7px;
  box-sizing: border-box;
  border: 1.5px solid currentColor;
  border-radius: 50%;
}
.monthly-analysis-table .num.col-change.new::after { background: currentColor; }
/* Historique incomplet sur l'année précédente (voir previousColumnTitle). */
.yoy-partial { margin-left: 2px; color: var(--amber); }

/* Colonnes de synthèse figées ensemble à droite pendant le défilement
   horizontal (largeurs fixes = décalages des suivantes). Pas sur petit écran :
   ~370 px figés ne laisseraient plus voir aucun mois. */
.monthly-analysis-table {
  --col-change-width: 70px;
  --col-previous-width: 100px;
  --summary-total-width: 100px;
}
.monthly-analysis-table .col-change { width: var(--col-change-width); min-width: var(--col-change-width); }
.monthly-analysis-table .col-previous { width: var(--col-previous-width); min-width: var(--col-previous-width); }
.monthly-analysis-table .col-total { width: var(--summary-total-width); min-width: var(--summary-total-width); }
@media (min-width: 1100px) {
  .monthly-analysis-table .col-change { position: sticky; right: 0; }
  .monthly-analysis-table .col-previous { position: sticky; right: var(--col-change-width); }
  .monthly-analysis-table .col-total {
    position: sticky;
    right: calc(var(--col-change-width) + var(--col-previous-width));
  }
  .monthly-analysis-table .col-average {
    position: sticky;
    right: calc(var(--col-change-width) + var(--col-previous-width) + var(--summary-total-width));
    box-shadow: var(--sticky-edge-shadow);
  }
  .monthly-analysis-table th.col-average,
  .monthly-analysis-table th.col-total,
  .monthly-analysis-table th.col-previous,
  .monthly-analysis-table th.col-change {
    z-index: 7;
  }
}

.monthly-analysis-table tbody tr.category-row td {
  background: var(--ink-900);
  font-weight: 600;
}

/* Fond alterné par groupe catégorie + sous-catégories (un <tbody> par groupe). */
.monthly-analysis-table tbody.category-group-alt tr.category-row td,
.monthly-analysis-table tbody.category-group-alt tr.subcategory-row td {
  background: var(--ink-950);
}
.monthly-analysis-table tbody.category-group-alt .num.summary-col {
  background: var(--ink-800);
}

.monthly-analysis-table tbody tr.category-row:hover td,
.monthly-analysis-table tbody tr.subcategory-row:hover td {
  background: var(--ink-800);
}
.monthly-analysis-table tbody tr:hover td { background-color: var(--row-hover); }

.monthly-analysis-table tbody tr.subcategory-row td {
  color: var(--text-muted);
}

.monthly-analysis-table tbody tr.subcategory-row .category-sticky {
  padding-left: 22px;
}

.monthly-analysis-table .total-row td {
  background: var(--ink-800);
  border-top: 2px solid var(--line);
  border-bottom: 0;
}

.monthly-analysis-table .income-total-row td {
  color: var(--mint);
}

.monthly-analysis-table .expense-total-row td {
  color: var(--coral);
}

.monthly-analysis-table .monthly-balance-row td {
  border-bottom: 0;
  background: var(--ink-800);
}

.collapse-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  vertical-align: middle;
  width: 18px;
  height: 18px;
  padding: 0;
  margin-right: 4px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 12px;
}
.collapse-button:hover {
  background: var(--ink-700);
  color: var(--text-primary);
}
/* Téléphone : zone de toucher agrandie sans changer le dessin. */
@media (max-width: 640px) {
  .collapse-button { position: relative; }
  .collapse-button::after { content: ''; position: absolute; inset: -5px; }
}

.collapse-placeholder {
  display: inline-block;
  width: 18px;
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 50%;
  vertical-align: middle;
}

/* PDF : le tableau s'étale sur plusieurs pages (en-tête répété), sans
   plafond ni positionnement figé, resserré pour tenir dans la largeur d'une
   page A4 paysage (≈ 1 060 px). */
@media print {
  .monthly-analysis-wrap {
    max-height: none;
    overflow: visible;
  }
  .monthly-analysis-table thead th,
  .monthly-analysis-table .category-sticky,
  .monthly-analysis-table .summary-col {
    position: static;
    box-shadow: none;
  }
  .monthly-analysis-table th,
  .monthly-analysis-table td {
    padding: 4px 3px;
  }
  /* Colonnes de synthèse : leurs règles écran (col-*) sont plus spécifiques et
     les laissaient collées à droite, par-dessus les mois les plus récents. */
  .monthly-analysis-table .col-average,
  .monthly-analysis-table .col-total,
  .monthly-analysis-table .col-previous,
  .monthly-analysis-table .col-change {
    position: static;
    width: auto;
    min-width: 0;
    box-shadow: none;
  }
  /* Seule l'évolution figure dans le PDF, pour tenir dans la largeur. */
  .monthly-analysis-table .col-previous { display: none; }
  /* 10 px depuis la colonne « Évol. » : le tableau des dépenses dépassait sinon (mesuré). */
  .monthly-analysis-table {
    width: 100%;
    font-size: 10px;
  }
  .monthly-analysis-table .num,
  .monthly-analysis-table .category-sticky {
    min-width: 0;
  }
  /* Boutons replier/déplier : sans objet sur papier (tout est déplié). */
  .monthly-analysis-table .collapse-button,
  .monthly-analysis-table .collapse-placeholder {
    display: none;
  }
}
</style>

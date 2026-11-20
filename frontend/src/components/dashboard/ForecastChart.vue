<template>
  <div class="chart-header">
    <div class="stat-label chart-header-title">
      <SectionToggle section="solde" label="Évolution du solde" />
      Évolution du solde — historique, présent &amp; prévisionnel
      <button
        type="button"
        class="chart-info-btn"
        :class="{ active: showForecastNote }"
        :aria-expanded="showForecastNote"
        aria-controls="forecast-note"
        aria-label="Comment le prévisionnel est-il calculé ?"
        title="Comment le prévisionnel est-il calculé ?"
        @click="showForecastNote = !showForecastNote"
      >
        <Icon name="info" size="sm" />
      </button>
    </div>
    <button
      v-if="hasLoans"
      type="button"
      role="switch"
      class="chart-mode-toggle"
      :class="{ 'is-on': loansAddedToBalance }"
      :aria-checked="loansAddedToBalance"
      title="Activé : le capital déjà remboursé de vos prêts est ajouté au solde (une seule courbe). Désactivé : solde et capital des prêts en deux courbes."
      @click="loansAddedToBalance = !loansAddedToBalance"
    >
      <span class="toggle-track"><span class="toggle-thumb"></span></span>
      <span class="toggle-label">Additionner les prêts</span>
    </button>
    <span v-if="hasLoans" class="print-only chart-print-caption">
      {{ loansAddedToBalance ? 'Solde + capital déjà remboursé des prêts' : 'Solde et capital des prêts' }}
    </span>
  </div>
  <div class="section-body">
    <canvas ref="canvas" height="90"></canvas>
    <p v-show="showForecastNote" id="forecast-note" class="chart-note">
    Le prévisionnel est estimé à partir de la variation moyenne du solde observée sur les 12 derniers mois,
    soit <strong :class="monthlyTrend >= 0 ? 'amount positive' : 'amount negative'">{{ formatAmount(monthlyTrend) }}</strong>
    ajoutés au solde chaque mois sur la partie prévisionnelle.
    <template v-if="hasLoans">
      La courbe inclut aussi le capital déjà remboursé sur l'ensemble de vos prêts (connu à l'avance,
      indépendant de la projection de solde).
    </template>
    </p>
  </div>
</template>

<script setup>
/**
 * Solde sur 12 mois passés + 12 mois prévisionnels (voir
 * ServiceRapport#balanceForecast). Le prévisionnel est tracé en gris
 * pointillé (--chart-forecast) : un simple éclaircissement de la couleur
 * réelle ne se distinguait pas sans zoomer. Avec des prêts, l'interrupteur
 * « Additionner les prêts » ajoute le capital déjà remboursé au solde (une
 * courbe) ou affiche les deux courbes séparément.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Chart from '../../services/chart'
import { areaGradient, axisOptions, CHART_FONT_FAMILY, readChartColors, tooltipOptions } from '../../services/chartTheme'
import { effectiveTheme } from '../../services/theme'
import { formatAmount } from '../../services/format'
import Icon from '../Icon.vue'
import SectionToggle from './SectionToggle.vue'

const props = defineProps({
  /** Points { label, balance, type: 'historique' | 'previsionnel' } du rapport. */
  points: { type: Array, required: true },
  /** Capital déjà remboursé des prêts, aligné sur points (null sans prêt, voir buildLoanOwnedSeries). */
  loanOwnedSeries: { type: Array, default: null },
  /** Variation mensuelle moyenne appliquée au prévisionnel (champ monthlyTrend du rapport). */
  monthlyTrend: { type: Number, default: 0 }
})

const canvas = ref(null)
const hasLoans = computed(() => !!props.loanOwnedSeries)
/** Interrupteur « Additionner les prêts » : une seule courbe solde + capital, ou deux courbes. */
const loansAddedToBalance = ref(true)
/** Explication du prévisionnel, repliée par défaut (bouton ⓘ du titre) pour alléger la carte. */
const showForecastNote = ref(false)

const SMALL_TICK_FONT = { family: CHART_FONT_FAMILY, size: 10 }
const tickFormatter = new Intl.NumberFormat('fr-FR')
let chart = null

/**
 * Paire de datasets « réel » (trait plein) / « prévisionnel » (pointillé gris)
 * d'une série, qui se rejoignent exactement sur junctionIndex (même valeur des
 * deux côtés, le reste à null). isForecastVariant exclut le second de la
 * légende et de l'infobulle au point de jonction.
 */
function pastFutureDatasets(label, data, junctionIndex, color, futureColor) {
  const pointRadiusAtJunction = (ctx) => (ctx.dataIndex === junctionIndex ? 4 : 0)
  const common = { fill: true, tension: 0.25, pointRadius: pointRadiusAtJunction, borderWidth: 2, spanGaps: false }
  return [
    {
      ...common,
      label,
      data: data.map((v, i) => (i <= junctionIndex ? v : null)),
      borderColor: color,
      backgroundColor: areaGradient(color, 0.2),
      pointBackgroundColor: color
    },
    {
      ...common,
      label: `${label} (prévisionnel)`,
      isForecastVariant: true,
      data: data.map((v, i) => (i >= junctionIndex ? v : null)),
      borderColor: futureColor,
      backgroundColor: areaGradient(futureColor, 0.14),
      borderDash: [6, 5],
      pointBackgroundColor: futureColor
    }
  ]
}

/** Détail au survol : la somme affichée garde ses deux composantes visibles. */
function tooltipLabelFor(points, loanOwnedSeries, showSeparate) {
  if (showSeparate) return (ctx) => `${ctx.dataset.label} : ${formatAmount(ctx.parsed.y)}`
  return (ctx) => {
    if (!loanOwnedSeries) return `Solde : ${formatAmount(ctx.parsed.y)}`
    const balance = Number(points[ctx.dataIndex].balance)
    const loanCapital = Number(loanOwnedSeries[ctx.dataIndex])
    return [
      `Solde : ${formatAmount(balance)}`,
      `Capital des prêts : ${formatAmount(loanCapital)}`,
      `Total : ${formatAmount(balance + loanCapital)}`
    ]
  }
}

function render() {
  chart?.destroy()
  chart = null
  const { points, loanOwnedSeries } = props
  if (!canvas.value || !points.length) return

  const firstForecastIndex = points.findIndex(p => p.type === 'previsionnel')
  // Fenêtre entièrement passée (navigation loin en arrière) : pas de jonction,
  // toute la courbe reste pleine.
  const junctionIndex = firstForecastIndex === -1 ? points.length - 1 : firstForecastIndex - 1
  const showSeparate = !!loanOwnedSeries && !loansAddedToBalance.value
  const colors = readChartColors()

  const datasets = showSeparate
    ? [
        ...pastFutureDatasets('Solde', points.map(p => Number(p.balance)), junctionIndex, colors.accent, colors.forecast),
        ...pastFutureDatasets('Capital des prêts', loanOwnedSeries.map(Number), junctionIndex, colors.secondary, colors.forecast)
      ]
    : pastFutureDatasets(
        'Solde',
        points.map((p, i) => Number(p.balance) + (loanOwnedSeries ? Number(loanOwnedSeries[i]) : 0)),
        junctionIndex,
        colors.accent,
        colors.forecast
      )

  chart = new Chart(canvas.value, {
    type: 'line',
    data: { labels: points.map(p => p.label), datasets },
    options: {
      responsive: true,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: {
          display: showSeparate,
          labels: {
            usePointStyle: true,
            pointStyle: 'circle',
            boxWidth: 8,
            boxHeight: 8,
            padding: 18,
            color: colors.legend,
            font: { family: CHART_FONT_FAMILY, size: 11, weight: '600' },
            filter: (legendItem, data) => !data.datasets[legendItem.datasetIndex]?.isForecastVariant
          }
        },
        tooltip: tooltipOptions(colors, {
          displayColors: true,
          // Au point de jonction, le prévisionnel répète la valeur du réel : une seule ligne.
          filter: (item) => !(item.dataset.isForecastVariant && item.dataIndex === junctionIndex),
          callbacks: { label: tooltipLabelFor(points, loanOwnedSeries, showSeparate) }
        })
      },
      scales: {
        x: axisOptions(colors, { ticks: { maxRotation: 0, autoSkip: true, font: SMALL_TICK_FONT } }),
        y: axisOptions(colors, { withGrid: true, ticks: { padding: 10, callback: (v) => tickFormatter.format(v) } })
      }
    }
  })
}

// Couleurs lues dans les variables CSS au moment du dessin (voir chartTheme.js) :
// on redessine aussi au changement de thème. flush 'post' : canvas déjà dans le DOM.
watch([() => props.points, () => props.loanOwnedSeries, loansAddedToBalance, effectiveTheme], render, { flush: 'post' })
onMounted(render)
onBeforeUnmount(() => chart?.destroy())
</script>

<style scoped>
.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.chart-header-title { display: inline-flex; align-items: center; gap: 6px; margin-bottom: 0; }
canvas { max-height: 310px; }

/* Bouton ⓘ qui déplie l'explication du prévisionnel. */
.chart-info-btn {
  display: inline-flex;
  padding: 3px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-faint);
  cursor: pointer;
}
.chart-info-btn:hover,
.chart-info-btn.active { color: var(--azure); background: var(--azure-dim); }

.chart-mode-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--ink-800);
  border-radius: var(--radius-md);
  padding: 4px 10px 4px 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-muted);
  flex-shrink: 0;
}
.chart-mode-toggle:hover { background: var(--ink-700); }
.toggle-track {
  position: relative;
  width: 34px;
  height: 18px;
  border-radius: 999px;
  background: var(--line);
  flex-shrink: 0;
  transition: background 0.2s ease;
}
.chart-mode-toggle.is-on .toggle-track { background: var(--violet); }
.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--ink-900);
  box-shadow: var(--shadow-sm);
  transition: transform 0.2s ease;
}
.chart-mode-toggle.is-on .toggle-thumb { transform: translateX(16px); }
.toggle-label {
  font-weight: 600;
  white-space: nowrap;
}

/* Téléphone : zone de toucher agrandie sans changer le dessin. */
@media (max-width: 640px) {
  .chart-info-btn { position: relative; }
  .chart-info-btn::after { content: ''; position: absolute; inset: -5px; }
}

/* PDF : interrupteur et bouton d'aide remplacés par une légende fixe. */
@media print {
  .chart-mode-toggle,
  .chart-info-btn {
    display: none;
  }
  .chart-print-caption {
    font-size: 12px;
    font-weight: 600;
    color: var(--text-muted);
  }
}
</style>

<template>
  <div class="savings-chart-wrap">
    <canvas ref="canvas"></canvas>
  </div>
</template>

<script setup>
/**
 * Barres d'épargne mensuelle (revenus − dépenses du mois, voir
 * ServiceRapport#monthlySavings) : couleur d'accent si positive (l'épargne
 * n'est pas un revenu, d'où le bleu plutôt que le vert), rouge si négative,
 * gris prévisionnel pour un mois pas encore terminé. Clic sur une barre :
 * transactions du mois.
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Chart from '../../services/chart'
import { axisOptions, barGradient, CHART_FONT_FAMILY, readChartColors, tooltipOptions } from '../../services/chartTheme'
import { effectiveTheme } from '../../services/theme'
import { formatAmount } from '../../services/format'
import { monthTitleFromKey } from '../../services/dashboardAnalysis'
import { useCellDetail } from '../../services/cellDetail'

const props = defineProps({
  /** Points { date, label, savings, forecast } du rapport. */
  points: { type: Array, required: true }
})

const { openMonthTransactions } = useCellDetail()
const canvas = ref(null)
const SMALL_TICK_FONT = { family: CHART_FONT_FAMILY, size: 10 }
let chart = null

function render() {
  chart?.destroy()
  chart = null
  const { points } = props
  if (!canvas.value || !points.length) return
  const colors = readChartColors()
  const savedFill = barGradient(colors.accent)
  const barFill = (ctx) => {
    const point = points[ctx.dataIndex]
    if (point.forecast) return colors.forecast
    return Number(point.savings) >= 0 ? savedFill(ctx) : colors.negative
  }
  chart = new Chart(canvas.value, {
    type: 'bar',
    data: {
      labels: points.map(p => p.label),
      datasets: [{
        label: 'Épargne',
        data: points.map(p => p.savings),
        backgroundColor: barFill,
        borderRadius: 4,
        maxBarThickness: 34
      }]
    },
    options: {
      responsive: true,
      // Remplit toute la hauteur de la carte, étirée à celle des cartes périodiques voisines.
      maintainAspectRatio: false,
      onClick: (_event, elements) => {
        const point = elements.length ? points[elements[0].index] : null
        if (point) openMonthTransactions(point.date, null, {}, `Épargne — ${monthTitleFromKey(point.date)}`, point.forecast)
      },
      onHover: (event, elements) => {
        const clickable = elements.length && !points[elements[0].index].forecast
        event.native.target.style.cursor = clickable ? 'pointer' : 'default'
      },
      plugins: {
        legend: { display: false },
        tooltip: tooltipOptions(colors, {
          padding: 11,
          displayColors: false,
          callbacks: {
            label: (ctx) => formatAmount(ctx.parsed.y) + (points[ctx.dataIndex].forecast ? ' (prévisionnel)' : '')
          }
        })
      },
      scales: {
        x: axisOptions(colors, { ticks: { maxRotation: 0, autoSkip: true, font: SMALL_TICK_FONT } }),
        y: axisOptions(colors, { withGrid: true, ticks: { padding: 9, font: SMALL_TICK_FONT } })
      }
    }
  })
}

watch([() => props.points, effectiveTheme], render, { flush: 'post' })
onMounted(render)
onBeforeUnmount(() => chart?.destroy())
</script>

<style scoped>
/* Le graphique absorbe toute la hauteur libre de la carte (colonne flex, voir
   .savings-card dans Dashboard.vue) ; min-height sert de repli quand la carte
   n'est pas étirée. */
.savings-chart-wrap {
  position: relative;
  flex: 1 1 auto;
  min-height: 160px;
}
.savings-chart-wrap canvas {
  position: absolute;
  inset: 0;
  width: 100% !important;
  height: 100% !important;
}
</style>

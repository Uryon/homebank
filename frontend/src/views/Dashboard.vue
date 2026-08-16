<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Tableau de bord</h1>
        <div class="subtitle">Vue d'ensemble de vos finances</div>
      </div>
    </div>

    <div class="card-grid">
      <div class="card">
        <div class="stat-label">Solde total</div>
        <div class="stat-value" :class="totalBalance >= 0 ? 'positive amount' : 'negative amount'">
          {{ formatAmount(totalBalance) }}
        </div>
      </div>
      <div class="card">
        <div class="stat-label">Revenus ({{ monthLabel }})</div>
        <div class="stat-value amount positive">{{ formatAmount(summary.income) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">Dépenses ({{ monthLabel }})</div>
        <div class="stat-value amount negative">{{ formatAmount(summary.expenses) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">Net ({{ monthLabel }})</div>
        <div class="stat-value amount" :class="summary.net >= 0 ? 'positive' : 'negative'">
          {{ formatAmount(summary.net) }}
        </div>
      </div>
    </div>

    <div class="card" style="margin-bottom: 20px;">
      <div style="margin-bottom: 16px;">
        <div class="stat-label" style="margin-bottom: 0;">
          Évolution du solde — historique, présent &amp; prévisionnel
        </div>
      </div>
      <canvas ref="forecastChart" height="90"></canvas>
      <p class="chart-note">
        Le prévisionnel est estimé à partir de la variation moyenne du solde observée sur les 12 derniers mois.
      </p>
    </div>

    <div class="dashboard-grid">
      <div class="card">
        <div class="stat-label" style="margin-bottom: 16px;">Épargne mensuelle (12 derniers mois)</div>
        <canvas ref="savingsChart" height="100"></canvas>
        <p class="chart-note">Épargne = revenus du mois − dépenses du mois.</p>
      </div>

      <div class="card">
        <div class="stat-label" style="margin-bottom: 16px;">Dépenses mensuelles</div>
        <div v-if="monthlyExpenses.items.length">
          <table class="plain-table">
            <tbody>
              <tr v-for="item in monthlyExpenses.items" :key="item.payee">
                <td>{{ item.payee || 'Sans nom' }}</td>
                <td class="num amount negative">{{ formatAmount(item.amount) }}</td>
              </tr>
            </tbody>
          </table>
          <div class="monthly-total">
            <span>Total</span>
            <span class="amount negative">{{ formatAmount(monthlyExpenses.total) }}</span>
          </div>
        </div>
        <div v-else class="empty-state">
          Aucune dépense mensuelle marquée. Coche "Mensuelle" sur une transaction récurrente
          (loyer, abonnement...) dans l'onglet Transactions pour la voir apparaître ici.
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { AccountsAPI, ReportsAPI } from '../services/api'
import Chart from 'chart.js/auto'

const accounts = ref([])
const balances = ref({})
const summary = ref({ income: 0, expenses: 0, net: 0 })
const monthLabel = ref('')
const monthlyExpenses = ref({ items: [], total: 0 })

const forecastChart = ref(null)
const savingsChart = ref(null)
let forecastChartInstance = null
let savingsChartInstance = null

const totalBalance = computed(() =>
    Object.values(balances.value).reduce((sum, v) => sum + Number(v), 0)
)

function formatAmount(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v || 0)
}

/**
 * Dernier mois calendaire déjà terminé (le mois en cours n'est pas fini,
 * les revenus/dépenses n'y sont donc pas encore complets).
 */
function lastCompletedMonthRange() {
  const now = new Date()
  const from = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const to = new Date(now.getFullYear(), now.getMonth(), 0)
  return {
    from: from.toISOString().slice(0, 10),
    to: to.toISOString().slice(0, 10)
  }
}

function lastCompletedMonthLabel() {
  const now = new Date()
  const d = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const label = d.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
  return label.charAt(0).toUpperCase() + label.slice(1)
}

async function loadDashboard() {
  accounts.value = await AccountsAPI.list()
  balances.value = await AccountsAPI.balances()

  monthLabel.value = lastCompletedMonthLabel()
  const { from, to } = lastCompletedMonthRange()
  summary.value = await ReportsAPI.summary({ from, to })

  monthlyExpenses.value = await ReportsAPI.monthlyExpenses()

  const [forecast, savings] = await Promise.all([
    ReportsAPI.balanceForecast({}),
    ReportsAPI.monthlySavings({ months: 12 })
  ])

  await nextTick()
  renderForecastChart(forecast)
  renderSavingsChart(savings)
}

/**
 * Affiche le solde sur 12 mois historiques + le mois en cours ("présent")
 * + 12 mois prévisionnels, avec deux jeux de données superposés partageant
 * le point "présent" : une ligne pleine pour le réel, une ligne en
 * pointillés pour le prévisionnel.
 */
function renderForecastChart(points) {
  const presentIndex = points.findIndex(p => p.type === 'present')

  const actualData = points.map((p, i) => (i <= presentIndex ? p.balance : null))
  const forecastData = points.map((p, i) => (i >= presentIndex ? p.balance : null))

  if (forecastChartInstance) forecastChartInstance.destroy()
  forecastChartInstance = new Chart(forecastChart.value, {
    type: 'line',
    data: {
      labels: points.map(p => p.label),
      datasets: [
        {
          label: 'Réel',
          data: actualData,
          borderColor: '#1F9D6C',
          backgroundColor: 'rgba(31, 157, 108, 0.10)',
          fill: true,
          tension: 0.25,
          pointRadius: (ctx) => (points[ctx.dataIndex]?.type === 'present' ? 4 : 0),
          pointBackgroundColor: '#1F9D6C',
          borderWidth: 2,
          spanGaps: false
        },
        {
          label: 'Prévisionnel',
          data: forecastData,
          borderColor: '#3B6FE0',
          backgroundColor: 'rgba(59, 111, 224, 0.06)',
          borderDash: [6, 5],
          fill: true,
          tension: 0.25,
          pointRadius: 0,
          borderWidth: 2,
          spanGaps: false
        }
      ]
    },
    options: {
      responsive: true,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (ctx) => `${ctx.dataset.label} : ${formatAmount(ctx.parsed.y)}`
          }
        }
      },
      scales: {
        x: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C', maxRotation: 0, autoSkip: true } },
        y: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C' } }
      }
    }
  })
}

/** Barres épargne mensuelle : vert si positif (on a épargné), rouge si négatif (on a puisé dans le solde). */
function renderSavingsChart(points) {
  if (savingsChartInstance) savingsChartInstance.destroy()
  savingsChartInstance = new Chart(savingsChart.value, {
    type: 'bar',
    data: {
      labels: points.map(p => p.label),
      datasets: [{
        label: 'Épargne',
        data: points.map(p => p.savings),
        backgroundColor: points.map(p => (Number(p.savings) >= 0 ? '#1F9D6C' : '#D64545')),
        borderRadius: 4,
        maxBarThickness: 34
      }]
    },
    options: {
      responsive: true,
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (ctx) => formatAmount(ctx.parsed.y)
          }
        }
      },
      scales: {
        x: { grid: { display: false }, ticks: { color: '#67707C', maxRotation: 0, autoSkip: true } },
        y: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C' } }
      }
    }
  })
}

onMounted(loadDashboard)
</script>

<style scoped>
.chart-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-faint);
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 20px;
}
@media (max-width: 860px) {
  .dashboard-grid { grid-template-columns: 1fr; }
}

.plain-table {
  width: 100%;
  border-collapse: collapse;
}
.plain-table td {
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
  font-size: 14px;
}
.plain-table tr:last-child td {
  border-bottom: none;
}

.monthly-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 2px solid var(--line);
  font-weight: 700;
}
</style>

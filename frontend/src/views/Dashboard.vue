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
        <div class="stat-label" style="margin-bottom: 16px;">Dépenses périodiques</div>
        <div v-if="periodicExpenses.items.length">
          <table class="plain-table">
            <tbody>
              <tr v-for="item in periodicExpenses.items" :key="item.payee">
                <td>{{ item.payee || 'Sans nom' }}</td>
                <td class="num amount negative">{{ formatAmount(item.amount) }}</td>
              </tr>
            </tbody>
          </table>
          <div class="periodic-total">
            <span>Total</span>
            <span class="amount negative">{{ formatAmount(periodicExpenses.total) }}</span>
          </div>
        </div>
        <div v-else class="empty-state">
          Aucune dépense périodique marquée. Coche "Périodique" sur une transaction récurrente
          (loyer, abonnement...) dans l'onglet Transactions, ou regarde l'onglet "Périodiques"
          pour des suggestions automatiques.
        </div>
      </div>
    </div>

    <div class="card" style="margin-bottom: 28px;">
      <div class="stat-label" style="margin-bottom: 4px;">Où va mon argent ?</div>
      <div style="color: var(--text-muted); font-size: 13px; margin-bottom: 20px;">
        Répartition des dépenses sur les 12 derniers mois (les virements entre comptes ne sont pas comptés).
      </div>
      <div v-if="whereMoneyData.length" class="where-money-layout">
        <div class="donut-wrap">
          <canvas ref="whereMoneyCanvas" width="200" height="200"></canvas>
          <div class="donut-center">
            <div class="donut-center-value">{{ formatAmountCompact(whereMoneyTotal) }}</div>
            <div class="donut-center-label">sur 12 mois</div>
          </div>
        </div>
        <div class="where-table-wrap">
          <table class="where-table">
            <thead>
              <tr>
                <th>Catégorie</th>
                <th class="num">Montant</th>
                <th class="num">%</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in whereMoneyData" :key="row.category">
                <td>
                  <span class="legend-dot" :style="{ background: PALETTE[i % PALETTE.length] }"></span>
                  {{ row.category }}
                </td>
                <td class="num amount negative">{{ formatAmount(row.total) }}</td>
                <td class="num where-pct">{{ row.percentage }}%</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>Total</td>
                <td class="num amount negative">{{ formatAmount(whereMoneyTotal) }}</td>
                <td class="num where-pct">100%</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>
      <div v-else class="empty-state">Aucune dépense sur les 12 derniers mois.</div>
    </div>

    <div class="card monthly-analysis-card">
      <div class="stat-label" style="margin-bottom: 4px;">Résumé mensuel — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Revenus, dépenses et solde par mois. Les 12 derniers mois entièrement terminés sont affichés.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table class="monthly-analysis-table">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                {{ month.label }}
              </th>
              <th class="num summary-col">Total</th>
            </tr>
          </thead>

          <tbody>
            <tr>
              <td class="category-sticky summary-label">Revenus</td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-income-' + month.key" class="num">
                {{ formatAmount(month.income) }}
              </td>
              <td class="num summary-col">{{ formatAmount(monthlyAnalysis.incomeTotal.total) }}</td>
            </tr>
            <tr>
              <td class="category-sticky summary-label">Dépenses</td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-expense-' + month.key" class="num">
                {{ formatAmount(month.expenses) }}
              </td>
              <td class="num summary-col">{{ formatAmount(monthlyAnalysis.expenseTotal.total) }}</td>
            </tr>
            <tr class="monthly-balance-row">
              <td class="category-sticky summary-label"><strong>Solde du mois</strong></td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-net-' + month.key"
                  class="num" :class="Number(month.net) >= 0 ? 'positive' : 'negative'">
                <strong>{{ formatAmount(month.net) }}</strong>
              </td>
              <td class="num summary-col">
                <strong>{{ formatAmount(Number(monthlyAnalysis.incomeTotal.total) - Number(monthlyAnalysis.expenseTotal.total)) }}</strong>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>

    <div class="card monthly-analysis-card">
      <div class="stat-label" style="margin-bottom: 4px;">Revenus par catégorie — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Évolution mensuelle par catégorie et sous-catégorie. Les 12 derniers mois entièrement terminés sont affichés.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table class="monthly-analysis-table">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                {{ month.label }}
              </th>
              <th class="num summary-col">Moyenne</th>
              <th class="num summary-col">Total</th>
            </tr>
          </thead>

          <tbody>
            <template v-for="group in incomeGroups" :key="'income-' + group.key">
              <tr class="category-row">
                <td class="category-sticky category-name">
                  <button v-if="group.children.length" class="collapse-button"
                          @click="toggleMonthlyGroup('income', group.key)"
                          :title="isMonthlyGroupCollapsed('income', group.key) ? 'Développer' : 'Replier'">
                    {{ isMonthlyGroupCollapsed('income', group.key) ? '▸' : '▾' }}
                  </button>
                  <span v-else class="collapse-placeholder"></span>
                  <strong>{{ group.name }}</strong>
                </td>
                <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                  {{ formatAmount(group.values[month.key]) }}
                </td>
                <td class="num summary-col">{{ formatAmount(group.average) }}</td>
                <td class="num summary-col">{{ formatAmount(group.total) }}</td>
              </tr>

              <template v-if="!isMonthlyGroupCollapsed('income', group.key)">
                <tr v-for="child in group.children" :key="'income-child-' + child.key"
                    class="subcategory-row">
                  <td class="category-sticky category-name subcategory-name">
                    └ {{ child.name }}
                  </td>
                  <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                    {{ formatAmount(child.values[month.key]) }}
                  </td>
                  <td class="num summary-col">{{ formatAmount(child.average) }}</td>
                  <td class="num summary-col">{{ formatAmount(child.total) }}</td>
                </tr>
              </template>
            </template>

            <tr class="total-row income-total-row">
              <td class="category-sticky"><strong>{{ monthlyAnalysis.incomeTotal.name }}</strong></td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                <strong>{{ formatAmount(monthlyAnalysis.incomeTotal.values[month.key]) }}</strong>
              </td>
              <td class="num summary-col"><strong>{{ formatAmount(monthlyAnalysis.incomeTotal.average) }}</strong></td>
              <td class="num summary-col"><strong>{{ formatAmount(monthlyAnalysis.incomeTotal.total) }}</strong></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>

    <div class="card monthly-analysis-card">
      <div class="stat-label" style="margin-bottom: 4px;">Dépenses par catégorie — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Évolution mensuelle par catégorie et sous-catégorie. Les 12 derniers mois entièrement terminés sont affichés.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table class="monthly-analysis-table">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                {{ month.label }}
              </th>
              <th class="num summary-col">Moyenne</th>
              <th class="num summary-col">Total</th>
            </tr>
          </thead>

          <tbody>
            <template v-for="group in expenseGroups" :key="'expense-' + group.key">
              <tr class="category-row">
                <td class="category-sticky category-name">
                  <button v-if="group.children.length" class="collapse-button"
                          @click="toggleMonthlyGroup('expense', group.key)"
                          :title="isMonthlyGroupCollapsed('expense', group.key) ? 'Développer' : 'Replier'">
                    {{ isMonthlyGroupCollapsed('expense', group.key) ? '▸' : '▾' }}
                  </button>
                  <span v-else class="collapse-placeholder"></span>
                  <strong>{{ group.name }}</strong>
                </td>
                <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                  {{ formatAmount(group.values[month.key]) }}
                </td>
                <td class="num summary-col">{{ formatAmount(group.average) }}</td>
                <td class="num summary-col">{{ formatAmount(group.total) }}</td>
              </tr>

              <template v-if="!isMonthlyGroupCollapsed('expense', group.key)">
                <tr v-for="child in group.children" :key="'expense-child-' + child.key"
                    class="subcategory-row">
                  <td class="category-sticky category-name subcategory-name">
                    └ {{ child.name }}
                  </td>
                  <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                    {{ formatAmount(child.values[month.key]) }}
                  </td>
                  <td class="num summary-col">{{ formatAmount(child.average) }}</td>
                  <td class="num summary-col">{{ formatAmount(child.total) }}</td>
                </tr>
              </template>
            </template>

            <tr class="total-row expense-total-row">
              <td class="category-sticky"><strong>{{ monthlyAnalysis.expenseTotal.name }}</strong></td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num">
                <strong>{{ formatAmount(monthlyAnalysis.expenseTotal.values[month.key]) }}</strong>
              </td>
              <td class="num summary-col"><strong>{{ formatAmount(monthlyAnalysis.expenseTotal.average) }}</strong></td>
              <td class="num summary-col"><strong>{{ formatAmount(monthlyAnalysis.expenseTotal.total) }}</strong></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { AccountsAPI, ReportsAPI } from '../services/api'
import { formatAmount } from '../services/format'
import Chart from 'chart.js/auto'

const accounts = ref([])
const balances = ref({})
const summary = ref({ income: 0, expenses: 0, net: 0 })
const monthLabel = ref('')
const periodicExpenses = ref({ items: [], total: 0 })

const forecastChart = ref(null)
const savingsChart = ref(null)
let forecastChartInstance = null
let savingsChartInstance = null

// --- "Où va mon argent ?" + tableaux d'analyse mensuelle (ex-page Rapports,
// desormais integres au tableau de bord). Portee sur l'ensemble des comptes,
// sans filtre (le tableau de bord n'a jamais eu de selecteur de compte).
const whereMoneyData = ref([])
const monthlyAnalysis = ref(null)
const collapsedMonthlyGroups = ref({ income: {}, expense: {} })
const whereMoneyCanvas = ref(null)
let whereMoneyChart = null

const PALETTE = ['#1F9D6C', '#D64545', '#3B6FE0', '#C98A1B', '#8B6FE0', '#2CA8C9', '#D6559A', '#6FAF2C']

const totalBalance = computed(() =>
    Object.values(balances.value).reduce((sum, v) => sum + Number(v), 0)
)

/** Version compacte pour le total au centre du donut (ex : "3 780 €" au lieu de "3 780,00 €"). */
function formatAmountCompact(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }).format(v || 0)
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

/** Plage fixe des 12 derniers mois glissants, utilisee par "Où va mon argent ?". */
function last12MonthsIso() {
  const to = new Date()
  const from = new Date()
  from.setMonth(from.getMonth() - 12)
  return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) }
}

const whereMoneyTotal = computed(() =>
  whereMoneyData.value.reduce((sum, row) => sum + Number(row.total || 0), 0)
)

/**
 * Regroupe les lignes plates (categorie/sous-categorie) renvoyees par
 * monthlyCategoryAnalysis en groupes racine + enfants, avec des totaux/
 * moyennes recalcules par groupe (une racine sans transaction directe
 * affiche quand meme le total de ses sous-categories).
 */
function analysisGroup(rows) {
  const groups = new Map()
  const zeroValues = () => Object.fromEntries((monthlyAnalysis.value?.months || []).map(m => [m.key, 0]))
  const addValues = (target, source) => {
    for (const month of monthlyAnalysis.value.months) {
      target.values[month.key] = Number(target.values[month.key] || 0) + Number(source.values[month.key] || 0)
    }
    target.total = Object.values(target.values).reduce((sum, value) => sum + Number(value || 0), 0)
    target.average = monthlyAnalysis.value.months.length
      ? target.total / monthlyAnalysis.value.months.length
      : 0
  }

  for (const row of rows || []) {
    const rootKey = row.parentId != null ? `parent-${row.parentId}` : `category-${row.categoryId ?? 'uncategorized'}`
    let group = groups.get(rootKey)
    if (!group) {
      group = {
        key: rootKey,
        name: row.parentId != null ? row.parentName : row.name,
        values: zeroValues(),
        total: 0,
        average: 0,
        children: []
      }
      groups.set(rootKey, group)
    }
    if (row.parentId != null) {
      group.children.push(row)
    }
    // Les transactions directement affectées à la catégorie racine sont
    // intégrées au total du groupe.
    if (row.parentId == null) addValues(group, row)
  }

  // Un parent qui n'a que des sous-catégories doit quand même afficher leur total.
  for (const group of groups.values()) {
    for (const child of group.children) addValues(group, child)
    group.children.sort((a, b) => String(a.name).localeCompare(String(b.name), 'fr'))
  }

  return Array.from(groups.values()).sort((a, b) => a.name.localeCompare(b.name, 'fr'))
}

const incomeGroups = computed(() => analysisGroup(monthlyAnalysis.value?.income || []))
const expenseGroups = computed(() => analysisGroup(monthlyAnalysis.value?.expenses || []))

function toggleMonthlyGroup(type, key) {
  collapsedMonthlyGroups.value[type][key] = !collapsedMonthlyGroups.value[type][key]
}
function isMonthlyGroupCollapsed(type, key) {
  return !!collapsedMonthlyGroups.value[type][key]
}

async function loadDashboard() {
  accounts.value = await AccountsAPI.list()
  balances.value = await AccountsAPI.balances()

  monthLabel.value = lastCompletedMonthLabel()
  const { from, to } = lastCompletedMonthRange()
  summary.value = await ReportsAPI.summary({ from, to })

  periodicExpenses.value = await ReportsAPI.periodicExpenses()

  const [forecast, savings, whereMoney, monthly] = await Promise.all([
    ReportsAPI.balanceForecast({}),
    ReportsAPI.monthlySavings({ months: 12 }),
    ReportsAPI.expensesByCategory(last12MonthsIso()),
    ReportsAPI.monthlyCategoryAnalysis({})
  ])
  whereMoneyData.value = whereMoney
  monthlyAnalysis.value = monthly

  await nextTick()
  renderForecastChart(forecast)
  renderSavingsChart(savings)
  renderWhereMoneyChart(whereMoneyData.value)
}

/**
 * Affiche le solde sur 12 mois historiques (deja termines, ligne pleine
 * "Réel") + 12 mois prévisionnels a partir du mois en cours inclus (ligne
 * pointillee "Prévisionnel") : un mois n'est compte comme réel qu'a partir
 * du 1er du mois suivant, une fois entierement termine. Les deux lignes
 * partagent le dernier point historique (mois precedent) comme point de
 * jonction, pour eviter tout trou visuel entre les deux.
 */
function renderForecastChart(points) {
  const firstForecastIndex = points.findIndex(p => p.type === 'previsionnel')
  const junctionIndex = firstForecastIndex - 1

  const actualData = points.map((p, i) => (i < firstForecastIndex ? p.balance : null))
  const forecastData = points.map((p, i) => (i >= junctionIndex ? p.balance : null))

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
          pointRadius: 0,
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
          pointRadius: (ctx) => (ctx.dataIndex === junctionIndex ? 4 : 0),
          pointBackgroundColor: '#3B6FE0',
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

/**
 * Donut dedie a "Où va mon argent ?" : pas de legende Chart.js interne (le
 * tableau a droite sert deja de legende detaillee), anneau plus fin pour un
 * rendu plus moderne, et un total affiche au centre (overlay CSS, voir
 * .donut-center dans le style du composant).
 */
function renderWhereMoneyChart(data) {
  if (whereMoneyChart) whereMoneyChart.destroy()
  if (!whereMoneyCanvas.value || !data.length) return
  whereMoneyChart = new Chart(whereMoneyCanvas.value, {
    type: 'doughnut',
    data: {
      labels: data.map(d => d.category),
      datasets: [{
        data: data.map(d => d.total),
        backgroundColor: data.map((_, i) => PALETTE[i % PALETTE.length]),
        borderColor: '#FFFFFF',
        borderWidth: 2
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: true,
      cutout: '72%',
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (ctx) => {
              const row = data[ctx.dataIndex]
              return ` ${row.category} : ${formatAmount(row.total)} (${row.percentage}%)`
            }
          }
        }
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
  margin-bottom: 28px;
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

.periodic-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 2px solid var(--line);
  font-weight: 700;
}

.legend-dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-right: 8px;
}

/* --- "Où va mon argent ?" : donut compact à gauche + tableau détaillé à droite --- */
.where-money-layout {
  display: grid;
  grid-template-columns: 200px 1fr;
  gap: 32px;
  align-items: center;
}
@media (max-width: 640px) {
  .where-money-layout {
    grid-template-columns: 1fr;
    justify-items: center;
  }
}

.donut-wrap {
  position: relative;
  width: 200px;
  height: 200px;
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
  color: var(--text-primary);
  line-height: 1.2;
}
.donut-center-label {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 2px;
}

.where-table-wrap {
  max-height: 260px;
  overflow-y: auto;
}
.where-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.where-table th {
  position: sticky;
  top: 0;
  background: var(--ink-900);
  text-align: left;
  padding: 0 0 8px;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: .06em;
  color: var(--text-faint);
  font-weight: 700;
}
.where-table td {
  padding: 7px 0;
  border-bottom: 1px solid var(--line);
}
.where-table tbody tr:hover td {
  background: var(--ink-800);
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

.monthly-analysis-card {
  margin-bottom: 28px;
}

.monthly-analysis-subtitle {
  color: var(--text-muted);
  font-size: 13px;
  margin-bottom: 18px;
}

.monthly-analysis-wrap {
  width: 100%;
  overflow: auto;
  max-height: 720px;
}

.monthly-analysis-table {
  width: max-content;
  min-width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 11px;
}

.monthly-analysis-table th,
.monthly-analysis-table td {
  padding: 6px 5px;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.monthly-analysis-table thead th {
  position: sticky;
  top: 0;
  z-index: 4;
  background: var(--ink-900);
  color: var(--text-faint);
  font-size: 9px;
  text-transform: uppercase;
  letter-spacing: .05em;
}

.monthly-analysis-table .category-sticky {
  position: sticky;
  left: 0;
  z-index: 3;
  min-width: 120px;
  text-align: left;
  background: var(--ink-900);
  border-right: 1px solid var(--line);
}

.monthly-analysis-table thead .category-sticky {
  z-index: 6;
}

.monthly-analysis-table .num {
  min-width: 58px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.monthly-analysis-table .summary-col {
  background: var(--ink-800);
  font-weight: 600;
}

.monthly-analysis-table tbody tr.category-row td {
  background: var(--ink-900);
  font-weight: 600;
}

.monthly-analysis-table tbody tr.category-row:hover td,
.monthly-analysis-table tbody tr.subcategory-row:hover td {
  background: var(--ink-800);
}

.monthly-analysis-table tbody tr.subcategory-row td {
  color: var(--text-muted);
}

.monthly-analysis-table tbody tr.subcategory-row .category-sticky {
  padding-left: 18px;
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

.monthly-analysis-table .summary-label {
  font-weight: 700;
}

.monthly-analysis-table .monthly-balance-row td {
  border-bottom: 0;
  background: var(--ink-800);
}

.collapse-button {
  width: 16px;
  padding: 0;
  margin-right: 2px;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 11px;
}

.collapse-placeholder {
  display: inline-block;
  width: 18px;
}
</style>

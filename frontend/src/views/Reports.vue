<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Rapports</h1>
        <div class="subtitle">Analysez vos dépenses et revenus</div>
      </div>
    </div>

    <div class="toolbar">
      <div class="form-field">
        <label>Du</label>
        <input v-model="from" type="date" @change="loadAll" />
      </div>
      <div class="form-field">
        <label>Au</label>
        <input v-model="to" type="date" @change="loadAll" />
      </div>
      <div class="form-field">
        <label>Compte</label>
        <select v-model="accountId" @change="loadAll">
          <option :value="null">Tous les comptes</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </div>
    </div>

    <div class="card-grid">
      <div class="card">
        <div class="stat-label">Revenus</div>
        <div class="stat-value amount positive">{{ formatAmount(summary.income) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">Dépenses</div>
        <div class="stat-value amount negative">{{ formatAmount(summary.expenses) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">Net</div>
        <div class="stat-value amount" :class="summary.net >= 0 ? 'positive' : 'negative'">{{ formatAmount(summary.net) }}</div>
      </div>
    </div>

    <div class="card" style="margin-bottom: 28px;">
      <div class="stat-label" style="margin-bottom: 16px;">Évolution du solde</div>
      <canvas ref="balanceCanvas" height="90"></canvas>
    </div>

    <div class="card" style="margin-bottom: 28px;">
      <div class="stat-label" style="margin-bottom: 16px;">Revenus par catégorie</div>
      <canvas ref="incomeCanvas"></canvas>
      <div v-if="!incomeData.length" class="empty-state">Aucun revenu sur cette période.</div>
    </div>

    <div class="card">
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

    <div class="card monthly-analysis-card">
      <div class="stat-label" style="margin-bottom: 4px;">Revenus et dépenses — 12 derniers mois</div>
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
            <tr class="section-title-row">
              <td :colspan="monthlyAnalysis.months.length + 3">Revenus</td>
            </tr>

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

            <tr class="section-spacer">
              <td :colspan="monthlyAnalysis.months.length + 3"></td>
            </tr>

            <tr class="section-title-row expense-section-title">
              <td :colspan="monthlyAnalysis.months.length + 3">Dépenses</td>
            </tr>

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

            <tr class="section-spacer">
              <td :colspan="monthlyAnalysis.months.length + 3"></td>
            </tr>

            <tr class="monthly-summary-title">
              <td :colspan="monthlyAnalysis.months.length + 3">Résumé mensuel</td>
            </tr>
            <tr>
              <td class="category-sticky summary-label">Revenus</td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-income-' + month.key" class="num">
                {{ formatAmount(month.income) }}
              </td>
              <td class="num summary-col">—</td>
              <td class="num summary-col">{{ formatAmount(monthlyAnalysis.incomeTotal.total) }}</td>
            </tr>
            <tr>
              <td class="category-sticky summary-label">Dépenses</td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-expense-' + month.key" class="num">
                {{ formatAmount(month.expenses) }}
              </td>
              <td class="num summary-col">—</td>
              <td class="num summary-col">{{ formatAmount(monthlyAnalysis.expenseTotal.total) }}</td>
            </tr>
            <tr class="monthly-balance-row">
              <td class="category-sticky summary-label"><strong>Solde du mois</strong></td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-net-' + month.key"
                  class="num" :class="Number(month.net) >= 0 ? 'positive' : 'negative'">
                <strong>{{ formatAmount(month.net) }}</strong>
              </td>
              <td class="num summary-col">—</td>
              <td class="num summary-col">
                <strong>{{ formatAmount(Number(monthlyAnalysis.incomeTotal.total) - Number(monthlyAnalysis.expenseTotal.total)) }}</strong>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { AccountsAPI, ReportsAPI } from '../services/api'
import Chart from 'chart.js/auto'

const accounts = ref([])
const accountId = ref(null)
const from = ref(firstOfMonthIso())
const to = ref(new Date().toISOString().slice(0, 10))
const summary = ref({ income: 0, expenses: 0, net: 0 })
const incomeData = ref([])
const whereMoneyData = ref([])
const monthlyAnalysis = ref(null)
const collapsedMonthlyGroups = ref({ income: {}, expense: {} })

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

  // On masque les lignes qui n'ont aucun montant sur toute la période.
  const isZero = (value) => Math.abs(Number(value) || 0) < 0.005
  for (const group of groups.values()) {
    group.children = group.children.filter(child => !isZero(child.total))
  }

  return Array.from(groups.values())
    .filter(group => !isZero(group.total))
    .sort((a, b) => a.name.localeCompare(b.name, 'fr'))
}

const incomeGroups = computed(() => analysisGroup(monthlyAnalysis.value?.income || []))
const expenseGroups = computed(() => analysisGroup(monthlyAnalysis.value?.expenses || []))

function toggleMonthlyGroup(type, key) {
  collapsedMonthlyGroups.value[type][key] = !collapsedMonthlyGroups.value[type][key]
}
function isMonthlyGroupCollapsed(type, key) {
  return !!collapsedMonthlyGroups.value[type][key]
}

const balanceCanvas = ref(null)
const incomeCanvas = ref(null)
const whereMoneyCanvas = ref(null)
let balanceChart, incomeChart, whereMoneyChart

const PALETTE = ['#1F9D6C', '#D64545', '#3B6FE0', '#C98A1B', '#8B6FE0', '#2CA8C9', '#D6559A', '#6FAF2C']

function firstOfMonthIso() {
  const d = new Date()
  return new Date(d.getFullYear(), d.getMonth(), 1).toISOString().slice(0, 10)
}

/** Plage fixe des 12 derniers mois glissants, indépendante des filtres "Du"/"Au" du haut de page. */
function last12MonthsIso() {
  const to = new Date()
  const from = new Date()
  from.setMonth(from.getMonth() - 12)
  return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) }
}

function formatAmount(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v || 0)
}

/** Version compacte pour le total au centre du donut (ex : "3 780 €" au lieu de "3 780,00 €"). */
function formatAmountCompact(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }).format(v || 0)
}

const whereMoneyTotal = computed(() =>
  whereMoneyData.value.reduce((sum, row) => sum + Number(row.total || 0), 0)
)

async function loadAll() {
  const params = { from: from.value, to: to.value }
  if (accountId.value) params.accountId = accountId.value

  summary.value = await ReportsAPI.summary(params)
  incomeData.value = await ReportsAPI.incomeByCategory(params)
  const evolution = await ReportsAPI.balanceEvolution(params)

  const whereMoneyParams = { ...last12MonthsIso() }
  if (accountId.value) whereMoneyParams.accountId = accountId.value
  whereMoneyData.value = await ReportsAPI.expensesByCategory(whereMoneyParams)
  monthlyAnalysis.value = await ReportsAPI.monthlyCategoryAnalysis(accountId.value ? { accountId: accountId.value } : {})

  await nextTick()
  renderBalanceChart(evolution)
  renderPieChart(incomeCanvas, incomeChart, incomeData.value, c => { incomeChart = c })
  renderWhereMoneyChart(whereMoneyData.value)
}

function renderBalanceChart(evolution) {
  if (balanceChart) balanceChart.destroy()
  balanceChart = new Chart(balanceCanvas.value, {
    type: 'line',
    data: {
      labels: evolution.map(p => p.date.slice(5)),
      datasets: [{
        data: evolution.map(p => p.balance),
        borderColor: '#3B6FE0',
        backgroundColor: 'rgba(59, 111, 224, 0.10)',
        fill: true, tension: 0.25, pointRadius: 0, borderWidth: 2
      }]
    },
    options: {
      responsive: true,
      plugins: { legend: { display: false } },
      scales: {
        x: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C' } },
        y: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C' } }
      }
    }
  })
}

function renderPieChart(canvasRef, existing, data, setter) {
  if (existing) existing.destroy()
  if (!canvasRef.value || !data.length) return
  const chart = new Chart(canvasRef.value, {
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
      plugins: {
        legend: { position: 'right', labels: { color: '#1B2430', boxWidth: 12, font: { size: 11 } } }
      }
    }
  })
  setter(chart)
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

onMounted(async () => {
  accounts.value = await AccountsAPI.list()
  await loadAll()
})
</script>

<style scoped>
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
  margin-top: 28px;
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
  border: 1px solid var(--line);
  border-radius: 10px;
}

.monthly-analysis-table {
  width: max-content;
  min-width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12px;
}

.monthly-analysis-table th,
.monthly-analysis-table td {
  padding: 9px 12px;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.monthly-analysis-table thead th {
  position: sticky;
  top: 0;
  z-index: 4;
  background: var(--ink-900);
  color: var(--text-faint);
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: .05em;
}

.monthly-analysis-table .category-sticky {
  position: sticky;
  left: 0;
  z-index: 3;
  min-width: 190px;
  text-align: left;
  background: var(--ink-900);
  border-right: 1px solid var(--line);
}

.monthly-analysis-table thead .category-sticky {
  z-index: 6;
}

.monthly-analysis-table .num {
  min-width: 100px;
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
  padding-left: 30px;
}

.monthly-analysis-table .section-title-row td,
.monthly-analysis-table .monthly-summary-title td {
  padding: 11px 12px;
  background: var(--ink-800);
  color: var(--text-primary);
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: .06em;
  border-bottom: 2px solid var(--line);
}

.monthly-analysis-table .expense-section-title td {
  border-top: 1px solid var(--line);
}

.monthly-analysis-table .section-spacer td {
  height: 14px;
  padding: 0;
  background: var(--ink-900);
  border: 0;
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

.monthly-analysis-table .monthly-summary-title td {
  border-top: 2px solid var(--line);
}

.monthly-analysis-table .summary-label {
  font-weight: 700;
}

.monthly-analysis-table .monthly-balance-row td {
  border-bottom: 0;
  background: var(--ink-800);
}

.collapse-button {
  width: 22px;
  padding: 0;
  margin-right: 4px;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 13px;
}

.collapse-placeholder {
  display: inline-block;
  width: 26px;
}

</style>
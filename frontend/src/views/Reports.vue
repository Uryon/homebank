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

    <div style="display:grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 28px;">
      <div class="card">
        <div class="stat-label" style="margin-bottom: 16px;">Dépenses par catégorie</div>
        <canvas ref="expenseCanvas"></canvas>
        <div v-if="!expenseData.length" class="empty-state">Aucune dépense sur cette période.</div>
      </div>
      <div class="card">
        <div class="stat-label" style="margin-bottom: 16px;">Revenus par catégorie</div>
        <canvas ref="incomeCanvas"></canvas>
        <div v-if="!incomeData.length" class="empty-state">Aucun revenu sur cette période.</div>
      </div>
    </div>

    <div class="card">
      <div class="stat-label" style="margin-bottom: 4px;">Où va mon argent ?</div>
      <div style="color: var(--text-muted); font-size: 13px; margin-bottom: 16px;">
        Répartition des dépenses sur les 12 derniers mois (les virements entre comptes ne sont pas comptés).
      </div>
      <div v-if="whereMoneyData.length" style="display:grid; grid-template-columns: 280px 1fr; gap: 28px; align-items: center;">
        <canvas ref="whereMoneyCanvas"></canvas>
        <table class="plain-table">
          <thead>
            <tr>
              <th style="text-align:left; padding-bottom:8px; font-size:11px; text-transform:uppercase; letter-spacing:.06em; color: var(--text-faint);">Catégorie</th>
              <th class="num" style="padding-bottom:8px; font-size:11px; text-transform:uppercase; letter-spacing:.06em; color: var(--text-faint);">Montant</th>
              <th class="num" style="padding-bottom:8px; font-size:11px; text-transform:uppercase; letter-spacing:.06em; color: var(--text-faint);">%</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in whereMoneyData" :key="row.category">
              <td>
                <span class="legend-dot" :style="{ background: PALETTE[i % PALETTE.length] }"></span>
                {{ row.category }}
              </td>
              <td class="num amount negative">{{ formatAmount(row.total) }}</td>
              <td class="num">{{ row.percentage }}%</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">Aucune dépense sur les 12 derniers mois.</div>
      <div v-if="whereMoneyData.length" class="monthly-total">
        <span>Total des dépenses (12 derniers mois)</span>
        <span class="amount negative">{{ formatAmount(whereMoneyTotal) }}</span>
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
const expenseData = ref([])
const incomeData = ref([])
const whereMoneyData = ref([])

const balanceCanvas = ref(null)
const expenseCanvas = ref(null)
const incomeCanvas = ref(null)
const whereMoneyCanvas = ref(null)
let balanceChart, expenseChart, incomeChart, whereMoneyChart

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

const whereMoneyTotal = computed(() =>
  whereMoneyData.value.reduce((sum, row) => sum + Number(row.total || 0), 0)
)

async function loadAll() {
  const params = { from: from.value, to: to.value }
  if (accountId.value) params.accountId = accountId.value

  summary.value = await ReportsAPI.summary(params)
  expenseData.value = await ReportsAPI.expensesByCategory(params)
  incomeData.value = await ReportsAPI.incomeByCategory(params)
  const evolution = await ReportsAPI.balanceEvolution(params)

  const whereMoneyParams = { ...last12MonthsIso() }
  if (accountId.value) whereMoneyParams.accountId = accountId.value
  whereMoneyData.value = await ReportsAPI.expensesByCategory(whereMoneyParams)

  await nextTick()
  renderBalanceChart(evolution)
  renderPieChart(expenseCanvas, expenseChart, expenseData.value, c => { expenseChart = c })
  renderPieChart(incomeCanvas, incomeChart, incomeData.value, c => { incomeChart = c })
  renderPieChart(whereMoneyCanvas, whereMoneyChart, whereMoneyData.value, c => { whereMoneyChart = c })
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
  margin-top: 16px;
  padding-top: 14px;
  border-top: 2px solid var(--line);
  font-weight: 700;
}
</style>

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

    <div style="display:grid; grid-template-columns: 1fr 1fr; gap: 20px;">
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
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { AccountsAPI, ReportsAPI } from '../services/api'
import Chart from 'chart.js/auto'

const accounts = ref([])
const accountId = ref(null)
const from = ref(firstOfMonthIso())
const to = ref(new Date().toISOString().slice(0, 10))
const summary = ref({ income: 0, expenses: 0, net: 0 })
const expenseData = ref([])
const incomeData = ref([])

const balanceCanvas = ref(null)
const expenseCanvas = ref(null)
const incomeCanvas = ref(null)
let balanceChart, expenseChart, incomeChart

const PALETTE = ['#1F9D6C', '#D64545', '#3B6FE0', '#C98A1B', '#8B6FE0', '#2CA8C9', '#D6559A', '#6FAF2C']

function firstOfMonthIso() {
  const d = new Date()
  return new Date(d.getFullYear(), d.getMonth(), 1).toISOString().slice(0, 10)
}

function formatAmount(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v || 0)
}

async function loadAll() {
  const params = { from: from.value, to: to.value }
  if (accountId.value) params.accountId = accountId.value

  summary.value = await ReportsAPI.summary(params)
  expenseData.value = await ReportsAPI.expensesByCategory(params)
  incomeData.value = await ReportsAPI.incomeByCategory(params)
  const evolution = await ReportsAPI.balanceEvolution(params)

  await nextTick()
  renderBalanceChart(evolution)
  renderPieChart(expenseCanvas, expenseChart, expenseData.value, c => { expenseChart = c })
  renderPieChart(incomeCanvas, incomeChart, incomeData.value, c => { incomeChart = c })
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

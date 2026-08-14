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
        <div class="stat-label">Revenus (30 derniers jours)</div>
        <div class="stat-value amount positive">{{ formatAmount(summary.income) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">Dépenses (30 derniers jours)</div>
        <div class="stat-value amount negative">{{ formatAmount(summary.expenses) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">Net (30 derniers jours)</div>
        <div class="stat-value amount" :class="summary.net >= 0 ? 'positive' : 'negative'">
          {{ formatAmount(summary.net) }}
        </div>
      </div>
    </div>

    <div class="card" style="margin-bottom: 28px;">
      <div class="stat-label" style="margin-bottom: 16px;">Évolution du solde (30 derniers jours)</div>
      <canvas ref="balanceChart" height="90"></canvas>
    </div>

    <div class="card">
      <div class="stat-label" style="margin-bottom: 16px;">Comptes</div>
      <table v-if="accounts.length">
        <thead>
          <tr>
            <th>Nom</th>
            <th>Type</th>
            <th class="num">Solde</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in accounts" :key="a.id">
            <td>{{ a.name }}</td>
            <td><span class="badge" :class="a.type.toLowerCase()">{{ typeLabel(a.type) }}</span></td>
            <td class="num amount" :class="(balances[a.id] ?? 0) >= 0 ? 'positive' : 'negative'">
              {{ formatAmount(balances[a.id] ?? 0) }}
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">Aucun compte pour l'instant. Rendez-vous dans "Comptes" pour en créer un.</div>
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
const balanceChart = ref(null)
let chartInstance = null

const totalBalance = computed(() =>
    Object.values(balances.value).reduce((sum, v) => sum + Number(v), 0)
)

function formatAmount(v) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(v || 0)
}

function typeLabel(type) {
  const labels = { CHECKING: 'Courant', SAVINGS: 'Épargne', CASH: 'Espèces', CREDIT_CARD: 'Carte', INVESTMENT: 'Placement' }
  return labels[type] || type
}

function todayIso() {
  return new Date().toISOString().slice(0, 10)
}
function daysAgoIso(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

async function loadDashboard() {
  accounts.value = await AccountsAPI.list()
  balances.value = await AccountsAPI.balances()

  const from = daysAgoIso(30)
  const to = todayIso()
  summary.value = await ReportsAPI.summary({ from, to })

  const evolution = await ReportsAPI.balanceEvolution({ from, to })
  await nextTick()
  renderChart(evolution)
}

function renderChart(evolution) {
  if (chartInstance) chartInstance.destroy()
  chartInstance = new Chart(balanceChart.value, {
    type: 'line',
    data: {
      labels: evolution.map(p => p.date.slice(5)),
      datasets: [{
        label: 'Solde',
        data: evolution.map(p => p.balance),
        borderColor: '#4FD1A5',
        backgroundColor: 'rgba(79, 209, 165, 0.12)',
        fill: true,
        tension: 0.25,
        pointRadius: 0,
        borderWidth: 2
      }]
    },
    options: {
      responsive: true,
      plugins: { legend: { display: false } },
      scales: {
        x: { grid: { color: '#2A3644' }, ticks: { color: '#8B98A8' } },
        y: { grid: { color: '#2A3644' }, ticks: { color: '#8B98A8' } }
      }
    }
  })
}

onMounted(loadDashboard)
</script>

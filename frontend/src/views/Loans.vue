<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Prêts</h1>
        <div class="subtitle">Suivi du capital restant dû et de l'échéancier d'amortissement</div>
      </div>
      <button class="btn" @click="openCreate">+ Nouveau prêt</button>
    </div>

    <div v-if="loans.length" class="card-grid loan-grid">
      <div
        v-for="l in loans"
        :key="l.id"
        class="card loan-card"
        :class="{ active: selectedId === l.id }"
        @click="select(l.id)"
      >
        <div style="display:flex; justify-content:space-between; align-items:flex-start;">
          <div>
            <div class="stat-label">{{ l.name }}</div>
            <div class="stat-value amount">{{ formatAmount(l.currentRemainingBalance) }}</div>
            <div class="text-muted" style="font-size: 12px;">capital restant dû</div>
          </div>
          <div @click.stop>
            <button class="btn secondary" style="padding: 6px 10px;" @click="openEdit(l)">✎</button>
            <button class="btn danger" style="padding: 6px 10px;" @click="remove(l)">✕</button>
          </div>
        </div>

        <div class="loan-progress">
          <div class="loan-progress-bar" :style="{ width: progressPercent(l) + '%' }"></div>
        </div>

        <div class="loan-meta">
          <div><span class="text-muted">Mensualité</span><br />{{ formatAmount(totalMonthly(l)) }}</div>
          <div><span class="text-muted">Taux</span><br />{{ l.annualRate }} %</div>
          <div><span class="text-muted">Fin prévue</span><br />{{ formatDate(l.payoffDate) }}</div>
          <div><span class="text-muted">Intérêts totaux</span><br />{{ formatAmount(l.totalInterest) }}</div>
        </div>
      </div>
    </div>

    <div v-else class="empty-state">
      Aucun prêt enregistré. Ajoutez votre prêt immobilier (ou autre) pour suivre son remboursement.
    </div>

    <!-- Detail du pret selectionne : graphique + echeancier -->
    <div v-if="selectedLoan" class="card" style="margin-top: 20px;">
      <div class="stat-label" style="margin-bottom: 16px;">
        Évolution du capital restant dû — {{ selectedLoan.name }}
      </div>
      <canvas ref="balanceChart" height="90"></canvas>
    </div>

    <div v-if="selectedLoan" class="card" style="margin-top: 20px;">
      <div class="stat-label" style="margin-bottom: 16px;">Tableau d'amortissement</div>
      <div class="table-scroll loan-schedule-scroll" ref="scheduleScroll">
        <table class="plain-table loan-schedule-table">
          <thead>
            <tr>
              <th>Échéance</th>
              <th>Date</th>
              <th class="num">Mensualité</th>
              <th class="num">Intérêts</th>
              <th class="num">Capital</th>
              <th class="num">Restant dû</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in scheduleRows"
              :key="row.month"
              :ref="(el) => setRowRef(row, el)"
              :class="{ 'row-paid': row.paid, 'row-current': row.isCurrent }"
            >
              <td>{{ row.month }}</td>
              <td>{{ formatDate(row.date) }}</td>
              <td class="num">{{ formatAmount(row.payment) }}</td>
              <td class="num">{{ formatAmount(row.interest) }}</td>
              <td class="num">{{ formatAmount(row.principal) }}</td>
              <td class="num">{{ formatAmount(row.remainingBalance) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="chart-note">
        Les échéances passées ou en cours sont surlignées. Calcul théorique à partir des paramètres
        du prêt (indépendant des transactions saisies).
      </p>
    </div>

    <!-- Modal creation/edition -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div class="modal">
        <h2>{{ editing ? 'Modifier le prêt' : 'Nouveau prêt' }}</h2>
        <div class="form-grid">
          <div class="form-field" style="grid-column: span 2;">
            <label>Nom</label>
            <input v-model="form.name" placeholder="Ex : Prêt immobilier - Résidence principale" />
          </div>
          <div class="form-field">
            <label>Capital emprunté</label>
            <input v-model.number="form.principal" type="number" step="0.01" min="0" />
          </div>
          <div class="form-field">
            <label>Taux annuel (%)</label>
            <input v-model.number="form.annualRate" type="number" step="0.001" min="0" placeholder="Ex : 3.25" />
          </div>
          <div class="form-field">
            <label>Durée (mois)</label>
            <input v-model.number="form.termMonths" type="number" min="1" placeholder="Ex : 240 pour 20 ans" />
          </div>
          <div class="form-field">
            <label>Date de la 1ère mensualité</label>
            <input v-model="form.startDate" type="date" />
          </div>
          <div class="form-field">
            <label>Mensualité (hors assurance)</label>
            <input v-model.number="form.monthlyPayment" type="number" step="0.01" min="0" />
          </div>
          <div class="form-field">
            <label>Assurance mensuelle (optionnel)</label>
            <input v-model.number="form.monthlyInsurance" type="number" step="0.01" min="0" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label>Notes</label>
            <input v-model="form.notes" placeholder="Note libre" />
          </div>
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import Chart from 'chart.js/auto'
import { LoansAPI } from '../services/api'

const loans = ref([])
const selectedId = ref(null)
const schedule = ref([])

const showModal = ref(false)
const editing = ref(null)
const form = ref(emptyForm())

const balanceChart = ref(null)
const scheduleScroll = ref(null)
let balanceChartInstance = null
let currentRowNode = null

function emptyForm() {
  return {
    name: '',
    principal: 0,
    annualRate: 0,
    termMonths: 240,
    startDate: new Date().toISOString().slice(0, 10),
    monthlyPayment: 0,
    monthlyInsurance: null,
    notes: ''
  }
}

const selectedLoan = computed(() => loans.value.find(l => l.id === selectedId.value) || null)

const scheduleRows = computed(() => {
  const today = new Date().toISOString().slice(0, 10)
  let currentAssigned = false
  return schedule.value.map(row => {
    const isCurrent = !currentAssigned && !row.paid
    if (isCurrent) currentAssigned = true
    return { ...row, isCurrent }
  })
})

function totalMonthly(loan) {
  return (loan.monthlyPayment || 0) + (loan.monthlyInsurance || 0)
}

function progressPercent(loan) {
  if (!loan.principal) return 0
  const paid = loan.principal - loan.currentRemainingBalance
  return Math.min(100, Math.max(0, (paid / loan.principal) * 100))
}

function formatAmount(value) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(value || 0)
}

function formatDate(iso) {
  if (!iso) return '—'
  const [y, m, d] = iso.split('-')
  return `${d}/${m}/${y}`
}

async function loadLoans() {
  loans.value = await LoansAPI.list()
  if (!selectedId.value && loans.value.length) {
    selectedId.value = loans.value[0].id
  }
}

async function loadSchedule(id) {
  currentRowNode = null
  const data = await LoansAPI.schedule(id)
  schedule.value = data.rows
  await nextTick()
  renderChart()
  await nextTick()
  scrollToCurrentRow()
}

function setRowRef(row, el) {
  if (row.isCurrent && el) currentRowNode = el
}

function scrollToCurrentRow() {
  if (currentRowNode) {
    currentRowNode.scrollIntoView({ block: 'center' })
  }
}

function select(id) {
  selectedId.value = id
}

/**
 * Ligne pleine pour les echeances deja passees, pointilles pour les
 * echeances a venir (meme codage visuel que le graphique du tableau de
 * bord), pour bien montrer ce qui a deja ete rembourse.
 */
function renderChart() {
  if (!balanceChart.value) return
  const presentIndex = schedule.value.findIndex(r => !r.paid)
  const splitIndex = presentIndex === -1 ? schedule.value.length - 1 : presentIndex

  const pastData = schedule.value.map((r, i) => (i <= splitIndex ? r.remainingBalance : null))
  const futureData = schedule.value.map((r, i) => (i >= splitIndex ? r.remainingBalance : null))

  if (balanceChartInstance) balanceChartInstance.destroy()
  balanceChartInstance = new Chart(balanceChart.value, {
    type: 'line',
    data: {
      labels: schedule.value.map(r => formatDate(r.date)),
      datasets: [
        {
          label: 'Remboursé',
          data: pastData,
          borderColor: '#1F9D6C',
          backgroundColor: 'rgba(31, 157, 108, 0.10)',
          fill: true,
          tension: 0.2,
          pointRadius: 0,
          borderWidth: 2,
          spanGaps: false
        },
        {
          label: 'Restant à rembourser',
          data: futureData,
          borderColor: '#3B6FE0',
          backgroundColor: 'rgba(59, 111, 224, 0.06)',
          borderDash: [6, 5],
          fill: true,
          tension: 0.2,
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
        x: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C', maxRotation: 0, autoSkip: true, maxTicksLimit: 12 } },
        y: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C' } }
      }
    }
  })
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(loan) {
  editing.value = loan
  form.value = {
    name: loan.name,
    principal: loan.principal,
    annualRate: loan.annualRate,
    termMonths: loan.termMonths,
    startDate: loan.startDate,
    monthlyPayment: loan.monthlyPayment,
    monthlyInsurance: loan.monthlyInsurance,
    notes: loan.notes || ''
  }
  showModal.value = true
}

async function save() {
  if (!form.value.name.trim()) {
    alert('Le nom du prêt est obligatoire.')
    return
  }
  if (!form.value.principal || !form.value.termMonths || !form.value.monthlyPayment) {
    alert('Capital, durée et mensualité sont obligatoires.')
    return
  }
  if (editing.value) {
    await LoansAPI.update(editing.value.id, form.value)
  } else {
    const created = await LoansAPI.create(form.value)
    selectedId.value = created.id
  }
  showModal.value = false
  await loadLoans()
  // Si le pret modifie est celui actuellement affiche, on rafraichit aussi
  // son echeancier (les parametres ont pu changer : taux, mensualite...).
  if (selectedId.value) await loadSchedule(selectedId.value)
}

async function remove(loan) {
  if (!confirm(`Supprimer le prêt "${loan.name}" ?`)) return
  await LoansAPI.remove(loan.id)
  if (selectedId.value === loan.id) selectedId.value = null
  await loadLoans()
}

watch(selectedId, (id) => {
  if (id) loadSchedule(id)
  else schedule.value = []
})

onMounted(loadLoans)
</script>

<style scoped>
.loan-grid {
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
}
.loan-card {
  cursor: pointer;
  border: 1px solid transparent;
}
.loan-card.active {
  border-color: var(--azure);
}
.loan-progress {
  height: 6px;
  border-radius: 999px;
  background: var(--ink-800);
  overflow: hidden;
  margin: 14px 0;
}
.loan-progress-bar {
  height: 100%;
  background: var(--mint);
}
.loan-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  font-size: 13px;
}
.loan-schedule-scroll {
  max-height: 420px;
  overflow-y: auto;
}
.loan-schedule-table {
  width: 100%;
}
.loan-schedule-table thead th {
  position: sticky;
  top: 0;
  background: var(--ink-900);
  z-index: 1;
}
.row-paid {
  color: var(--text-muted);
}
.row-current {
  background: var(--azure-dim);
  font-weight: 600;
}
.chart-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-faint);
}
.text-muted {
  color: var(--text-muted);
}
</style>

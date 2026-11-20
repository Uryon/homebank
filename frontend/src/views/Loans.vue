<template>
  <div class="loans-page">
    <div class="page-header">
      <div>
        <h1>Prêts</h1>
        <div class="subtitle">Suivi du capital restant dû et de l'échéancier d'amortissement</div>
      </div>
      <button class="btn" @click="openCreate"><Icon name="plus" size="sm" /> Nouveau prêt</button>
    </div>

    <div v-if="loans.length" class="card-grid loan-grid">
      <div
        v-for="l in loans"
        :key="l.id"
        class="card loan-card"
        :class="{ active: selectedId === l.id }"
        @click="select(l.id)"
      >
        <div class="loan-head">
          <div class="loan-title">
            <div class="stat-label">{{ l.name }}</div>
            <div class="stat-value"><Money :value="Number(l.currentRemainingBalance)" /></div>
            <div class="loan-sub">capital restant dû</div>
          </div>
          <div class="hover-actions" @click.stop>
            <button class="icon-btn" title="Modifier le prêt" :aria-label="'Modifier ' + l.name" @click="openEdit(l)"><Icon name="edit" /></button>
            <button class="icon-btn danger" title="Supprimer le prêt" :aria-label="'Supprimer ' + l.name" @click="remove(l)"><Icon name="trash" /></button>
          </div>
        </div>

        <div class="loan-progress-head">
          <span>Remboursé</span>
          <strong>{{ ownedPercent(l) }} %</strong>
        </div>
        <div class="loan-progress" role="progressbar" :aria-valuenow="ownedPercent(l)" aria-valuemin="0" aria-valuemax="100" :aria-label="'Capital remboursé : ' + ownedPercent(l) + ' %'">
          <div class="loan-progress-bar" :style="{ width: progressPercent(l) + '%' }"></div>
        </div>
        <div class="loan-owned">
          <span class="amount positive">{{ formatAmount(ownedAmount(l)) }}</span>
          <span class="text-muted"> vous appartiennent déjà</span>
        </div>

        <dl class="loan-meta">
          <div><dt>Mensualité</dt><dd>{{ formatAmount(totalMonthly(l)) }}</dd></div>
          <div><dt>Taux</dt><dd>{{ formatRate(l.annualRate) }}</dd></div>
          <div><dt>Fin prévue</dt><dd>{{ formatDate(l.payoffDate) }}</dd></div>
          <div><dt>Intérêts totaux</dt><dd>{{ formatAmount(l.totalInterest) }}</dd></div>
        </dl>
      </div>
    </div>

    <div v-else-if="initialLoad === 'loading'" class="empty-state" role="status">Chargement des prêts…</div>
    <div v-else-if="initialLoad === 'failed'" class="empty-state" role="alert">
      <p>Impossible de charger les prêts. Vos données n'ont pas été modifiées.</p>
      <button class="btn secondary" type="button" @click="loadInitial">Réessayer</button>
    </div>
    <div v-else class="empty-state">
      Aucun prêt enregistré. Ajoutez votre prêt immobilier (ou autre) pour suivre son remboursement.
    </div>

    <!-- Detail du pret selectionne : graphique + echeancier -->
    <div v-if="selectedLoan" class="card loan-detail-card">
      <div class="loan-chart-header">
        <div class="card-title">
          Évolution du capital restant dû — {{ selectedLoan.name }}
        </div>
        <div class="loan-owned-today">
          <span class="amount positive">{{ formatAmount(ownedAmount(selectedLoan)) }}</span>
          <span class="text-muted"> vous appartiennent aujourd'hui</span>
        </div>
      </div>
      <canvas ref="balanceChart" height="90"></canvas>
    </div>

    <div v-if="selectedLoan" class="card loan-detail-card">
      <div class="card-title loan-schedule-title">Tableau d'amortissement</div>
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
        Échéances déjà payées en grisé, prochaine échéance surlignée. Calcul théorique à partir des
        paramètres du prêt (indépendant des transactions saisies).
      </p>
    </div>

    <!-- Modal creation/edition -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div v-dialog="() => (showModal = false)" class="modal" role="dialog" aria-modal="true">
        <h2>{{ editing ? 'Modifier le prêt' : 'Nouveau prêt' }}</h2>
        <div class="form-grid">
          <div class="form-field span-2">
            <label for="loan-name">Nom</label>
            <input id="loan-name" v-model="form.name" maxlength="255" placeholder="Ex : Prêt immobilier - Résidence principale" v-bind="invalidAttrs('name')" @input="clear('name')" />
            <FieldError id="loan-name-error" :message="errors.name" />
          </div>
          <div class="form-field">
            <label for="loan-principal">Capital emprunté</label>
            <AmountInput id="loan-principal" v-model="form.principal" v-bind="invalidAttrs('principal')" @input="clear('principal')" />
            <FieldError id="loan-principal-error" :message="errors.principal" />
          </div>
          <div class="form-field">
            <label for="loan-rate">Taux annuel (%)</label>
            <input id="loan-rate" v-model.number="form.annualRate" type="number" step="0.001" min="0" placeholder="Ex : 3.25" />
          </div>
          <div class="form-field">
            <label for="loan-term">Durée (mois)</label>
            <input id="loan-term" v-model.number="form.termMonths" type="number" min="1" :max="MAX_TERM_MONTHS" placeholder="Ex : 240 pour 20 ans" v-bind="invalidAttrs('termMonths')" @input="clear('termMonths')" />
            <FieldError id="loan-term-error" :message="errors.termMonths" />
          </div>
          <div class="form-field">
            <label for="loan-start-date">Date de la 1ère mensualité</label>
            <input id="loan-start-date" v-model="form.startDate" type="date" :min="MIN_DATE" :max="MAX_DATE" />
          </div>
          <div class="form-field">
            <label for="loan-monthly-payment">Mensualité (hors assurance)</label>
            <AmountInput id="loan-monthly-payment" v-model="form.monthlyPayment" v-bind="invalidAttrs('monthlyPayment')" @input="clear('monthlyPayment')" />
            <FieldError id="loan-monthly-payment-error" :message="errors.monthlyPayment" />
            <div v-if="suggestedMonthlyPayment && suggestedMonthlyPayment !== form.monthlyPayment" class="payment-hint">
              Théorique : <strong>{{ formatAmount(suggestedMonthlyPayment) }}</strong>
              <button type="button" class="payment-hint-btn" @click="form.monthlyPayment = suggestedMonthlyPayment">Utiliser</button>
            </div>
          </div>
          <div class="form-field">
            <label for="loan-monthly-insurance">Assurance mensuelle (optionnel)</label>
            <AmountInput id="loan-monthly-insurance" v-model="form.monthlyInsurance" />
          </div>
          <div class="form-field span-2">
            <label for="loan-notes">Notes</label>
            <input id="loan-notes" v-model="form.notes" maxlength="255" placeholder="Note libre" />
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>

    <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
  </div>
</template>

<script setup>
import { notify, askConfirm } from '../services/feedback'
import { ref, onMounted, onBeforeUnmount, computed, nextTick, watch } from 'vue'
import { useRouteActions } from '../services/routeActions'
import Chart from '../services/chart'
import { LoansAPI } from '../services/api'
import { MAX_DATE, MIN_DATE, formatAmount, formatRate, toLocalIsoDate } from '../services/format'
import {
  MAX_TERM_MONTHS, formatScheduleDate as formatDate, loanPayload, markCurrentRow, monthlyPaymentFor,
  ownedAmount, ownedPercent, progressPercent, termMonthsError, totalMonthly
} from '../services/loanMath'
import { loanBalanceChartConfig } from '../services/loanChart'
import { alertApiError } from '../services/errors'
import { readChartColors } from '../services/chartTheme'
import { effectiveTheme } from '../services/theme'
import AmountInput from '../components/AmountInput.vue'
import FieldError from '../components/FieldError.vue'
import { useFieldErrors } from '../services/fieldErrors'
import Icon from '../components/Icon.vue'
import Money from '../components/Money.vue'
import UndoBar from '../components/UndoBar.vue'
import { useLastAction } from '../services/lastAction'

const loans = ref([])
const selectedId = ref(null)
const schedule = ref([])

const showModal = ref(false)
const editing = ref(null)
const form = ref(emptyForm())
/** Proposée sous le champ Mensualité : celle de la banque peut différer de quelques centimes, on ne l'impose pas. */
const suggestedMonthlyPayment = computed(() => monthlyPaymentFor(form.value.principal, form.value.annualRate, form.value.termMonths))

const balanceChart = ref(null)
const scheduleScroll = ref(null)
let balanceChartInstance = null

// Instance Chart.js liberee en quittant la page (sinon conservee par le
// registre de Chart.js, avec son observateur de redimensionnement, a chaque visite).
onBeforeUnmount(() => {
  balanceChartInstance?.destroy()
  balanceChartInstance = null
})
let currentRowNode = null

// Couleurs du canvas lues au dessin (voir services/chartTheme.js) : on
// redessine au changement de thème, système compris.
watch(effectiveTheme, async () => {
  if (!schedule.value.length) return
  await nextTick()
  renderChart()
})

function emptyForm() {
  return {
    name: '',
    principal: 0,
    annualRate: 0,
    termMonths: 240,
    startDate: toLocalIsoDate(),
    monthlyPayment: 0,
    monthlyInsurance: null,
    notes: ''
  }
}

const selectedLoan = computed(() => loans.value.find(l => l.id === selectedId.value) || null)

/** Échéancier avec l'échéance courante (première non payée) marquée. */
const scheduleRows = computed(() => markCurrentRow(schedule.value))

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

/** Échéances déjà payées laissées visibles au-dessus de l'échéance courante, pour le contexte. */
const PAID_ROWS_ABOVE_CURRENT = 3

function scrollToCurrentRow() {
  // Défilement limité au conteneur du tableau : scrollIntoView() faisait aussi
  // défiler toute la page, et masquait les cartes de prêts en haut.
  // Calé sur des lignes entières sous l'en-tête collant : un centrage simple
  // laissait une ligne à moitié cachée sous l'en-tête.
  const container = scheduleScroll.value
  if (currentRowNode && container) {
    const headerHeight = container.querySelector('thead')?.offsetHeight || 0
    const rowHeight = currentRowNode.offsetHeight
    container.scrollTop = currentRowNode.offsetTop - headerHeight - PAID_ROWS_ABOVE_CURRENT * rowHeight
  }
}

function select(id) {
  selectedId.value = id
}

/** Dessine le capital restant dû (voir services/loanChart.js), en remplaçant le graphique précédent. */
function renderChart() {
  if (!balanceChart.value) return
  if (balanceChartInstance) balanceChartInstance.destroy()
  balanceChartInstance = new Chart(balanceChart.value, loanBalanceChartConfig(schedule.value, {
    principal: selectedLoan.value?.principal ?? null,
    colors: readChartColors()
  }))
}

const { errors, check, clear, invalidAttrs } = useFieldErrors({
  name: 'loan-name',
  principal: 'loan-principal',
  termMonths: 'loan-term',
  monthlyPayment: 'loan-monthly-payment'
})

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  clear()
  showModal.value = true
}

function openEdit(loan) {
  editing.value = loan
  clear()
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
  const f = form.value
  const valid = check({
    name: f.name.trim() ? '' : 'Saisissez le nom du prêt.',
    principal: f.principal ? '' : 'Saisissez le capital emprunté.',
    termMonths: termMonthsError(f.termMonths),
    monthlyPayment: f.monthlyPayment ? '' : 'Saisissez la mensualité.'
  })
  if (!valid) return
  try {
    let undo
    if (editing.value) {
      // Paramètres d'avant la modification, pour le bouton « Annuler ».
      const { id } = editing.value
      const previous = loanPayload(editing.value)
      await LoansAPI.update(id, form.value)
      undo = { label: 'Prêt modifié', run: () => LoansAPI.update(id, previous) }
    } else {
      const created = await LoansAPI.create(form.value)
      selectedId.value = created.id
      undo = {
        label: 'Prêt créé',
        run: async () => {
          await LoansAPI.remove(created.id)
          if (selectedId.value === created.id) selectedId.value = null
        }
      }
    }
    showModal.value = false
    notify(editing.value ? 'Prêt modifié.' : 'Prêt créé.', 'success')
    await loadLoans()
    // Si le pret modifie est celui actuellement affiche, on rafraichit aussi
    // son echeancier (les parametres ont pu changer : taux, mensualite...).
    if (selectedId.value) await loadSchedule(selectedId.value)
    setLastAction(undo.label, async () => {
      await undo.run()
      await loadLoans()
      if (selectedId.value) await loadSchedule(selectedId.value)
    })
  } catch (e) {
    alertApiError('Erreur lors de l’enregistrement du prêt', e)
  }
}

/** Annulation de la dernière création, modification ou suppression de prêt (voir services/lastAction.js). */
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

async function remove(loan) {
  if (!(await askConfirm(`Supprimer le prêt "${loan.name}" ?`))) return
  try {
    await LoansAPI.remove(loan.id)
    notify('Prêt supprimé.', 'success')
    if (selectedId.value === loan.id) selectedId.value = null
    await loadLoans()
    const payload = loanPayload(loan)
    setLastAction('Prêt supprimé', async () => {
      const restored = await LoansAPI.create(payload)
      await loadLoans()
      selectedId.value = restored.id
    })
  } catch (e) {
    alertApiError('Erreur lors de la suppression du prêt', e)
  }
}

watch(selectedId, async (id) => {
  if (!id) {
    schedule.value = []
    return
  }
  try {
    await loadSchedule(id)
  } catch (e) {
    schedule.value = []
    alertApiError('Impossible de charger le tableau d’amortissement', e)
  }
})

/**
 * Premier chargement de la page : « loading », « failed » ou « done ».
 * Sans cet état, « Aucun prêt enregistré » s'affichait pendant le
 * chargement, et restait affiché après un échec comme si la liste était vide.
 */
const initialLoad = ref('loading')

async function loadInitial() {
  initialLoad.value = 'loading'
  try {
    await loadLoans()
    initialLoad.value = 'done'
  } catch (e) {
    initialLoad.value = 'failed'
    alertApiError('Impossible de charger les prêts', e)
  }
}

onMounted(loadInitial)

// Actions lancées depuis la palette de commandes (Ctrl+K), voir services/routeActions.js.
useRouteActions({ 'new-loan': openCreate })
</script>

<style scoped>
/* Cartes de détail du prêt sélectionné (graphique, échéancier). */
.loan-detail-card { margin-top: 20px; }
.loan-chart-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.loan-chart-header .card-title { margin-bottom: 0; }
.loan-owned-today { font-size: 13px; }
.loan-schedule-title { margin-bottom: 16px; }
/* Taux et durée alignés à droite, comme les montants (AmountInput) */
#loan-rate, #loan-term { text-align: right; font-variant-numeric: tabular-nums; }
.payment-hint {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted);
}
.payment-hint strong { color: var(--text-primary); font-variant-numeric: tabular-nums; }
.payment-hint-btn {
  padding: 1px 8px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  background: transparent;
  color: var(--azure);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.payment-hint-btn:hover { background: var(--azure-dim); }
.loans-page .page-header { margin-bottom: 22px; }
.loans-page .loan-card { background: var(--ink-900); border: 1px solid var(--line); box-shadow: var(--shadow-card); padding: 20px; transition: transform .16s ease, box-shadow .16s ease, border-color .16s ease; }
.loans-page .loan-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.loans-page .loan-card.active { border-color: var(--azure); box-shadow: 0 0 0 3px var(--azure-dim); }
.loans-page .loan-card .stat-value { font-size: 25px; letter-spacing: -.035em; font-variant-numeric: tabular-nums; }
.loans-page .loan-meta { padding-top: 13px; border-top: 1px solid var(--line); }
.loans-page .loan-progress-bar { border-radius: 999px; }
.loans-page > .card { border: 1px solid var(--line); box-shadow: var(--shadow-card); }
.loans-page .loan-schedule-table thead th { background: var(--table-head-bg); color: var(--table-head-text); font-size: 12.5px; font-weight: 600; text-transform: none; }
.loans-page .loan-schedule-table tbody td { padding-top: 11px; padding-bottom: 11px; border-bottom: 1px solid var(--line); font-variant-numeric: tabular-nums; }
.loans-page .row-current { background: var(--azure-dim); box-shadow: inset 3px 0 0 var(--azure); }
.loan-grid {
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
}
.loan-card {
  cursor: pointer;
  border: 1px solid transparent;
}
.loan-card.active {
  border-color: var(--azure);
}
.loan-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; }
.loan-sub { font-size: 12.5px; color: var(--text-muted); margin-top: 2px; }
.loan-progress-head { display: flex; justify-content: space-between; margin-top: 18px; font-size: 13px; color: var(--text-muted); }
.loan-progress-head strong { color: var(--text-primary); font-variant-numeric: tabular-nums; }
.loan-owned { font-size: 13px; margin: 0 0 14px; }
.loan-meta dt { font-size: 12.5px; color: var(--text-muted); }
.loan-meta dd { margin: 2px 0 0; font-weight: 600; font-variant-numeric: tabular-nums; }
.loan-meta { margin: 0; }
.card-title { font-size: 15px; font-weight: 600; letter-spacing: -0.01em; color: var(--text-primary); }
.loan-progress {
  height: 6px;
  border-radius: 999px;
  background: var(--ink-700);
  overflow: hidden;
  margin: 8px 0 10px;
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
  position: relative;
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
/* Sur les cellules et non la ligne : la couleur des td (style.css) l'emporterait */
.row-paid td {
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

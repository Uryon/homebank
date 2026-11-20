<template>
  <div class="dashboard-page">
    <div class="page-header">
      <div>
        <h1>Tableau de bord</h1>
        <div class="subtitle">Vue d'ensemble de vos finances</div>
      </div>
      <div class="month-nav">
        <button
          class="month-nav-btn"
          @click="goToPreviousMonth"
          title="Mois précédent (←)"
          aria-label="Mois précédent"
        ><Icon name="chevron-left" size="sm" /></button>
        <button
          class="month-nav-label month-nav-label-btn"
          :disabled="isCurrentMonth"
          @click="goToCurrentMonth"
          title="Revenir au mois actuel"
        >{{ monthLabel }}</button>
        <button
          class="month-nav-btn"
          @click="goToNextMonth"
          title="Mois suivant (→)"
          aria-label="Mois suivant"
        ><Icon name="chevron-right" size="sm" /></button>
      </div>
    </div>

    <div v-if="reportsLoadFailed" class="card reports-error" role="alert">
      <span>Les rapports n'ont pas pu être chargés ({{ monthLabel }}). Vos données n'ont pas été modifiées.</span>
      <button class="btn secondary" type="button" @click="loadMonthlyReports">Réessayer</button>
    </div>

    <nav v-if="todoItems.length" class="todo-banner" aria-label="À traiter">
      <span class="todo-banner-title">À traiter</span>
      <RouterLink v-for="item in todoItems" :key="item.key" :to="item.to" class="todo-banner-item">
        {{ item.label }}
        <Icon name="chevron-right" size="sm" />
      </RouterLink>
    </nav>

    <div class="card-grid">
      <div class="card kpi">
        <div class="stat-label">Solde total</div>
        <div class="stat-value amount" :class="totalBalanceClass">
          <Money :value="totalBalance" />
        </div>
        <div class="kpi-foot">
          <span v-if="balanceDelta" class="kpi-delta" :class="balanceDelta.tone">{{ balanceDelta.text }}</span>
          <Sparkline :values="balanceSeries" color="var(--hero-sparkline)" />
        </div>
      </div>
      <div v-for="kpi in monthKpis" :key="kpi.key" class="card kpi">
        <div class="stat-label">
          {{ kpi.label }} ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount" :class="[kpi.tone, { 'forecast-cell': summary.forecast }]">
          <Money :value="kpi.value" />
        </div>
        <div v-if="kpi.rate" class="kpi-rate">{{ kpi.rate }}</div>
        <div v-if="kpi.yearAgo" class="kpi-yoy" :class="kpi.yearAgo.tone" :title="kpi.yearAgo.title">{{ kpi.yearAgo.text }}</div>
        <div class="kpi-foot">
          <span v-if="kpi.delta" class="kpi-delta" :class="kpi.delta.tone">{{ kpi.delta.text }}</span>
          <Sparkline :values="kpi.series" :color="sparkColor(kpi.delta)" />
        </div>
      </div>
    </div>

    <!-- Liste des comptes : réservée à l'export PDF (masquée à l'écran, où la
         sidebar l'affiche déjà — la sidebar est justement retirée du PDF, voir
         style.css). Compacte : 3 colonnes, une ligne par compte. -->
    <div v-if="printedAccounts.length" class="card accounts-print-card">
      <div class="accounts-print-header">
        <span class="stat-label">Comptes</span>
        <span class="accounts-print-count">{{ printedAccountsCountLabel }}</span>
      </div>
      <ul class="accounts-print-list">
        <li v-for="entry in printedAccounts" :key="entry.account.id" class="accounts-print-item">
          <span class="accounts-print-dot" :class="entry.typeClass"></span>
          <span class="accounts-print-name">{{ entry.account.name }}</span>
          <span class="accounts-print-balance amount" :class="entry.balanceClass">
            {{ entry.formattedBalance }}
          </span>
        </li>
      </ul>
    </div>

    <div class="card forecast-card chart-card" :class="{ 'section-collapsed': isCollapsed('solde') }">
      <ForecastChart :points="forecast.points" :loan-owned-series="loanOwnedSeries" :monthly-trend="forecast.monthlyTrend" />
    </div>

    <div class="top-row-grid">
      <div class="card compact-card savings-card chart-card" :class="{ 'section-collapsed': isCollapsed('epargne') }">
        <div class="card-title"><SectionToggle section="epargne" label="Épargne mensuelle" />Épargne mensuelle — {{ periodLabel }}</div>
        <div class="section-body savings-body">
          <SavingsChart :points="savingsPoints" />
          <p class="chart-note">Épargne = revenus du mois − dépenses du mois. Cliquez sur une barre pour voir les transactions du mois.</p>
        </div>
      </div>

      <PeriodicCards :periodic="periodicExpenses" />
    </div>

    <WhereMoneyCard
      :data="whereMoneyData"
      :range="whereMoneyRange"
      :scope="whereMoneyScope"
      :month-label="monthLabel"
      :period-label="whereMoneyPeriodLabel"
      @update:scope="setWhereMoneyScope"
    />

    <MonthlyAnalysisTables :analysis="monthlyAnalysis" :period-label="periodLabel" />

    <CellTransactionsPopover
      :visible="hover.visible"
      :loading="hover.loading"
      :x="hover.x"
      :y="hover.y"
      :above="hover.above"
      :title="hover.title"
      :rows="hover.rows"
      :total="hover.total"
      @stay="cancelHideTimer"
      @leave="scheduleHide"
    />
  </div>
</template>

<script setup>
/**
 * Tableau de bord : cartes du mois, graphiques et tableaux d'analyse sur une
 * fenêtre glissante de 12 mois se terminant sur le mois affiché (flèches de
 * l'en-tête). La page charge les rapports et les répartit entre ses cartes
 * (components/dashboard/), qui partagent le détail des montants au survol et
 * au clic (services/cellDetail.js).
 */
import { ref, shallowRef, onMounted, onBeforeUnmount, computed } from 'vue'
import { monthStepFromKey } from '../services/shortcuts'
import { ReportsAPI, LoansAPI, TransactionsAPI } from '../services/api'
import { needsCategory } from '../services/categories'
import { dataStore } from '../services/dataStore'
import { accountBalanceRows, accountCountLabel, activeAccounts, amountToneClass, computeNetWorth, sortAccountsForDisplay } from '../services/accounts'
import { alertApiError } from '../services/errors'
import { provideCellDetail } from '../services/cellDetail'
import { provideCollapsibleSections } from '../services/collapsibleSections'
import SectionToggle from '../components/dashboard/SectionToggle.vue'
import CellTransactionsPopover from '../components/CellTransactionsPopover.vue'
import Money from '../components/Money.vue'
import Sparkline from '../components/Sparkline.vue'
import Icon from '../components/Icon.vue'
import ForecastChart from '../components/dashboard/ForecastChart.vue'
import SavingsChart from '../components/dashboard/SavingsChart.vue'
import PeriodicCards from '../components/dashboard/PeriodicCards.vue'
import WhereMoneyCard from '../components/dashboard/WhereMoneyCard.vue'
import MonthlyAnalysisTables from '../components/dashboard/MonthlyAnalysisTables.vue'
import {
  balanceChangeOverPeriod,
  buildLoanOwnedSeries,
  dashboardTodoItems,
  last12MonthsRange,
  monthDelta,
  monthKeyOf,
  monthRange,
  monthTitle,
  monthWindowLabel,
  savingsRateLabel,
  yearAgoKpi,
  yearOverYearCoverage
} from '../services/dashboardAnalysis'

// Détail des montants (popover au survol, transactions au clic), partagé par toutes les cartes.
const { hover, scheduleHide, cancelHideTimer } = provideCellDetail()

// Cartes repliables, état mémorisé dans le navigateur (voir services/collapsibleSections.js).
const { collapsed: collapsedSections } = provideCollapsibleSections('homebank-tableau-de-bord-replie')
function isCollapsed(section) {
  return collapsedSections.value.has(section)
}

// --- Bandeau « À traiter » : ce qui attend une action, avec un lien direct ---
// Transactions lues dans le cache partagé (comme le badge de la barre latérale) ;
// les doublons, eux, sont comptés côté backend : une requête, sans bloquer la page.
const duplicateCount = ref(0)
const todoItems = computed(() => {
  const transactions = dataStore.state.transactions
  return dashboardTodoItems({
    pending: transactions.filter(t => !t.validated).length,
    uncategorized: transactions.filter(needsCategory).length,
    duplicates: duplicateCount.value
  })
})

async function loadDuplicateCount() {
  try {
    duplicateCount.value = (await TransactionsAPI.duplicateCount()).count
  } catch {
    duplicateCount.value = 0 // point secondaire : le bandeau se passe simplement de ce chiffre
  }
}

// --- Mois affiché ---

/**
 * Dernier mois calendaire terminé (le mois en cours n'est pas complet) :
 * point de départ de la navigation. Calculé une fois : la date du jour ne
 * change pas en cours de session.
 */
const lastCompletedMonthStart = (() => {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth() - 1, 1)
})()

/**
 * Mois des cartes du haut, navigable sans limite haute (les rapports
 * affichent alors le prévisionnel, voir ServiceAnalyseMensuelle#completeAnalysisRow).
 */
const viewedMonthStart = ref(lastCompletedMonthStart)
const monthLabel = computed(() => monthTitle(viewedMonthStart.value))

function shiftViewedMonth(step) {
  viewedMonthStart.value = new Date(viewedMonthStart.value.getFullYear(), viewedMonthStart.value.getMonth() + step, 1)
  loadMonthlyReports()
}
const goToPreviousMonth = () => shiftViewedMonth(-1)
const goToNextMonth = () => shiftViewedMonth(1)

/** Vrai quand le mois affiché est déjà le dernier mois terminé (clic sur le libellé alors sans effet). */
const isCurrentMonth = computed(() => viewedMonthStart.value.getTime() === lastCompletedMonthStart.getTime())

/** Clic sur le libellé du mois : retour direct au dernier mois terminé. */
function goToCurrentMonth() {
  if (isCurrentMonth.value) return
  viewedMonthStart.value = lastCompletedMonthStart
  loadMonthlyReports()
}

/** Fenêtre de 12 mois en clair : « 12 derniers mois », ou les mois exacts dès qu'on navigue. */
const periodLabel = computed(() => {
  if (isCurrentMonth.value) return '12 derniers mois'
  const { from, to } = last12MonthsRange(viewedMonthStart.value)
  return monthWindowLabel(from, to)
})

// --- Données des rapports (shallowRef : passées telles quelles aux graphiques) ---
const forecast = shallowRef({ points: [], monthlyTrend: 0 })
const loanOwnedSeries = shallowRef(null)
const savingsPoints = shallowRef([])
const monthlyAnalysis = shallowRef(null)
const periodicExpenses = shallowRef({ expenses: { items: [], total: 0 }, revenues: { items: [], total: 0 } })

// --- Chiffres clés ---
// Comptes et soldes lus dans le cache partagé (même source que la barre latérale).
const accounts = computed(() => dataStore.state.accounts)
const balances = computed(() => dataStore.state.balances)
const totalBalance = computed(() => computeNetWorth(accounts.value, balances.value))
const totalBalanceClass = computed(() => amountToneClass(totalBalance.value))
/** Solde réel de fin de mois sur 12 mois (sans le prévisionnel), pour la courbe miniature du solde. */
const balanceSeries = computed(() => forecast.value.points.filter(p => p.type !== 'previsionnel').map(p => Number(p.balance)))
const balanceDelta = computed(() => balanceChangeOverPeriod(balanceSeries.value, '12 mois'))

/**
 * Cartes Revenus / Dépenses / Net : dernier mois du résumé de l'analyse
 * mensuelle (toujours exactement le mois affiché, prévisionnel compris quand
 * on navigue dans le futur) plutôt qu'un appel séparé.
 */
const summary = computed(() => monthlyAnalysis.value?.summary.at(-1) || { income: 0, expenses: 0, net: 0 })
/** Même mois un an plus tôt : dernier mois de la fenêtre précédente, si l'historique le couvre. */
const summaryYearAgo = computed(() => {
  const analysis = monthlyAnalysis.value
  if (!yearOverYearCoverage(analysis).available) return null
  return analysis.previousSummary?.at(-1) ?? null
})
const yearAgoMonthLabel = computed(() => {
  const date = new Date(viewedMonthStart.value)
  date.setFullYear(date.getFullYear() - 1)
  return date.toLocaleDateString('fr-FR', { month: 'short', year: 'numeric' })
})

/** Cartes Revenus / Dépenses / Net, rendues par le même gabarit. */
const monthKpis = computed(() => {
  const rows = monthlyAnalysis.value?.summary || []
  const series = (field) => rows.map(m => Number(m[field]))
  const { income, expenses, net } = summary.value
  const yearAgo = summaryYearAgo.value
  const label = yearAgoMonthLabel.value
  const incomeSeries = series('income')
  const expensesSeries = series('expenses')
  const netSeries = series('net')
  return [
    { key: 'income', label: 'Revenus', value: Number(income), tone: 'positive', series: incomeSeries,
      delta: monthDelta(incomeSeries, true), yearAgo: yearAgoKpi(income, yearAgo?.income, 'income', label) },
    { key: 'expenses', label: 'Dépenses', value: Number(expenses), tone: 'negative', series: expensesSeries,
      delta: monthDelta(expensesSeries, false), yearAgo: yearAgoKpi(expenses, yearAgo?.expenses, 'expense', label) },
    { key: 'net', label: 'Net', value: Number(net), tone: net >= 0 ? 'positive' : 'negative', series: netSeries,
      delta: monthDelta(netSeries, true), yearAgo: yearAgoKpi(net, yearAgo?.net, 'net', label), rate: savingsRateLabel(income, net) }
  ]
})

/** La courbe prend la couleur de la variation (bonne nouvelle = vert) : une baisse des dépenses se lit comme positive. */
function sparkColor(delta) {
  if (!delta) return 'var(--text-faint)'
  return delta.tone === 'good' ? 'var(--chart-positive)' : 'var(--chart-negative)'
}

// Comptes actifs seulement, comme la barre latérale que cette carte remplace dans le PDF.
const printedAccounts = computed(() => accountBalanceRows(sortAccountsForDisplay(activeAccounts(accounts.value)), balances.value))
const printedAccountsCountLabel = computed(() => accountCountLabel(printedAccounts.value.length))

// --- « Où va mon argent ? » : mois affiché ('month') ou ses 12 mois ('year') ---
const whereMoneyScope = ref('year')
const whereMoneyData = shallowRef([])
const whereMoneyRange = ref({ from: '', to: '' })
const whereMoneyPeriodLabel = computed(() => (whereMoneyScope.value === 'month' ? monthLabel.value : periodLabel.value))
let whereMoneyToken = 0

function whereMoneyRangeFor(monthStart) {
  return whereMoneyScope.value === 'year' ? last12MonthsRange(monthStart) : monthRange(monthKeyOf(monthStart))
}

/** Bascule mois / 12 mois : seul ce bloc est rechargé. */
async function setWhereMoneyScope(scope) {
  if (whereMoneyScope.value === scope) return
  whereMoneyScope.value = scope
  const token = ++whereMoneyToken
  const range = whereMoneyRangeFor(viewedMonthStart.value)
  try {
    const data = await ReportsAPI.expensesByCategory(range)
    if (token !== whereMoneyToken) return
    whereMoneyRange.value = range
    whereMoneyData.value = data
  } catch (e) {
    alertApiError('Erreur lors du chargement de la répartition des dépenses', e)
  }
}

// --- Chargement ---

/**
 * Prêts et échéanciers : indépendants du mois affiché, chargés une seule fois
 * pour la visite (et non à chaque flèche) ; un échec est retenté au
 * chargement suivant.
 */
let loansPromise = null
function loadLoans() {
  if (!loansPromise) {
    loansPromise = LoansAPI.list().then(async (loansList) => ({
      loansList,
      schedules: await Promise.all(loansList.map(l => LoansAPI.schedule(l.id)))
    }))
    loansPromise.catch(() => { loansPromise = null })
  }
  return loansPromise
}

// Jeton du dernier chargement mensuel : en cliquant vite sur les flèches, une
// réponse plus ancienne arrivée en dernier affichait sinon un autre mois.
let monthlyReportsToken = 0
/** Dernier chargement des rapports en échec : encadré « Réessayer ». */
const reportsLoadFailed = ref(false)

/**
 * Recharge tout ce qui dépend du mois affiché : cartes du haut, solde,
 * épargne, « Où va mon argent ? » et tableaux d'analyse, pour que tout le
 * tableau de bord se décale ensemble.
 */
async function loadMonthlyReports() {
  const token = ++monthlyReportsToken
  // Fenêtre glissante de 12 mois se terminant sur le mois affiché.
  const referenceMonth = monthKeyOf(viewedMonthStart.value)
  whereMoneyToken++ // une bascule mois / 12 mois encore en vol est remplacée par ce chargement
  const range = whereMoneyRangeFor(viewedMonthStart.value)
  let reports
  try {
    reports = await Promise.all([
      ReportsAPI.balanceForecast({ referenceMonth }),
      ReportsAPI.monthlySavings({ months: 12, referenceMonth }),
      ReportsAPI.expensesByCategory(range),
      ReportsAPI.monthlyCategoryAnalysis({ referenceMonth }),
      loadLoans()
    ])
  } catch (e) {
    if (token === monthlyReportsToken) {
      reportsLoadFailed.value = true
      alertApiError('Erreur lors du chargement des rapports', e)
    }
    return
  }
  if (token !== monthlyReportsToken) return
  reportsLoadFailed.value = false
  const [balanceForecast, savings, whereMoney, monthly, { loansList, schedules }] = reports
  forecast.value = { points: balanceForecast.points, monthlyTrend: Number(balanceForecast.monthlyTrend) }
  loanOwnedSeries.value = buildLoanOwnedSeries(balanceForecast.points, loansList, schedules)
  savingsPoints.value = savings
  whereMoneyRange.value = range
  whereMoneyData.value = whereMoney
  monthlyAnalysis.value = monthly
}

/**
 * Tout part en même temps. Les rapports n'attendent pas le cache partagé :
 * ce qui en dépend (icônes de catégories, soldes, bandeau « À traiter ») se
 * met à jour seul.
 */
function loadDashboard() {
  // Échec signalé par le bandeau « Serveur injoignable » d'App.vue.
  dataStore.ensureLoaded().catch(() => {})
  // Les périodiques ne dépendent pas du mois affiché (dernière occurrence connue).
  ReportsAPI.periodicExpenses()
    .then(periodic => { periodicExpenses.value = periodic })
    .catch(e => alertApiError('Erreur lors du chargement du tableau de bord', e))
  loadDuplicateCount()
  loadMonthlyReports()
}

/** ← / → : mois précédent / suivant, ignoré pendant qu'une fenêtre ou la palette est ouverte. */
function onMonthKeydown(event) {
  const step = monthStepFromKey(event)
  if (!step || document.querySelector('.modal-overlay, .feedback-overlay, .palette-overlay')) return
  event.preventDefault()
  shiftViewedMonth(step < 0 ? -1 : 1)
}
onMounted(() => window.addEventListener('keydown', onMonthKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onMonthKeydown)
  // Un chargement encore en vol ne doit plus rien appliquer.
  monthlyReportsToken++
  whereMoneyToken++
})

// Données demandées dès la création de la page, sans attendre son premier
// rendu : ~120 ms plus tôt (mesuré). Les graphiques se dessinent à l'arrivée
// des données, une fois leur canvas monté.
loadDashboard()
</script>

<style scoped>
/* --- Bandeau « À traiter » : liens vers ce qui attend une action --- */
.todo-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
  margin-bottom: 18px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--amber);
  border-radius: var(--radius-md);
  background: var(--ink-900);
  box-shadow: var(--shadow-xs);
}
.todo-banner-title {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--amber);
  margin-right: 4px;
}
.todo-banner-item {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 4px 6px 4px 10px;
  border-radius: 999px;
  background: var(--ink-800);
  border: 1px solid var(--line);
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}
.todo-banner-item:hover { border-color: var(--azure); color: var(--azure); }
.todo-banner-item .icon { color: var(--text-faint); }
.todo-banner-item:hover .icon { color: inherit; }

/* --- Chiffres clés : variation + courbe miniature --- */
/* Taux d'épargne sous le solde du mois. */
.kpi-rate { margin-top: 4px; font-size: 12.5px; color: var(--text-muted); font-variant-numeric: tabular-nums; }
/* Même mois un an plus tôt (voir monthKpis) : teinte selon le sens de l'écart. */
.kpi-yoy { margin-top: 4px; font-size: 12.5px; font-weight: 600; color: var(--text-muted); font-variant-numeric: tabular-nums; }
.kpi-yoy.good { color: var(--mint); }
.kpi-yoy.bad { color: var(--coral); }
.kpi { display: flex; flex-direction: column; }
.kpi-foot {
  margin-top: auto;
  padding-top: 14px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}
.kpi-delta { font-size: 12.5px; font-weight: 600; white-space: nowrap; }
.kpi-delta.good { color: var(--mint); }
.kpi-delta.bad { color: var(--coral); }
/* Carte « Solde total » : teintés dédiées, lisibles sur --hero-bg. */
.kpi:first-child .kpi-delta.good { color: var(--hero-positive); }
.kpi:first-child .kpi-delta.bad { color: var(--hero-negative); }

/* Échec de chargement des rapports : message et bouton « Réessayer » sur une ligne. */
.reports-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  color: var(--text-primary);
}

/*
 * position: fixed (pas sticky) : .page-header est un bandeau très court, un
 * enfant en sticky ne resterait "accroche" que tant que ce bandeau est
 * encore à l'écran, puis défilerait avec lui (testé : les flèches
 * disparaissaient des les premiers pixels de scroll). fixed les maintient
 * ancrées en haut à droite quelle que soit la position de scroll dans la
 * page. Fond translucide flouté (backdrop-filter) + bordure + ombre : reste
 * lisible par-dessus le contenu qui défile dessous sans le masquer d'un bloc
 * opaque.
 */
.month-nav {
  display: flex;
  align-items: center;
  gap: 10px;
  position: fixed;
  top: 24px;
  /* Calée sur le bord droit du contenu : au-delà de 252 + 1600 px (sidebar +
     max-width de .main-content, voir style.css), le contenu ne s'élargit plus
     et 40px depuis le bord de la fenêtre la ferait déborder de la grille.
     100% = largeur de la fenêtre (bloc conteneur d'un élément fixed),
     1808px = 252 + 1600 - 44 (padding droit de .main-content). */
  right: max(40px, calc(100% - 1808px));
  z-index: 50;
  background: color-mix(in srgb, var(--ink-900) 78%, transparent);
  -webkit-backdrop-filter: blur(14px) saturate(1.4);
  backdrop-filter: blur(14px) saturate(1.4);
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
}
.month-nav-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--ink-800);
  color: var(--text-primary);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}
.month-nav-btn:hover:not(:disabled) {
  background: var(--ink-700);
}
.month-nav-btn:disabled {
  opacity: 0.4;
  cursor: default;
}
.month-nav-label {
  min-width: 140px;
  text-align: center;
  font-weight: 700;
  font-size: 14px;
  color: var(--text-primary);
}
.month-nav-label-btn {
  border: 0;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
  border-radius: var(--radius-sm);
  padding: 4px 0;
}
.month-nav-label-btn:hover:not(:disabled) {
  background: var(--ink-800);
}
.month-nav-label-btn:disabled {
  cursor: default;
}

/* Rangée "Épargne mensuelle" + "Dépenses/Revenus périodiques" sur la même
   ligne à l'écran (demande : réduire la largeur de ces trois cartes) :
   1 colonne pour l'épargne, 2 colonnes (via .periodic-grid imbriqué) pour les
   périodiques, donc trois blocs de largeur comparable. En impression, cette
   grille est désactivée (voir @media print plus bas) pour retrouver la mise
   en page d'origine (épargne pleine largeur en page 1, périodiques en page 2
   — .periodic-grid garde son saut de page dédié, inchangé).
   Pas d'align-items: la valeur par défaut (stretch) étire la carte épargne
   (moins haute) à la même hauteur que la colonne périodiques : demande
   explicite de faire correspondre les hauteurs. */
.top-row-grid {
  display: grid;
  grid-template-columns: 1fr 2fr;
  gap: 20px;
  margin-bottom: 28px;
}
@media (max-width: 1100px) {
  .top-row-grid { grid-template-columns: 1fr; }
}

/* La carte « Épargne mensuelle » est étirée à la hauteur des cartes
   périodiques voisines (stretch de .top-row-grid) : colonne flex dont le
   graphique (voir SavingsChart.vue) absorbe l'espace restant. */
.savings-card {
  display: flex;
  flex-direction: column;
}
.savings-body {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
}

/* Cartes Revenus/Dépenses/Net du haut : même traitement (italique, même
   teinte que le previsionnel du graphique/tableau) quand summary.forecast
   est vrai (voir loadMonthlyReports). */
.stat-value.forecast-cell {
  color: var(--text-muted);
  font-style: italic;
}

/* Même recette visuelle que .future-badge dans TransactionRow.vue (badge
   discret "Prévu"), dupliquee ici plutot que globalisee : les badges de ce
   type restent scoped par composant dans ce projet. */
.forecast-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--ink-700);
  color: var(--text-muted);
  font-size: 10px;
  font-weight: 700;
  text-transform: none;
  letter-spacing: .04em;
  vertical-align: middle;
}

/* Sur mobile, la navigation est déjà collée en haut de l'écran : un sélecteur
   de mois en position fixe la recouvrirait. Il rejoint le flux de l'en-tête. */
@media (max-width: 760px) {
  /* Une seule ligne sur toute la largeur : flèches aux bords, mois au centre. */
  .month-nav {
    position: static;
    width: 100%;
    max-width: 100%;
    flex-wrap: nowrap;
    margin-bottom: 0;
  }
  .month-nav-label {
    flex: 1;
    min-width: 0;
  }
  /* Même écart sous l'en-tête que les autres pages en mobile. */
  .dashboard-page > .page-header { margin-bottom: 20px; }
}

/* Le position:fixed de .month-nav se répète sur chaque page imprimée et
   chevauche l'entete — on le desactive pour qu'il rejoigne le flux normal
   du .page-header, et on masque les flèches,
   inutiles sur un PDF. */
@media print {
  .month-nav {
    position: static;
    box-shadow: none;
    background: transparent;
    border: none;
    padding: 0;
  }
  .month-nav-btn {
    display: none;
  }
}

/* Page 1 de l'export : cartes de solde/revenus/dépenses/net, évolution du
   solde, puis depenses/revenus periodiques a la suite si la place le permet
   (demande explicite : plus de saut de page force vers la page 2 — chaque
   carte garde juste son "avoid" habituel, voir .card dans style.css, pour ne
   jamais etre coupee en deux si elle doit basculer sur la page suivante).
   Le graphique "Epargne mensuelle" est exclu de l'export (demande
   explicite) : masque uniquement a l'impression, il reste visible a l'ecran
   — ca degage justement la place qui permet aux periodiques de remonter en
   page 1. .top-row-grid (epargne + periodiques cote a cote a l'ecran, voir
   plus haut) repasse en bloc simple : .periodic-grid retrouve sa pleine
   largeur juste apres, dans le flux normal. */
@media print {
  .top-row-grid {
    display: block;
    margin-bottom: 0;
  }
  .savings-card {
    display: none;
  }
}

/* Liste des comptes de la première page du PDF : n'existe qu'a l'impression
   (la sidebar la montre deja a l'ecran). Volontairement compacte — padding
   reduit, 3 colonnes (colonnes CSS : l'ordre de lecture descend dans chaque
   colonne, les courants d'abord), une ligne fine par compte — pour ne pas
   repousser les graphiques suivants sur la page 2. Chaque compte reste
   insecable (break-inside) pour ne jamais etre coupe entre deux colonnes. */
.accounts-print-card {
  display: none;
}
@media print {
  .accounts-print-card {
    display: block;
    padding: 10px 16px 6px;
    margin-bottom: 14px;
  }
  .accounts-print-header {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 4px;
  }
  .accounts-print-header .stat-label {
    margin-bottom: 0;
  }
  .accounts-print-count {
    font-size: 11px;
    color: var(--text-faint);
  }
  .accounts-print-list {
    list-style: none;
    margin: 0;
    padding: 0;
    column-count: 3;
    column-gap: 22px;
    column-rule: 1px solid var(--line);
  }
  .accounts-print-item {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 4px 0;
    border-bottom: 1px solid var(--line);
    break-inside: avoid;
    page-break-inside: avoid;
    font-size: 11.5px;
    line-height: 1.25;
  }
  .accounts-print-dot {
    flex: 0 0 7px;
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--text-faint);
  }
  .accounts-print-dot.checking { background: var(--azure); }
  .accounts-print-dot.savings { background: var(--mint); }
  .accounts-print-dot.cash { background: var(--amber); }
  .accounts-print-dot.credit_card { background: var(--coral); }
  .accounts-print-name {
    flex: 1 1 auto;
    min-width: 0;
    color: var(--text-primary);
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .accounts-print-balance {
    flex: 0 0 auto;
    font-size: 11.5px;
    font-weight: 600;
    white-space: nowrap;
  }
}


/* PDF au format téléphone (page étroite, voir export/export-dashboard.mjs) :
   une colonne de comptes, sinon leurs noms seraient réduits à une lettre. */
@media print and (max-width: 600px) {
  .accounts-print-list {
    column-count: 1;
  }
}

/* Cartes de graphique (solde, épargne) : contenu dans components/dashboard/. */
.chart-card {
  border: 1px solid var(--line);
  box-shadow: var(--shadow-card);
}
.forecast-card { padding: 22px 24px 18px; margin-bottom: 20px; }
@media (max-width: 640px) {
  .forecast-card { padding: 16px; }
}

/* PDF : interrupteur des prêts remplacé par une légende fixe (voir ForecastChart.vue),
   flèches des liens « À traiter » retirées (non cliquables sur papier). */
@media print {
  .todo-banner-item .icon {
    display: none;
  }
}
</style>

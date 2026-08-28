<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Tableau de bord</h1>
        <div class="subtitle">Vue d'ensemble de vos finances</div>
      </div>
      <div class="month-nav">
        <button
          class="month-nav-btn"
          @click="goToPreviousMonth"
          title="Mois précédent"
          aria-label="Mois précédent"
        >‹</button>
        <button
          class="month-nav-label month-nav-label-btn"
          :disabled="isCurrentMonth"
          @click="goToCurrentMonth"
          title="Revenir au mois actuel"
        >{{ monthLabel }}</button>
        <button
          class="month-nav-btn"
          :disabled="!canGoToNextMonth"
          @click="goToNextMonth"
          title="Mois suivant"
          aria-label="Mois suivant"
        >›</button>
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
        <template v-if="hasLoans">
          La courbe inclut aussi le capital déjà remboursé sur l'ensemble de vos prêts (connu à l'avance,
          indépendant de la projection de solde).
        </template>
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
        <div v-if="periodicExpenses.expenses.items.length">
          <table class="plain-table">
            <tbody>
              <tr v-for="item in periodicExpenses.expenses.items" :key="item.payee">
                <td>{{ item.payee || 'Sans nom' }}</td>
                <td class="num amount negative"
                    @mouseenter="scheduleHover($event, item.payee || 'Sans nom', () => periodicItemRows(item, -1))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(item.amount) }}
                </td>
              </tr>
            </tbody>
          </table>
          <div class="periodic-total">
            <span>Total</span>
            <span class="amount negative"
                  @mouseenter="scheduleHover($event, 'Dépenses périodiques', () => periodicGroupRows(periodicExpenses.expenses, -1))"
                  @mouseleave="onCellLeave">
              {{ formatAmount(periodicExpenses.expenses.total) }}
            </span>
          </div>
        </div>
        <div v-else class="empty-state">
          Aucune dépense périodique marquée. Coche "Périodique" sur une transaction récurrente
          (loyer, abonnement...) dans l'onglet Transactions, ou regarde l'onglet "Périodiques"
          pour des suggestions automatiques.
        </div>
      </div>

      <div class="card">
        <div class="stat-label" style="margin-bottom: 16px;">Revenus périodiques</div>
        <div v-if="periodicExpenses.revenues.items.length">
          <table class="plain-table">
            <tbody>
              <tr v-for="item in periodicExpenses.revenues.items" :key="item.payee">
                <td>{{ item.payee || 'Sans nom' }}</td>
                <td class="num amount positive"
                    @mouseenter="scheduleHover($event, item.payee || 'Sans nom', () => periodicItemRows(item, 1))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(item.amount) }}
                </td>
              </tr>
            </tbody>
          </table>
          <div class="periodic-total">
            <span>Total</span>
            <span class="amount positive"
                  @mouseenter="scheduleHover($event, 'Revenus périodiques', () => periodicGroupRows(periodicExpenses.revenues, 1))"
                  @mouseleave="onCellLeave">
              {{ formatAmount(periodicExpenses.revenues.total) }}
            </span>
          </div>
        </div>
        <div v-else class="empty-state">
          Aucun revenu périodique marqué. Coche "Périodique" sur une transaction récurrente
          (salaire, remboursement régulier...) dans l'onglet Transactions.
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
                <td class="num amount negative"
                    @mouseenter="scheduleHover($event, row.category + ' — 12 derniers mois', () => fetchCellTransactions(whereMoneyRange.from, whereMoneyRange.to, 'expense', row.uncategorized ? { uncategorized: true } : { categoryId: row.categoryId }))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(row.total) }}
                </td>
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
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-income-' + month.key" class="num"
                  @mouseenter="scheduleHover($event, 'Revenus — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income'))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(month.income) }}
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Revenus — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income'))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(monthlyAnalysis.incomeTotal.total) }}
              </td>
            </tr>
            <tr>
              <td class="category-sticky summary-label">Dépenses</td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-expense-' + month.key" class="num"
                  @mouseenter="scheduleHover($event, 'Dépenses — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense'))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(month.expenses) }}
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Dépenses — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense'))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(monthlyAnalysis.expenseTotal.total) }}
              </td>
            </tr>
            <tr class="monthly-balance-row">
              <td class="category-sticky summary-label"><strong>Solde du mois</strong></td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-net-' + month.key"
                  class="num" :class="Number(month.net) >= 0 ? 'positive' : 'negative'"
                  @mouseenter="scheduleHover($event, 'Solde — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, null))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(month.net) }}</strong>
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Solde — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, null))"
                  @mouseleave="onCellLeave">
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
                <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                    @mouseenter="scheduleHover($event, group.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income', groupCategoryFilter(group)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(group.values[month.key]) }}
                </td>
                <td class="num summary-col"
                    @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', groupCategoryFilter(group)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(group.average) }}
                </td>
                <td class="num summary-col"
                    @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', groupCategoryFilter(group)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(group.total) }}
                </td>
              </tr>

              <template v-if="!isMonthlyGroupCollapsed('income', group.key)">
                <tr v-for="child in group.children" :key="'income-child-' + child.key"
                    class="subcategory-row">
                  <td class="category-sticky category-name subcategory-name">
                    └ {{ child.name }}
                  </td>
                  <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                      @mouseenter="scheduleHover($event, child.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income', childCategoryFilter(child)))"
                      @mouseleave="onCellLeave">
                    {{ formatAmount(child.values[month.key]) }}
                  </td>
                  <td class="num summary-col"
                      @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', childCategoryFilter(child)))"
                      @mouseleave="onCellLeave">
                    {{ formatAmount(child.average) }}
                  </td>
                  <td class="num summary-col"
                      @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', childCategoryFilter(child)))"
                      @mouseleave="onCellLeave">
                    {{ formatAmount(child.total) }}
                  </td>
                </tr>
              </template>
            </template>

            <tr class="total-row income-total-row">
              <td class="category-sticky"><strong>{{ monthlyAnalysis.incomeTotal.name }}</strong></td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  @mouseenter="scheduleHover($event, 'Total revenus — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income'))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(monthlyAnalysis.incomeTotal.values[month.key]) }}</strong>
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Total revenus — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income'))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(monthlyAnalysis.incomeTotal.average) }}</strong>
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Total revenus — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income'))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(monthlyAnalysis.incomeTotal.total) }}</strong>
              </td>
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
                <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                    @mouseenter="scheduleHover($event, group.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense', groupCategoryFilter(group)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(group.values[month.key]) }}
                </td>
                <td class="num summary-col"
                    @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', groupCategoryFilter(group)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(group.average) }}
                </td>
                <td class="num summary-col"
                    @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', groupCategoryFilter(group)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(group.total) }}
                </td>
              </tr>

              <template v-if="!isMonthlyGroupCollapsed('expense', group.key)">
                <tr v-for="child in group.children" :key="'expense-child-' + child.key"
                    class="subcategory-row">
                  <td class="category-sticky category-name subcategory-name">
                    └ {{ child.name }}
                  </td>
                  <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                      @mouseenter="scheduleHover($event, child.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense', childCategoryFilter(child)))"
                      @mouseleave="onCellLeave">
                    {{ formatAmount(child.values[month.key]) }}
                  </td>
                  <td class="num summary-col"
                      @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', childCategoryFilter(child)))"
                      @mouseleave="onCellLeave">
                    {{ formatAmount(child.average) }}
                  </td>
                  <td class="num summary-col"
                      @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', childCategoryFilter(child)))"
                      @mouseleave="onCellLeave">
                    {{ formatAmount(child.total) }}
                  </td>
                </tr>
              </template>
            </template>

            <tr class="total-row expense-total-row">
              <td class="category-sticky"><strong>{{ monthlyAnalysis.expenseTotal.name }}</strong></td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  @mouseenter="scheduleHover($event, 'Total dépenses — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense'))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(monthlyAnalysis.expenseTotal.values[month.key]) }}</strong>
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Total dépenses — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense'))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(monthlyAnalysis.expenseTotal.average) }}</strong>
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Total dépenses — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense'))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(monthlyAnalysis.expenseTotal.total) }}</strong>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>

    <CellTransactionsPopover
      :visible="hover.visible"
      :loading="hover.loading"
      :x="hover.x"
      :y="hover.y"
      :title="hover.title"
      :rows="hover.rows"
      :total="hover.total"
      @stay="cancelHideTimer"
      @leave="scheduleHide"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { AccountsAPI, ReportsAPI, LoansAPI } from '../services/api'
import { formatAmount } from '../services/format'
import CellTransactionsPopover from '../components/CellTransactionsPopover.vue'
import Chart from 'chart.js/auto'

const accounts = ref([])
const balances = ref({})
const summary = ref({ income: 0, expenses: 0, net: 0 })
const monthLabel = ref('')
const periodicExpenses = ref({ expenses: { items: [], total: 0 }, revenues: { items: [], total: 0 } })

const forecastChart = ref(null)
const savingsChart = ref(null)
let forecastChartInstance = null
let savingsChartInstance = null
const hasLoans = ref(false)

// --- "Où va mon argent ?" + tableaux d'analyse mensuelle (ex-page Rapports,
// desormais integres au tableau de bord). Portee sur l'ensemble des comptes,
// sans filtre (le tableau de bord n'a jamais eu de selecteur de compte).
const whereMoneyData = ref([])
const whereMoneyRange = ref({ from: '', to: '' })
const monthlyAnalysis = ref(null)
const collapsedMonthlyGroups = ref({ income: {}, expense: {} })
const whereMoneyCanvas = ref(null)
let whereMoneyChart = null

/**
 * Popover unique (voir CellTransactionsPopover.vue) affichant le detail des
 * transactions derriere une cellule survolee. Un seul etat/instance partagee
 * pour tous les tableaux du tableau de bord plutot qu'un popover par cellule :
 * evite de multiplier les composants pour une interaction qui n'est jamais
 * active qu'a un seul endroit a la fois.
 */
const HOVER_DELAY_MS = 1000
const HIDE_GRACE_MS = 150
const POPOVER_WIDTH = 320

const hover = ref({ visible: false, loading: false, x: 0, y: 0, title: '', rows: [], total: 0 })
let showTimer = null
let hideTimer = null
let requestToken = 0

function positionFromEvent(event) {
  const rect = event.currentTarget.getBoundingClientRect()
  const x = Math.max(8, Math.min(rect.left, window.innerWidth - POPOVER_WIDTH - 12))
  return { x, y: rect.bottom + 6 }
}

/**
 * Declenche l'affichage du popover apres HOVER_DELAY_MS de survol continu de
 * la cellule (pour eviter l'apparition intempestive au simple passage de la
 * souris). `loadRows` doit renvoyer un tableau de { id, date, payee, amount }
 * — les memes transactions individuelles qui composent le montant affiche
 * dans la cellule.
 */
function scheduleHover(event, title, loadRows) {
  cancelHideTimer()
  clearTimeout(showTimer)
  const { x, y } = positionFromEvent(event)
  const token = ++requestToken
  showTimer = setTimeout(async () => {
    hover.value = { visible: true, loading: true, x, y, title, rows: [], total: 0 }
    try {
      const rows = await loadRows()
      if (token !== requestToken) return
      const total = rows.reduce((sum, r) => sum + Number(r.amount || 0), 0)
      hover.value = { visible: true, loading: false, x, y, title, rows, total }
    } catch {
      if (token !== requestToken) return
      hover.value = { ...hover.value, loading: false }
    }
  }, HOVER_DELAY_MS)
}

function scheduleHide() {
  cancelHideTimer()
  hideTimer = setTimeout(() => { hover.value.visible = false }, HIDE_GRACE_MS)
}

function cancelHideTimer() {
  clearTimeout(hideTimer)
  hideTimer = null
}

/** Quitte la cellule survolee : annule un affichage pas encore declenche, ou programme la fermeture. */
function onCellLeave() {
  clearTimeout(showTimer)
  requestToken++ // invalide une reponse deja en vol
  scheduleHide()
}

/** Decoupe une cle de mois "YYYY-MM-01" en plage [debut, fin] de ce mois (calcul en local, sans passer par Date(string) qui parse en UTC). */
function monthRange(monthKey) {
  const [year, month] = monthKey.split('-').map(Number)
  const lastDay = new Date(year, month, 0)
  const to = `${lastDay.getFullYear()}-${String(lastDay.getMonth() + 1).padStart(2, '0')}-${String(lastDay.getDate()).padStart(2, '0')}`
  return { from: monthKey, to }
}

function fetchCellTransactions(from, to, flow, filters = {}) {
  return ReportsAPI.cellTransactions({ from, to, flow: flow || undefined, ...filters })
}

function fetchMonthCellTransactions(monthKey, flow, filters = {}) {
  const { from, to } = monthRange(monthKey)
  return fetchCellTransactions(from, to, flow, filters)
}

/** Filtre catégorie à passer à cellTransactions pour une ligne "groupe" (racine) d'un tableau d'analyse mensuelle par catégorie. */
function groupCategoryFilter(group) {
  return group.uncategorized ? { uncategorized: true } : { rootCategoryId: group.categoryId }
}

/** Filtre catégorie pour une ligne "sous-catégorie" (toujours une feuille exacte). */
function childCategoryFilter(child) {
  return { categoryId: child.categoryId }
}

/**
 * Lignes du popover pour l'encart "Dépenses/Revenus périodiques" : les
 * données (dernière occurrence par tiers) sont déjà chargées côté client,
 * pas besoin d'appel réseau. `sign` remet le signe (les montants du groupe
 * sont stockés en valeur absolue côté backend, voir ServiceRapport#summarizePeriodicGroup).
 */
function periodicItemRow(item, sign) {
  return { id: item.transactionId, date: item.date, payee: item.payee, amount: sign * Number(item.amount) }
}
function periodicItemRows(item, sign) {
  return Promise.resolve([periodicItemRow(item, sign)])
}
function periodicGroupRows(group, sign) {
  return Promise.resolve(group.items.map(item => periodicItemRow(item, sign)))
}

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
 * les revenus/dépenses n'y sont donc pas encore complets) : point de départ
 * de la navigation mensuelle ci-dessous. Calculé une seule fois : la date
 * du jour ne change pas en cours de session.
 */
const lastCompletedMonthStart = (() => {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth() - 1, 1)
})()

/**
 * Borne haute de la navigation : on peut avancer dans le futur (les cartes
 * revenus/dépenses/tableaux affichent alors simplement "pas de données"),
 * jusqu'à l'horizon du graphique prévisionnel (12 mois). Au-delà, le solde
 * projeté ne fait que prolonger la même tendance, sans information nouvelle.
 */
const maxFutureMonthStart = new Date(lastCompletedMonthStart.getFullYear(), lastCompletedMonthStart.getMonth() + 12, 1)

/** Mois actuellement affiché par les cartes "Revenus/Dépenses/Net (mois)", navigable via les flèches du tableau de bord. */
const viewedMonthStart = ref(lastCompletedMonthStart)

const canGoToNextMonth = computed(() => viewedMonthStart.value < maxFutureMonthStart)

function monthRangeOf(monthStart) {
  const to = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0)
  return {
    from: monthStart.toISOString().slice(0, 10),
    to: to.toISOString().slice(0, 10)
  }
}

function monthLabelOf(monthStart) {
  const label = monthStart.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
  return label.charAt(0).toUpperCase() + label.slice(1)
}

function goToPreviousMonth() {
  viewedMonthStart.value = new Date(viewedMonthStart.value.getFullYear(), viewedMonthStart.value.getMonth() - 1, 1)
  loadMonthlyReports()
}

function goToNextMonth() {
  if (!canGoToNextMonth.value) return
  viewedMonthStart.value = new Date(viewedMonthStart.value.getFullYear(), viewedMonthStart.value.getMonth() + 1, 1)
  loadMonthlyReports()
}

/** Vrai quand le mois affiché est déjà le dernier mois calendaire terminé (clic sur le libellé alors sans effet). */
const isCurrentMonth = computed(() => viewedMonthStart.value.getTime() === lastCompletedMonthStart.getTime())

/** Clic sur le libellé du mois (entre les flèches) : revient directement au dernier mois calendaire terminé. */
function goToCurrentMonth() {
  if (isCurrentMonth.value) return
  viewedMonthStart.value = lastCompletedMonthStart
  loadMonthlyReports()
}

/** 12 mois pleins se terminant a monthStart inclus, utilise par "Où va mon argent ?" (aligne sur le meme mois que le reste du tableau de bord, plutot qu'une fenetre glissante figee sur aujourd'hui). */
function last12MonthsRange(monthStart) {
  const from = new Date(monthStart.getFullYear(), monthStart.getMonth() - 11, 1)
  const to = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0)
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
        // Categorie racine (elle-meme, ou celle dont ce groupe reunit les
        // sous-categories) pour retrouver au survol les transactions de tout
        // le groupe via rootCategoryId (voir groupCategoryFilter/ServiceRapport#cellTransactions).
        categoryId: row.parentId != null ? row.parentId : row.categoryId,
        uncategorized: row.parentId == null && row.categoryId == null,
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

const monthLabelByKey = computed(() =>
  new Map((monthlyAnalysis.value?.months || []).map(m => [m.key, m.label]))
)
function monthLabelFor(key) {
  return monthLabelByKey.value.get(key) || key
}

function toggleMonthlyGroup(type, key) {
  collapsedMonthlyGroups.value[type][key] = !collapsedMonthlyGroups.value[type][key]
}
function isMonthlyGroupCollapsed(type, key) {
  return !!collapsedMonthlyGroups.value[type][key]
}

async function loadDashboard() {
  accounts.value = await AccountsAPI.list()
  balances.value = await AccountsAPI.balances()

  // "Dépenses périodiques" ne depend pas du mois navigue (voir plus haut :
  // toujours la derniere occurrence connue, dans l'esprit "abonnement actif
  // ou non aujourd'hui"), donc charge une seule fois ici plutot que dans
  // loadMonthlyReports.
  periodicExpenses.value = await ReportsAPI.periodicExpenses()

  await loadMonthlyReports()
}

/**
 * Recharge tout ce qui depend du mois navigue (viewedMonthStart, voir les
 * fleches du tableau de bord) : les 3 cartes du haut, le graphique de solde,
 * l'epargne mensuelle, "Où va mon argent ?" et les deux tableaux d'analyse
 * mensuelle par categorie. Appelee au montage puis a chaque navigation
 * (goToPreviousMonth/goToNextMonth), pour que tout le tableau de bord se
 * decale ensemble sur le meme mois.
 */
async function loadMonthlyReports() {
  monthLabel.value = monthLabelOf(viewedMonthStart.value)
  const { from, to } = monthRangeOf(viewedMonthStart.value)
  // "YYYY-MM-01" : meme mois que les cartes du haut, transmis aux rapports
  // en fenetre glissante de 12 mois pour qu'ils se terminent sur ce mois.
  const referenceMonth = `${viewedMonthStart.value.getFullYear()}-${String(viewedMonthStart.value.getMonth() + 1).padStart(2, '0')}-01`

  whereMoneyRange.value = last12MonthsRange(viewedMonthStart.value)
  const loansPromise = LoansAPI.list().then(async (loansList) => {
    const schedules = await Promise.all(loansList.map(l => LoansAPI.schedule(l.id)))
    return { loansList, schedules }
  })
  const [monthSummary, forecast, savings, whereMoney, monthly, { loansList, schedules }] = await Promise.all([
    ReportsAPI.summary({ from, to }),
    ReportsAPI.balanceForecast({ referenceMonth }),
    ReportsAPI.monthlySavings({ months: 12, referenceMonth }),
    ReportsAPI.expensesByCategory(whereMoneyRange.value),
    ReportsAPI.monthlyCategoryAnalysis({ referenceMonth }),
    loansPromise
  ])
  summary.value = monthSummary
  whereMoneyData.value = whereMoney
  monthlyAnalysis.value = monthly
  hasLoans.value = loansList.length > 0

  await nextTick()
  renderForecastChart(forecast, buildLoanOwnedSeries(forecast, loansList, schedules))
  renderSavingsChart(savings)
  renderWhereMoneyChart(whereMoneyData.value)
}

/**
 * Capital des prets deja rembourse (= vous appartient), mois par mois, aligne
 * sur les points du graphique "Evolution du solde" (12 mois historiques +
 * 12 mois previsionnels). Contrairement au solde, cette serie n'a rien
 * d'incertain : l'echeancier d'un pret a taux fixe est connu a l'avance, donc
 * pas de distinction historique/previsionnel necessaire ici, juste une somme
 * sur tous les prets pour chaque mois. Retourne null si l'utilisateur n'a
 * aucun pret, pour ne pas ajouter une ligne a plat inutile au graphique.
 */
function buildLoanOwnedSeries(points, loansList, schedules) {
  if (!loansList.length) return null

  const perLoan = loansList.map((loan, i) => {
    const rows = schedules[i]?.rows || []
    const byMonth = new Map(rows.map(r => [r.date.slice(0, 7), Number(r.remainingBalance)]))
    return {
      principal: Number(loan.principal || 0),
      byMonth,
      firstMonth: rows.length ? rows[0].date.slice(0, 7) : null,
      lastMonth: rows.length ? rows[rows.length - 1].date.slice(0, 7) : null
    }
  })

  return points.map(p => {
    const monthKey = p.date.slice(0, 7)
    let owned = 0
    for (const loan of perLoan) {
      if (!loan.firstMonth || monthKey < loan.firstMonth) continue // pret pas encore commence
      if (monthKey > loan.lastMonth) { owned += loan.principal; continue } // deja solde
      owned += loan.principal - (loan.byMonth.get(monthKey) ?? 0)
    }
    return owned
  })
}

/**
 * Affiche le solde sur 12 mois historiques (deja termines) + 12 mois
 * prévisionnels a partir du mois en cours inclus, en une seule courbe (un
 * seul dataset/entree de legende) mais en pointille a partir du mois en
 * cours (voir "segment.borderDash") pour distinguer visuellement le
 * prévisionnel. Si l'utilisateur a des prets, le capital deja rembourse
 * (voir buildLoanOwnedSeries) est additionne au solde de chaque mois,
 * sinon la courbe correspond simplement au solde.
 */
function renderForecastChart(points, loanOwnedSeries) {
  const data = points.map((p, i) => Number(p.balance) + (loanOwnedSeries ? Number(loanOwnedSeries[i]) : 0))
  const firstForecastIndex = points.findIndex(p => p.type === 'previsionnel')
  // Si aucun point previsionnel n'est visible (fenetre entierement passee,
  // navigation loin en arriere), pas de jonction : toute la courbe reste
  // pleine plutot que de tomber sur un index negatif qui pointillerait tout.
  const junctionIndex = firstForecastIndex === -1 ? points.length - 1 : firstForecastIndex - 1

  const datasets = [
    {
      label: 'Solde',
      data,
      borderColor: '#5B4FCF',
      backgroundColor: 'rgba(91, 79, 207, 0.10)',
      fill: true,
      tension: 0.25,
      pointRadius: (ctx) => (ctx.dataIndex === junctionIndex ? 4 : 0),
      pointBackgroundColor: '#5B4FCF',
      borderWidth: 2,
      segment: {
        borderDash: (ctx) => (ctx.p1DataIndex > junctionIndex ? [6, 5] : undefined)
      }
    }
  ]

  if (forecastChartInstance) forecastChartInstance.destroy()
  forecastChartInstance = new Chart(forecastChart.value, {
    type: 'line',
    data: {
      labels: points.map(p => p.label),
      datasets
    },
    options: {
      responsive: true,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            // Detail solde / capital prets / total meme si une seule courbe
            // est affichee (voir "data" ci-dessus) : les deux composantes de
            // la somme restent utiles au survol.
            label: (ctx) => {
              if (!loanOwnedSeries) return `Solde : ${formatAmount(ctx.parsed.y)}`
              const balance = Number(points[ctx.dataIndex].balance)
              const loanCapital = Number(loanOwnedSeries[ctx.dataIndex])
              return [
                `Solde : ${formatAmount(balance)}`,
                `Capital des prêts : ${formatAmount(loanCapital)}`,
                `Total : ${formatAmount(balance + loanCapital)}`
              ]
            }
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

/*
 * position: fixed (pas sticky) : .page-header est un bandeau tres court, un
 * enfant en sticky ne resterait "accroche" que tant que ce bandeau est
 * encore a l'ecran, puis defilerait avec lui (teste : les fleches
 * disparaissaient des les premiers pixels de scroll). fixed les maintient
 * ancrees en haut a droite quelle que soit la position de scroll dans la
 * page. Fond + bordure + ombre opaques pour rester lisible par-dessus le
 * contenu qui defile dessous (meme traitement que le thead sticky de
 * .monthly-analysis-table plus bas).
 */
.month-nav {
  display: flex;
  align-items: center;
  gap: 10px;
  position: fixed;
  top: 24px;
  right: 40px;
  z-index: 50;
  background: var(--ink-900);
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
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

.dashboard-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr 1fr;
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
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
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

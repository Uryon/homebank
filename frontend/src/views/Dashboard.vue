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
          @click="goToNextMonth"
          title="Mois suivant"
          aria-label="Mois suivant"
        >›</button>
        <button
          class="btn secondary export-btn"
          :disabled="exporting"
          @click="exportDashboard"
          title="Exporter le tableau de bord en PDF (dossier synchronisé configuré côté backend)"
        >
          {{ exporting ? 'Export…' : 'Exporter en PDF' }}
        </button>
      </div>
    </div>

    <div class="card-grid">
      <div class="card kpi">
        <div class="stat-label">Solde total</div>
        <div class="stat-value amount" :class="totalBalanceClass">
          <Money :value="totalBalance" />
        </div>
        <div class="kpi-foot">
          <span v-if="balanceDelta" class="kpi-delta" :class="balanceDelta.tone">{{ balanceDelta.text }}</span>
          <Sparkline :values="balanceSeries" color="#E2A76B" />
        </div>
      </div>
      <div class="card kpi">
        <div class="stat-label">
          Revenus ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount positive" :class="{ 'forecast-cell': summary.forecast }"><Money :value="Number(summary.income)" /></div>
        <div class="kpi-foot">
          <span v-if="incomeDelta" class="kpi-delta" :class="incomeDelta.tone">{{ incomeDelta.text }}</span>
          <Sparkline :values="kpiSeries.income" :color="sparkColor(incomeDelta)" />
        </div>
      </div>
      <div class="card kpi">
        <div class="stat-label">
          Dépenses ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount negative" :class="{ 'forecast-cell': summary.forecast }"><Money :value="Number(summary.expenses)" /></div>
        <div class="kpi-foot">
          <span v-if="expensesDelta" class="kpi-delta" :class="expensesDelta.tone">{{ expensesDelta.text }}</span>
          <Sparkline :values="kpiSeries.expenses" :color="sparkColor(expensesDelta)" />
        </div>
      </div>
      <div class="card kpi">
        <div class="stat-label">
          Net ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount" :class="[summary.net >= 0 ? 'positive' : 'negative', { 'forecast-cell': summary.forecast }]">
          <Money :value="Number(summary.net)" />
        </div>
        <div class="kpi-foot">
          <span v-if="netDelta" class="kpi-delta" :class="netDelta.tone">{{ netDelta.text }}</span>
          <Sparkline :values="kpiSeries.net" :color="sparkColor(netDelta)" />
        </div>
      </div>
    </div>

    <!-- Liste des comptes : reservee a l'export PDF (masquee a l'ecran, ou la
         sidebar l'affiche deja — la sidebar est justement retiree du PDF, voir
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

    <div class="card forecast-card chart-card" style="margin-bottom: 20px;">
      <div class="chart-header">
        <div class="stat-label" style="margin-bottom: 0;">
          Évolution du solde — historique, présent &amp; prévisionnel
        </div>
        <button
          v-if="hasLoans"
          type="button"
          class="chart-mode-toggle"
          :class="{ 'is-separate': chartDisplayMode === 'separate' }"
          @click="toggleChartMode"
          :title="chartDisplayMode === 'separate' ? 'Afficher la courbe additionnée' : 'Afficher les courbes séparées'"
        >
          <span class="toggle-track"><span class="toggle-thumb"></span></span>
          <span class="toggle-label">
            {{ chartDisplayMode === 'separate' ? 'Courbes séparées' : 'Courbe additionnée' }}
          </span>
        </button>
      </div>
      <canvas ref="forecastChart" height="90"></canvas>
      <p class="chart-note">
        Le prévisionnel est estimé à partir de la variation moyenne du solde observée sur les 12 derniers mois,
        soit <strong :class="monthlyTrend >= 0 ? 'amount positive' : 'amount negative'">{{ formatAmount(monthlyTrend) }}</strong>
        ajoutés au solde chaque mois sur la partie prévisionnelle.
        <template v-if="hasLoans">
          La courbe inclut aussi le capital déjà remboursé sur l'ensemble de vos prêts (connu à l'avance,
          indépendant de la projection de solde).
        </template>
      </p>
    </div>

    <div class="top-row-grid">
      <div class="card compact-card savings-card chart-card">
        <div class="card-title" style="margin-bottom: 8px;">Épargne mensuelle (12 derniers mois)</div>
        <div class="savings-chart-wrap">
          <canvas ref="savingsChart"></canvas>
        </div>
        <p class="chart-note">Épargne = revenus du mois − dépenses du mois.</p>
      </div>

      <div class="periodic-grid">
        <div class="card compact-card">
          <div class="card-title" style="margin-bottom: 10px;">Dépenses périodiques</div>
          <div v-if="periodicExpenses.expenses.items.length">
            <div class="periodic-table-wrap">
              <table class="plain-table">
                <thead class="sr-only">
                  <tr><th>Tiers</th><th>Montant</th></tr>
                </thead>
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
            </div>
            <div class="periodic-total">
              <span>Total</span>
              <span class="amount negative"
                    @mouseenter="scheduleHover($event, 'Dépenses périodiques', () => periodicGroupRows(periodicExpenses.expenses, -1))"
                    @mouseleave="onCellLeave">
                {{ formatAmount(periodicExpenses.expenses.total) }}
              </span>
            </div>
          </div>
          <div v-else class="empty-state compact-empty-state">
            Aucune dépense périodique marquée. Coche "Périodique" sur une transaction récurrente
            (loyer, abonnement...) dans l'onglet Transactions, ou regarde l'onglet "Périodiques"
            pour des suggestions automatiques.
          </div>
        </div>

        <div class="card compact-card">
          <div class="card-title" style="margin-bottom: 10px;">Revenus périodiques</div>
          <div v-if="periodicExpenses.revenues.items.length">
            <div class="periodic-table-wrap">
              <table class="plain-table">
                <thead class="sr-only">
                  <tr><th>Tiers</th><th>Montant</th></tr>
                </thead>
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
            </div>
            <div class="periodic-total">
              <span>Total</span>
              <span class="amount positive"
                    @mouseenter="scheduleHover($event, 'Revenus périodiques', () => periodicGroupRows(periodicExpenses.revenues, 1))"
                    @mouseleave="onCellLeave">
                {{ formatAmount(periodicExpenses.revenues.total) }}
              </span>
            </div>
          </div>
          <div v-else class="empty-state compact-empty-state">
            Aucun revenu périodique marqué. Coche "Périodique" sur une transaction récurrente
            (salaire, remboursement régulier...) dans l'onglet Transactions.
          </div>
        </div>
      </div>
    </div>

    <div class="card compact-card where-money-card" style="margin-bottom: 28px;">
      <div class="card-title" style="margin-bottom: 4px;">Où va mon argent ?</div>
      <div style="color: var(--text-muted); font-size: 12px; margin-bottom: 12px;">
        Répartition des dépenses sur les 12 derniers mois (les virements entre comptes ne sont pas comptés).
      </div>
      <div v-if="whereMoneyData.length" class="where-money-layout">
        <div class="donut-wrap">
          <canvas ref="whereMoneyCanvas" width="150" height="150"></canvas>
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
                  {{ iconPrefix(row.categoryId) }}{{ row.category }}
                </td>
                <td class="num amount negative"
                    @mouseenter="scheduleHover($event, row.category + ' — 12 derniers mois', () => fetchCellTransactions(whereMoneyRange.from, whereMoneyRange.to, 'expense', row.uncategorized ? { uncategorized: true } : { categoryId: row.categoryId }))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(row.total) }}
                </td>
                <td class="num where-pct">{{ formatShare(row.percentage) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>Total</td>
                <td class="num amount negative">{{ formatAmount(whereMoneyTotal) }}</td>
                <td class="num where-pct">{{ formatShare(100) }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>
      <div v-else class="empty-state">Aucune dépense sur les 12 derniers mois.</div>
    </div>

    <div class="card monthly-analysis-card monthly-summary-card">
      <div class="card-title" style="margin-bottom: 4px;">Résumé mensuel — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Revenus, dépenses et solde par mois. Les 12 derniers mois entièrement terminés sont affichés<span v-if="hasForecastMonths">, complétés par un prévisionnel <em>(en italique)</em> sur les mois pas encore terminés</span>.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table v-grid-nav class="monthly-analysis-table" aria-describedby="grid-nav-hint">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  :title="month.forecast ? 'Mois pas encore termine : previsionnel (moyenne des mois precedents)' : null">
                {{ month.label }}
              </th>
              <th class="num summary-col">Moyenne</th>
              <th class="num summary-col">Total</th>
            </tr>
          </thead>

          <tbody>
            <tr>
              <td class="category-sticky summary-label">Revenus</td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-income-' + month.key" class="num"
                  :class="[anomalyClass(month.income, summaryRowValues.income), { 'forecast-cell': month.forecast }]"
                  @mouseenter="scheduleMonthHover($event, 'Revenus — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income'), month.forecast)"
                  @mouseleave="onCellLeave">
                {{ formatAmount(month.income) }}
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Revenus — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income'))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(monthlyAnalysis.incomeTotal.average) }}
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
                  :class="[anomalyClass(month.expenses, summaryRowValues.expenses), { 'forecast-cell': month.forecast }]"
                  @mouseenter="scheduleMonthHover($event, 'Dépenses — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense'), month.forecast)"
                  @mouseleave="onCellLeave">
                {{ formatAmount(month.expenses) }}
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Dépenses — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense'))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(monthlyAnalysis.expenseTotal.average) }}
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
                  class="num"
                  :class="[Number(month.net) >= 0 ? 'positive' : 'negative', anomalyClass(month.net, summaryRowValues.net), { 'forecast-cell': month.forecast }]"
                  @mouseenter="scheduleMonthHover($event, 'Solde — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, null), month.forecast)"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(month.net) }}</strong>
              </td>
              <td class="num summary-col"
                  @mouseenter="scheduleHover($event, 'Solde — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, null))"
                  @mouseleave="onCellLeave">
                <strong>{{ formatAmount(Number(monthlyAnalysis.incomeTotal.average) - Number(monthlyAnalysis.expenseTotal.average)) }}</strong>
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
      <div class="card-title" style="margin-bottom: 4px;">Dépenses par catégorie — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Évolution mensuelle par catégorie et sous-catégorie. Les 12 derniers mois entièrement terminés sont affichés<span v-if="hasForecastMonths">, complétés par un prévisionnel <em>(en italique)</em> sur les mois pas encore terminés</span>.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table v-grid-nav class="monthly-analysis-table" aria-describedby="grid-nav-hint">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  :title="month.forecast ? 'Mois pas encore termine : previsionnel (moyenne des mois precedents)' : null">
                {{ month.label }}
              </th>
              <th class="num summary-col">Moyenne</th>
              <th class="num summary-col">Total</th>
            </tr>
          </thead>

          <tbody v-for="(group, i) in expenseGroups" :key="'expense-' + group.key" :class="{ 'category-group-alt': i % 2 === 1 }">
            <tr class="category-row">
              <td class="category-sticky category-name">
                <button v-if="group.children.length" class="collapse-button"
                        @click="toggleMonthlyGroup('expense', group.key)"
                        :title="isMonthlyGroupCollapsed('expense', group.key) ? 'Développer' : 'Replier'">
                  {{ isMonthlyGroupCollapsed('expense', group.key) ? '▸' : '▾' }}
                </button>
                <span v-else class="collapse-placeholder"></span>
                <strong>{{ iconPrefix(group.categoryId) }}{{ group.name }}</strong>
              </td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="[{ 'zero-value': !Number(group.values[month.key]) }, anomalyClass(group.values[month.key], Object.values(group.values)), { 'forecast-cell': month.forecast }]"
                  @mouseenter="scheduleMonthHover($event, group.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense', groupCategoryFilter(group)), month.forecast)"
                  @mouseleave="onCellLeave">
                {{ formatAmount(group.values[month.key]) }}
              </td>
              <td class="num summary-col" :class="{ 'zero-value': !Number(group.average) }"
                  @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', groupCategoryFilter(group)))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(group.average) }}
              </td>
              <td class="num summary-col" :class="{ 'zero-value': !Number(group.total) }"
                  @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', groupCategoryFilter(group)))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(group.total) }}
              </td>
            </tr>

            <template v-if="!isMonthlyGroupCollapsed('expense', group.key)">
              <tr v-for="child in group.children" :key="'expense-child-' + child.key"
                  class="subcategory-row">
                <td class="category-sticky category-name subcategory-name">
                  └ {{ iconPrefix(child.categoryId) }}{{ child.name }}
                </td>
                <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                    :class="[{ 'zero-value': !Number(child.values[month.key]) }, anomalyClass(child.values[month.key], Object.values(child.values)), { 'forecast-cell': month.forecast }]"
                    @mouseenter="scheduleMonthHover($event, child.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense', childCategoryFilter(child)), month.forecast)"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(child.values[month.key]) }}
                </td>
                <td class="num summary-col" :class="{ 'zero-value': !Number(child.average) }"
                    @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', childCategoryFilter(child)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(child.average) }}
                </td>
                <td class="num summary-col" :class="{ 'zero-value': !Number(child.total) }"
                    @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'expense', childCategoryFilter(child)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(child.total) }}
                </td>
              </tr>
            </template>
          </tbody>

          <tbody>
            <tr class="total-row expense-total-row">
              <td class="category-sticky"><strong>{{ monthlyAnalysis.expenseTotal.name }}</strong></td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  @mouseenter="scheduleMonthHover($event, 'Total dépenses — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'expense'), month.forecast)"
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

    <div class="card monthly-analysis-card">
      <div class="card-title" style="margin-bottom: 4px;">Revenus par catégorie — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Évolution mensuelle par catégorie et sous-catégorie. Les 12 derniers mois entièrement terminés sont affichés<span v-if="hasForecastMonths">, complétés par un prévisionnel <em>(en italique)</em> sur les mois pas encore terminés</span>.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table v-grid-nav class="monthly-analysis-table" aria-describedby="grid-nav-hint">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  :title="month.forecast ? 'Mois pas encore termine : previsionnel (moyenne des mois precedents)' : null">
                {{ month.label }}
              </th>
              <th class="num summary-col">Moyenne</th>
              <th class="num summary-col">Total</th>
            </tr>
          </thead>

          <tbody v-for="(group, i) in incomeGroups" :key="'income-' + group.key" :class="{ 'category-group-alt': i % 2 === 1 }">
            <tr class="category-row">
              <td class="category-sticky category-name">
                <button v-if="group.children.length" class="collapse-button"
                        @click="toggleMonthlyGroup('income', group.key)"
                        :title="isMonthlyGroupCollapsed('income', group.key) ? 'Développer' : 'Replier'">
                  {{ isMonthlyGroupCollapsed('income', group.key) ? '▸' : '▾' }}
                </button>
                <span v-else class="collapse-placeholder"></span>
                <strong>{{ iconPrefix(group.categoryId) }}{{ group.name }}</strong>
              </td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="[{ 'zero-value': !Number(group.values[month.key]) }, anomalyClass(group.values[month.key], Object.values(group.values)), { 'forecast-cell': month.forecast }]"
                  @mouseenter="scheduleMonthHover($event, group.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income', groupCategoryFilter(group)), month.forecast)"
                  @mouseleave="onCellLeave">
                {{ formatAmount(group.values[month.key]) }}
              </td>
              <td class="num summary-col" :class="{ 'zero-value': !Number(group.average) }"
                  @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', groupCategoryFilter(group)))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(group.average) }}
              </td>
              <td class="num summary-col" :class="{ 'zero-value': !Number(group.total) }"
                  @mouseenter="scheduleHover($event, group.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', groupCategoryFilter(group)))"
                  @mouseleave="onCellLeave">
                {{ formatAmount(group.total) }}
              </td>
            </tr>

            <template v-if="!isMonthlyGroupCollapsed('income', group.key)">
              <tr v-for="child in group.children" :key="'income-child-' + child.key"
                  class="subcategory-row">
                <td class="category-sticky category-name subcategory-name">
                  └ {{ iconPrefix(child.categoryId) }}{{ child.name }}
                </td>
                <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                    :class="[{ 'zero-value': !Number(child.values[month.key]) }, anomalyClass(child.values[month.key], Object.values(child.values)), { 'forecast-cell': month.forecast }]"
                    @mouseenter="scheduleMonthHover($event, child.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income', childCategoryFilter(child)), month.forecast)"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(child.values[month.key]) }}
                </td>
                <td class="num summary-col" :class="{ 'zero-value': !Number(child.average) }"
                    @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', childCategoryFilter(child)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(child.average) }}
                </td>
                <td class="num summary-col" :class="{ 'zero-value': !Number(child.total) }"
                    @mouseenter="scheduleHover($event, child.name + ' — 12 derniers mois', () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, 'income', childCategoryFilter(child)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(child.total) }}
                </td>
              </tr>
            </template>
          </tbody>

          <tbody>
            <tr class="total-row income-total-row">
              <td class="category-sticky"><strong>{{ monthlyAnalysis.incomeTotal.name }}</strong></td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  @mouseenter="scheduleMonthHover($event, 'Total revenus — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, 'income'), month.forecast)"
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

    <p id="grid-nav-hint" class="sr-only">
      Flèches pour passer d'un montant à l'autre ; le détail des transactions s'affiche pour le montant sélectionné, Échap pour le fermer.
    </p>

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
import { ref, onMounted, onBeforeUnmount, computed, nextTick, watch } from 'vue'
import { effectiveTheme } from '../services/theme'
import { areaGradient, axisOptions, readChartColors, tooltipOptions } from '../services/chartTheme'
import { ReportsAPI, LoansAPI, ExportAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { formatAmount } from '../services/format'
import { accountBalanceRows, accountCountLabel, amountToneClass, computeNetWorth, sortAccountsForDisplay } from '../services/accounts'
import { alertApiError } from '../services/errors'
import CellTransactionsPopover from '../components/CellTransactionsPopover.vue'
import Chart from 'chart.js/auto'
import Money from '../components/Money.vue'
import Sparkline from '../components/Sparkline.vue'
import {
  analysisGroup,
  anomalyClass,
  balanceChangeOverPeriod,
  buildLoanOwnedSeries,
  last12MonthsRange,
  mergeRowsByPayee,
  monthDelta,
  monthRange
} from '../services/dashboardAnalysis'

// Categories issues du cache partage (voir App.vue / dataStore.js), uniquement
// pour resoudre l'icone (emoji) a afficher devant chaque nom de categorie
// dans les tableaux ci-dessous (voir iconPrefix) — le reste du tableau de
// bord ne consomme que les rapports agreges de ReportsAPI, jamais les
// categories elles-memes.
const categoriesById = computed(() => new Map(dataStore.state.categories.map(c => [c.id, c])))

/** Prefixe un nom de categorie avec son icone (emoji) resolue via son id, suivie d'un espace — chaine vide si aucune icone ou id inconnu. */
function iconPrefix(categoryId) {
  const icon = categoriesById.value.get(categoryId)?.icon
  return icon ? `${icon} ` : ''
}

// --- Chiffres clés : séries pour les courbes miniatures + variations ---
const balanceSeries = ref([])
const kpiSeries = computed(() => {
  const rows = monthlyAnalysis.value?.summary || []
  return {
    income: rows.map(m => Number(m.income)),
    expenses: rows.map(m => Number(m.expenses)),
    net: rows.map(m => Number(m.net))
  }
})
const shareFormatter = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 1 })

/** Part en pourcentage reçue du backend (ex : 24.6) affichée à la française : « 24,6 % ». */
function formatShare(percentage) {
  return shareFormatter.format(Number(percentage || 0) / 100)
}

/** La courbe prend la couleur de la variation (bonne nouvelle = vert, mauvaise = rouge), pas celle de la nature du chiffre : une baisse des dépenses doit se lire comme positive. */
function sparkColor(delta) {
  if (!delta) return 'var(--text-faint)'
  return delta.tone === 'good' ? 'var(--chart-positive)' : 'var(--chart-negative)'
}
const incomeDelta = computed(() => monthDelta(kpiSeries.value.income, true))
const expensesDelta = computed(() => monthDelta(kpiSeries.value.expenses, false))
const netDelta = computed(() => monthDelta(kpiSeries.value.net, true))
const balanceDelta = computed(() => balanceChangeOverPeriod(balanceSeries.value, '12 mois'))

// Comptes et soldes lus dans le cache partagé (même source que le
// « Patrimoine net » de la barre latérale, rafraîchi après chaque
// modification) plutôt que rechargés à chaque visite du tableau de bord.
const accounts = computed(() => dataStore.state.accounts)
const balances = computed(() => dataStore.state.balances)
const summary = ref({ income: 0, expenses: 0, net: 0 })
const monthLabel = ref('')
const periodicExpenses = ref({ expenses: { items: [], total: 0 }, revenues: { items: [], total: 0 } })

const forecastChart = ref(null)
const savingsChart = ref(null)
let forecastChartInstance = null
let savingsChartInstance = null
const hasLoans = ref(false)

/** Variation mensuelle moyenne appliquee a la partie previsionnelle de la courbe de solde (voir ServiceRapport#balanceForecast, champ "monthlyTrend"). */
const monthlyTrend = ref(0)

const exporting = ref(false)

/** Export manuel du tableau de bord en PDF (le même endpoint est appelé automatiquement à la fermeture de l'appli par HomeBank.vbs). */
async function exportDashboard() {
  exporting.value = true
  try {
    await ExportAPI.dashboardPdf()
  } catch (e) {
    alertApiError('Export PDF échoué', e)
  } finally {
    exporting.value = false
  }
}

/**
 * Mode d'affichage du graphique "Évolution du solde" : 'combined' additionne
 * solde et capital des prêts en une seule courbe (comportement historique),
 * 'separate' affiche les deux courbes independamment. Bascule via le bouton
 * toggleChartMode, sans recharger les donnees (voir lastForecastPoints/
 * lastLoanOwnedSeries ci-dessous).
 */
const chartDisplayMode = ref('combined')
let lastForecastPoints = []
let lastLoanOwnedSeries = null
let lastSavingsPoints = []

/**
 * Les couleurs des graphiques sont lues dans les variables CSS au moment du
 * dessin (voir services/chartTheme.js) : on redessine donc les graphiques
 * déjà affichés au changement de thème, y compris quand le système bascule.
 */
watch(effectiveTheme, async () => {
  if (!lastForecastPoints.length) return
  await nextTick()
  renderForecastChart(lastForecastPoints, lastLoanOwnedSeries)
  renderSavingsChart(lastSavingsPoints)
  renderWhereMoneyChart(whereMoneyData.value)
})

function toggleChartMode() {
  chartDisplayMode.value = chartDisplayMode.value === 'combined' ? 'separate' : 'combined'
  renderForecastChart(lastForecastPoints, lastLoanOwnedSeries)
}

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
      const mergedRows = mergeRowsByPayee(rows)
      // Tri par montant decroissant en valeur ABSOLUE (pas juste par valeur
      // signee) : sur une liste d'un seul sens (que des depenses, ex. "Alimentation :
      // Courses"), trier par valeur signee decroissante mettait la plus PETITE
      // depense en premier (-1,15€ avant -1,25€, puisque -1,15 > -1,25) — l'inverse
      // de "decroissant" au sens ou l'utilisateur l'entend (la plus grosse
      // transaction en premier, quel que soit le sens).
      const sortedRows = mergedRows.sort((a, b) => Math.abs(Number(b.amount)) - Math.abs(Number(a.amount)))
      hover.value = { visible: true, loading: false, x, y, title, rows: sortedRows, total }
    } catch {
      if (token !== requestToken) return
      hover.value = { ...hover.value, loading: false }
    }
  }, HOVER_DELAY_MS)
}

/**
 * Comme scheduleHover, mais sans effet sur une case previsionnelle (mois pas
 * encore termine, badge "Prevu" : voir ServiceRapport#completeAnalysisRow).
 * Son montant est une moyenne estimee sur les mois passes, jamais une somme
 * de transactions reelles de ce mois-la (qui n'a pas encore eu lieu) — un
 * survol y interrogerait cellTransactions pour un mois quasi vide (au mieux
 * quelques tranches de transaction annualisee qui ne totalisent pas la
 * moyenne affichee), un detail trompeur plutot qu'absent.
 */
function scheduleMonthHover(event, title, loadRows, isForecast) {
  if (isForecast) return
  scheduleHover(event, title, loadRows)
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

// Palette catégorielle (8 teintes distinguables, y compris pour les daltoniens),
// volontairement sans vert/rouge « sémantiques » : une catégorie n'est ni un gain ni une perte.
const PALETTE = ['#3E63DD', '#F76B15', '#12A594', '#D6409F', '#8E4EC6', '#00A2C7', '#E5A400', '#7B8794']
const SMALL_TICK_FONT = { family: 'Inter, sans-serif', size: 10 }
const fmtTick = new Intl.NumberFormat('fr-FR')

// Mêmes calculs partagés que la sidebar (services/accounts.js). Tous les
// comptes, archivés compris (en fin de liste) : la liste imprimée somme ainsi
// exactement au « Solde total » affiché.
const totalBalance = computed(() => computeNetWorth(accounts.value, balances.value))
const totalBalanceClass = computed(() => amountToneClass(totalBalance.value))
const printedAccounts = computed(() => accountBalanceRows(sortAccountsForDisplay(accounts.value), balances.value))
const printedAccountsCountLabel = computed(() => accountCountLabel(printedAccounts.value.length))

// Instance creee une seule fois, comme amountFormatter dans services/format.js :
// construire un Intl.NumberFormat a chaque appel est couteux.
const compactAmountFormatter = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 })

/** Version compacte pour le total au centre du donut (ex : "3 780 €" au lieu de "3 780,00 €"). */
function formatAmountCompact(v) {
  return compactAmountFormatter.format(v || 0)
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

/** Mois actuellement affiché par les cartes "Revenus/Dépenses/Net (mois)", navigable via les flèches du tableau de bord — sans limite haute (les cartes/tableaux affichent alors le prévisionnel, voir ServiceRapport#completeAnalysisRow, et le solde projeté prolonge la tendance, voir renderForecastChart / ServiceRapport#balanceForecast). */
const viewedMonthStart = ref(lastCompletedMonthStart)

function monthLabelOf(monthStart) {
  const label = monthStart.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
  return label.charAt(0).toUpperCase() + label.slice(1)
}

function goToPreviousMonth() {
  viewedMonthStart.value = new Date(viewedMonthStart.value.getFullYear(), viewedMonthStart.value.getMonth() - 1, 1)
  loadMonthlyReports()
}

function goToNextMonth() {
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

const whereMoneyTotal = computed(() =>
  whereMoneyData.value.reduce((sum, row) => sum + Number(row.total || 0), 0)
)

const analysisMonths = computed(() => monthlyAnalysis.value?.months || [])
const incomeGroups = computed(() => analysisGroup(monthlyAnalysis.value?.income || [], analysisMonths.value))
const expenseGroups = computed(() => analysisGroup(monthlyAnalysis.value?.expenses || [], analysisMonths.value))

const monthLabelByKey = computed(() =>
  new Map((monthlyAnalysis.value?.months || []).map(m => [m.key, m.label]))
)
function monthLabelFor(key) {
  return monthLabelByKey.value.get(key) || key
}

/** Vrai si la fenêtre de 12 mois affichée contient au moins un mois prévisionnel (voir monthlyAnalysis.months[].forecast) : en navigant vers le futur avec les flèches, pas seulement en vue par défaut. */
const hasForecastMonths = computed(() => (monthlyAnalysis.value?.months || []).some(m => m.forecast))

/** Valeurs mensuelles des lignes Revenus/Depenses/Solde du tableau "Resume mensuel", pour anomalyClass. */
const summaryRowValues = computed(() => {
  const summary = monthlyAnalysis.value?.summary || []
  return {
    income: summary.map(m => m.income),
    expenses: summary.map(m => m.expenses),
    net: summary.map(m => m.net)
  }
})

function toggleMonthlyGroup(type, key) {
  collapsedMonthlyGroups.value[type][key] = !collapsedMonthlyGroups.value[type][key]
}
function isMonthlyGroupCollapsed(type, key) {
  return !!collapsedMonthlyGroups.value[type][key]
}

async function loadDashboard() {
  // "Dépenses périodiques" ne depend pas du mois navigue (voir plus haut :
  // toujours la derniere occurrence connue, dans l'esprit "abonnement actif
  // ou non aujourd'hui"), donc charge une seule fois ici plutot que dans
  // loadMonthlyReports. Appels independants : lances en parallele.
  try {
    const [periodic] = await Promise.all([
      ReportsAPI.periodicExpenses(),
      dataStore.ensureLoaded()
    ])
    periodicExpenses.value = periodic
  } catch (e) {
    alertApiError('Erreur lors du chargement du tableau de bord', e)
  }

  await loadMonthlyReports()
}

// Jeton du dernier chargement mensuel lance : en cliquant plusieurs fois
// vite sur les fleches, une reponse plus ancienne arrivee en dernier
// affichait sinon les chiffres d'un autre mois que celui du libelle.
let monthlyReportsToken = 0

/**
 * Recharge tout ce qui depend du mois navigue (viewedMonthStart, voir les
 * fleches du tableau de bord) : les 3 cartes du haut, le graphique de solde,
 * l'epargne mensuelle, "Où va mon argent ?" et les deux tableaux d'analyse
 * mensuelle par categorie. Appelee au montage puis a chaque navigation
 * (goToPreviousMonth/goToNextMonth), pour que tout le tableau de bord se
 * decale ensemble sur le meme mois.
 */
async function loadMonthlyReports() {
  const token = ++monthlyReportsToken
  monthLabel.value = monthLabelOf(viewedMonthStart.value)
  // "YYYY-MM-01" : meme mois que les cartes du haut, transmis aux rapports
  // en fenetre glissante de 12 mois pour qu'ils se terminent sur ce mois.
  const referenceMonth = `${viewedMonthStart.value.getFullYear()}-${String(viewedMonthStart.value.getMonth() + 1).padStart(2, '0')}-01`

  whereMoneyRange.value = last12MonthsRange(viewedMonthStart.value)
  const loansPromise = LoansAPI.list().then(async (loansList) => {
    const schedules = await Promise.all(loansList.map(l => LoansAPI.schedule(l.id)))
    return { loansList, schedules }
  })
  let reports
  try {
    reports = await Promise.all([
      ReportsAPI.balanceForecast({ referenceMonth }),
      ReportsAPI.monthlySavings({ months: 12, referenceMonth }),
      ReportsAPI.expensesByCategory(whereMoneyRange.value),
      ReportsAPI.monthlyCategoryAnalysis({ referenceMonth }),
      loansPromise
    ])
  } catch (e) {
    if (token === monthlyReportsToken) alertApiError('Erreur lors du chargement des rapports', e)
    return
  }
  if (token !== monthlyReportsToken) return
  const [forecast, savings, whereMoney, monthly, { loansList, schedules }] = reports
  // Les 3 cartes du haut reprennent le dernier mois de monthlyAnalysis.summary
  // (toujours exactement le mois affiche, voir referenceMonth ci-dessus) plutot
  // qu'un appel separe a /reports/summary : ce mois-la est deja previsionnel
  // (moyenne des mois passes, voir ServiceRapport#completeAnalysisRow) quand on
  // navigue dans le futur, au lieu d'un total reel forcement a 0/quasi-0.
  summary.value = monthly.summary[monthly.summary.length - 1] || { income: 0, expenses: 0, net: 0 }
  whereMoneyData.value = whereMoney
  monthlyAnalysis.value = monthly
  hasLoans.value = loansList.length > 0
  monthlyTrend.value = Number(forecast.monthlyTrend)
  balanceSeries.value = forecast.points.filter(p => p.type !== 'previsionnel').map(p => Number(p.balance))

  await nextTick()
  if (token !== monthlyReportsToken) return
  renderForecastChart(forecast.points, buildLoanOwnedSeries(forecast.points, loansList, schedules))
  renderSavingsChart(savings)
  renderWhereMoneyChart(whereMoneyData.value)
}

/**
 * Couleur commune (--chart-forecast) a toutes les portions "previsionnel" des courbes du
 * tableau de bord : gris neutre, plutot qu'une teinte plus claire de la
 * couleur "reelle" de chaque serie (essaye d'abord, voir historique du
 * fichier) — a l'echelle fine d'une ligne de graphique, un simple
 * eclaircissement restait trop proche du reel pour se voir sans zoomer,
 * meme pousse. Un gris franc, lui, se distingue d'un coup d'œil quelle que
 * soit la couleur "reelle" de la serie (indigo du solde, ambre du capital
 * des prets...), au prix d'une petite concession en mode "courbes
 * separees" : les deux previsionnels (solde et capital des prets) partagent
 * alors ce meme gris plutot que de garder chacun leur teinte — mais restent
 * distinguables par leur trajectoire, et surtout par le trait plein de
 * meme couleur juste avant, dont ils prolongent directement la courbe.
 */


/**
 * Construit, pour une serie de valeurs, la paire de datasets Chart.js
 * "reel" (trait plein, couleur pleine) / "previsionnel" (pointille, couleur
 * eclaircie) — meme principe que le graphique "Evolution du capital restant
 * du" de la page Prets (voir Loans.vue#renderChart) : la distinction
 * reel/previsionnel passe par une couleur ET un trait differents, pas
 * seulement le trait comme avant ici (segment.borderDash sur un dataset
 * unique). Les deux tableaux de donnees se chevauchent EXACTEMENT sur
 * junctionIndex (meme valeur des deux cotes, le reste a null + spanGaps:
 * false) pour que les deux segments se rejoignent visuellement sans coupure.
 * `future.isForecastVariant` sert a exclure ce second dataset de la legende
 * (voir plugins.legend.labels.filter plus bas) : une seule entree par serie
 * logique, pas une par dataset Chart.js.
 */
function pastFutureDatasets(label, data, junctionIndex, color, futureColor) {
  const pastData = data.map((v, i) => (i <= junctionIndex ? v : null))
  const futureData = data.map((v, i) => (i >= junctionIndex ? v : null))
  const pointRadiusAtJunction = (ctx) => (ctx.dataIndex === junctionIndex ? 4 : 0)

  return [
    {
      label,
      data: pastData,
      borderColor: color,
      backgroundColor: areaGradient(color, 0.2),
      fill: true,
      tension: 0.25,
      pointRadius: pointRadiusAtJunction,
      pointBackgroundColor: color,
      borderWidth: 2,
      spanGaps: false
    },
    {
      label: `${label} (prévisionnel)`,
      isForecastVariant: true,
      data: futureData,
      borderColor: futureColor,
      backgroundColor: areaGradient(futureColor, 0.14),
      borderDash: [6, 5],
      fill: true,
      tension: 0.25,
      pointRadius: pointRadiusAtJunction,
      pointBackgroundColor: futureColor,
      borderWidth: 2,
      spanGaps: false
    }
  ]
}

/**
 * Affiche le solde sur 12 mois historiques (deja termines) + 12 mois
 * prévisionnels a partir du mois en cours inclus, en couleur eclaircie et
 * pointille a partir du mois en cours (voir pastFutureDatasets) pour
 * distinguer visuellement le prévisionnel du reel. Si l'utilisateur a des
 * prets, deux modes sont possibles (voir chartDisplayMode, bascule via
 * toggleChartMode) : 'combined' additionne le capital deja rembourse (voir
 * buildLoanOwnedSeries) au solde en une seule courbe (comportement
 * historique), 'separate' affiche les deux courbes independamment. Sans
 * pret, la courbe correspond simplement au solde quel que soit le mode.
 */
function renderForecastChart(points, loanOwnedSeries) {
  lastForecastPoints = points
  lastLoanOwnedSeries = loanOwnedSeries

  const firstForecastIndex = points.findIndex(p => p.type === 'previsionnel')
  // Si aucun point previsionnel n'est visible (fenetre entierement passee,
  // navigation loin en arriere), pas de jonction : toute la courbe reste
  // pleine plutot que de tomber sur un index negatif qui pointillerait tout.
  const junctionIndex = firstForecastIndex === -1 ? points.length - 1 : firstForecastIndex - 1

  const showSeparate = !!loanOwnedSeries && chartDisplayMode.value === 'separate'
  const colors = readChartColors()

  const datasets = showSeparate
    ? [
        ...pastFutureDatasets('Solde', points.map(p => Number(p.balance)), junctionIndex, colors.accent, colors.forecast),
        ...pastFutureDatasets('Capital des prêts', loanOwnedSeries.map(Number), junctionIndex, colors.secondary, colors.forecast)
      ]
    : pastFutureDatasets(
        'Solde',
        points.map((p, i) => Number(p.balance) + (loanOwnedSeries ? Number(loanOwnedSeries[i]) : 0)),
        junctionIndex,
        colors.accent,
        colors.forecast
      )

  const tooltipLabel = showSeparate
    ? (ctx) => `${ctx.dataset.label} : ${formatAmount(ctx.parsed.y)}`
    : (ctx) => {
        if (!loanOwnedSeries) return `Solde : ${formatAmount(ctx.parsed.y)}`
        // Detail solde / capital prets / total meme si une seule courbe est
        // affichee (voir "data" ci-dessus) : les deux composantes de la somme
        // restent utiles au survol.
        const balance = Number(points[ctx.dataIndex].balance)
        const loanCapital = Number(loanOwnedSeries[ctx.dataIndex])
        return [
          `Solde : ${formatAmount(balance)}`,
          `Capital des prêts : ${formatAmount(loanCapital)}`,
          `Total : ${formatAmount(balance + loanCapital)}`
        ]
      }

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
        legend: {
          display: showSeparate,
          // Une seule entree de legende par serie logique (Solde, Capital
          // des prets), pas une par dataset Chart.js : le dataset
          // "previsionnel" (voir pastFutureDatasets) est exclu.
          labels: {
            usePointStyle: true,
            pointStyle: 'circle',
            boxWidth: 8,
            boxHeight: 8,
            padding: 18,
            color: colors.legend,
            font: { family: 'Inter, sans-serif', size: 11, weight: '600' },
            filter: (legendItem, data) => !data.datasets[legendItem.datasetIndex]?.isForecastVariant
          }
        },
        tooltip: tooltipOptions(colors, {
          displayColors: true,
          // Au point de jonction, le dataset "previsionnel" porte
          // volontairement la meme valeur que le dataset "reel" (voir
          // pastFutureDatasets, pour que les deux segments se rejoignent
          // visuellement) : sans ce filtre, le survol de ce point precis
          // affichait la meme ligne deux fois (une par dataset). On exclut
          // donc la ligne du dataset "previsionnel" a cet index uniquement —
          // le reel, deja identique a cet endroit precis, reste affiche seul.
          filter: (item) => !(item.dataset.isForecastVariant && item.dataIndex === junctionIndex),
          callbacks: {
            label: tooltipLabel
          }
        })
      },
      scales: {
        x: axisOptions(colors, { ticks: { maxRotation: 0, autoSkip: true, font: SMALL_TICK_FONT } }),
        y: axisOptions(colors, { withGrid: true, ticks: { padding: 10, callback: (v) => fmtTick.format(v) } })
      }
    }
  })
}

/**
 * Barres épargne mensuelle : vert si positif (on a épargné), rouge si négatif
 * (on a puisé dans le solde) — gris (--chart-forecast, même teinte que le
 * prévisionnel du graphique de solde et des tableaux) pour un mois pas
 * encore terminé (p.forecast, voir ServiceRapport#monthlySavings), peu
 * importe son signe : ce n'est jamais une vraie valeur constatée.
 */
function renderSavingsChart(points) {
  lastSavingsPoints = points
  const colors = readChartColors()
  if (savingsChartInstance) savingsChartInstance.destroy()
  savingsChartInstance = new Chart(savingsChart.value, {
    type: 'bar',
    data: {
      labels: points.map(p => p.label),
      datasets: [{
        label: 'Épargne',
        data: points.map(p => p.savings),
        backgroundColor: points.map(p => (p.forecast ? colors.forecast : (Number(p.savings) >= 0 ? colors.positive : colors.negative))),
        borderRadius: 4,
        maxBarThickness: 34
      }]
    },
    options: {
      responsive: true,
      // Le canevas doit remplir toute la hauteur de .savings-chart-wrap
      // (voir Dashboard.vue : carte étirée à la même hauteur que "Dépenses/
      // Revenus périodiques"), pas un ratio largeur/hauteur fixe.
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: tooltipOptions(colors, {
          padding: 11,
          displayColors: false,
          callbacks: {
            label: (ctx) => formatAmount(ctx.parsed.y) + (points[ctx.dataIndex].forecast ? ' (prévisionnel)' : '')
          }
        })
      },
      scales: {
        x: axisOptions(colors, { ticks: { maxRotation: 0, autoSkip: true, font: SMALL_TICK_FONT } }),
        y: axisOptions(colors, { withGrid: true, ticks: { padding: 9, font: SMALL_TICK_FONT } })
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
  const colors = readChartColors()
  whereMoneyChart = new Chart(whereMoneyCanvas.value, {
    type: 'doughnut',
    data: {
      labels: data.map(d => d.category),
      datasets: [{
        data: data.map(d => d.total),
        backgroundColor: data.map((_, i) => PALETTE[i % PALETTE.length]),
        // Séparation entre les parts = couleur de la carte (claire ou sombre selon le thème)
        borderColor: colors.surface,
        borderWidth: 3,
        hoverOffset: 5,
        spacing: 2
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: true,
      cutout: '74%',
      plugins: {
        legend: { display: false },
        tooltip: tooltipOptions(colors, {
          padding: 11,
          displayColors: true,
          callbacks: {
            label: (ctx) => {
              const row = data[ctx.dataIndex]
              return ` ${row.category} : ${formatAmount(row.total)} (${formatShare(row.percentage)})`
            }
          }
        })
      }
    }
  })
}

onMounted(loadDashboard)

/**
 * Nettoyage en quittant le tableau de bord : sans lui, chaque visite laissait
 * 3 instances Chart.js vivantes (registre interne + observateurs de
 * redimensionnement sur des canvas detaches), les minuteurs du popover
 * pouvaient se declencher apres coup, et un chargement encore en vol tentait
 * de dessiner sur des canvas qui n'existent plus.
 */
onBeforeUnmount(() => {
  monthlyReportsToken++
  requestToken++
  clearTimeout(showTimer)
  clearTimeout(hideTimer)
  for (const chart of [forecastChartInstance, savingsChartInstance, whereMoneyChart]) chart?.destroy()
  forecastChartInstance = null
  savingsChartInstance = null
  whereMoneyChart = null
})
</script>

<style scoped>
/* --- Chiffres clés : variation + courbe miniature --- */
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
/* Carte sombre (solde total) : teintes claires lisibles sur fond marine. */
.kpi:first-child .kpi-delta.good { color: var(--hero-positive); }
.kpi:first-child .kpi-delta.bad { color: var(--hero-negative); }

.card-title { font-size: 15px; font-weight: 600; letter-spacing: -0.01em; color: var(--text-primary); }
.chart-note {
  line-height: 1.65;
  max-width: 1050px;
}
.savings-card .stat-label,
.where-money-card > .stat-label {
  font-size: 13px;
  letter-spacing: -.01em;
}
.where-money-card {
  padding: 20px 22px;
}
.where-money-layout {
  gap: 32px;
}
.donut-wrap {
  width: 176px;
  height: 176px;
}
.where-money-layout {
  grid-template-columns: 176px minmax(0, 1fr);
}
.donut-center-value {
  font-size: 17px;
  letter-spacing: -.035em;
}
.donut-center-label {
  margin-top: 3px;
  font-size: 10px;
  letter-spacing: .04em;
  text-transform: none;
}
.where-table {
  font-size: 12.5px;
}
.where-table th {
  padding: 0 8px 9px;
  font-size: 10px;
}
.where-table tfoot td {
  padding-top: 12px;
}
.monthly-analysis-table .category-sticky {
  font-weight: 600;
}
@media (max-width: 640px) {
  .forecast-card { padding: 16px; }
  .where-money-card { padding: 16px; }
  .where-money-layout { grid-template-columns: 1fr; gap: 16px; }
  .donut-wrap { width: 164px; height: 164px; }
}

.chart-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-faint);
}

.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.chart-mode-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  background: var(--ink-800);
  border-radius: var(--radius-md);
  padding: 4px 10px 4px 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-muted);
  flex-shrink: 0;
}
.chart-mode-toggle:hover {
  background: var(--ink-700);
}
.toggle-track {
  position: relative;
  width: 34px;
  height: 18px;
  border-radius: 999px;
  background: var(--line);
  flex-shrink: 0;
  transition: background 0.2s ease;
}
.chart-mode-toggle.is-separate .toggle-track {
  background: var(--violet);
}
.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--ink-900);
  box-shadow: var(--shadow-sm);
  transition: transform 0.2s ease;
}
.chart-mode-toggle.is-separate .toggle-thumb {
  transform: translateX(16px);
}
.toggle-label {
  font-weight: 600;
  white-space: nowrap;
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

/* La carte "Épargne mensuelle" est étirée à la hauteur des cartes
   périodiques voisines (stretch de .top-row-grid) ; ici, le graphique
   lui-même remplit cet espace au lieu de laisser du vide sous la légende.
   Colonne flex : le label et la note gardent leur taille naturelle,
   .savings-chart-wrap absorbe tout l'espace restant (flex: 1). min-height
   sert de repli quand la carte n'est pas étirée (ex. impression, voir
   @media print plus bas : .top-row-grid y redevient display:block). */
.savings-card {
  display: flex;
  flex-direction: column;
}
.savings-chart-wrap {
  position: relative;
  flex: 1 1 auto;
  min-height: 160px;
}
.savings-chart-wrap canvas {
  position: absolute;
  inset: 0;
  width: 100% !important;
  height: 100% !important;
}

.periodic-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
@media (max-width: 860px) {
  .periodic-grid { grid-template-columns: 1fr; }
}

/* Cartes "Épargne mensuelle", "Dépenses/Revenus périodiques" et "Où va mon
   argent ?" : padding réduit par rapport à .card par défaut (22px) pour que
   ces trois cartes prennent moins de place à l'écran (l'export PDF n'est pas
   concerné, voir la règle @media print de .card qui repasse à 16px). */
.compact-card {
  padding: 16px 18px;
}

/* Au-delà de quelques tiers, la liste défile plutôt que d'agrandir la carte
   (même logique que .where-table-wrap juste en dessous). scrollbar-gutter
   réserve la place de la barre de défilement même quand elle n'est pas
   affichée, pour que le montant ne saute pas d'une position à l'autre selon
   le nombre de lignes. */
.periodic-table-wrap {
  max-height: 190px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.plain-table {
  width: 100%;
  border-collapse: collapse;
}
.plain-table td {
  padding: 6px 0;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}
/* Montant décalé de la bordure droite : sinon collé à la barre de
   défilement quand la liste dépasse les 190px de .periodic-table-wrap. */
.plain-table td.num {
  padding-right: 6px;
}
.plain-table tr:last-child td {
  border-bottom: none;
}

.periodic-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 2px solid var(--line);
  font-weight: 700;
  font-size: 14px;
}

.compact-empty-state {
  padding: 24px 16px;
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
  grid-template-columns: 150px 1fr;
  gap: 24px;
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
  width: 150px;
  height: 150px;
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
  font-size: 15px;
  font-weight: 800;
  color: var(--text-primary);
  line-height: 1.2;
}
.donut-center-label {
  font-size: 10px;
  color: var(--text-faint);
  margin-top: 2px;
}

.where-table-wrap {
  max-height: 190px;
  overflow-y: auto;
}
.where-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.where-table th {
  position: sticky;
  top: 0;
  background: var(--ink-900);
  text-align: left;
  padding: 0 0 6px;
  font-size: 10px;
  text-transform: none;
  letter-spacing: .06em;
  color: var(--text-faint);
  font-weight: 700;
}
.where-table td {
  padding: 5px 0;
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
  font-size: 12.5px;
}

.monthly-analysis-table th,
.monthly-analysis-table td {
  /* 6px lateraux (et non 8) : 15 colonnes, les 12 mois + categorie +
     moyenne + total tiennent sans defilement sur un ecran large. */
  padding: 8px 6px;
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
  text-transform: none;
  letter-spacing: .05em;
  /* Ligne de separation nette entre l'entete fige et le contenu qui defile
     dessous (le simple border-bottom des cellules ne suffit pas a se voir
     une fois l'entete "detache" visuellement par le scroll). */
  box-shadow: 0 1px 0 var(--line);
}

.monthly-analysis-table .category-sticky {
  position: sticky;
  left: 0;
  z-index: 3;
  min-width: 160px;
  text-align: left;
  background: var(--ink-900);
  border-right: 1px solid var(--line);
}

.monthly-analysis-table thead .category-sticky {
  z-index: 6;
}

.monthly-analysis-table .num {
  min-width: 68px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
/* Cellule atteinte au clavier (flèches, voir directives/gridNav.js) */
.monthly-analysis-table td.num:focus-visible {
  outline: 2px solid var(--azure);
  outline-offset: -2px;
  border-radius: 4px;
}

/* Montants a 0 dans les tableaux "Revenus/Depenses par categorie" : moins
   visibles (texte attenue) plutot qu'affiches avec le meme contraste
   qu'une vraie valeur, pour que l'œil aille naturellement vers les
   categories qui ont effectivement bouge ce mois-la. Attenuation par la
   couleur du texte et non par opacity : l'opacite rendait aussi le FOND
   translucide, et la colonne "Total" fixee a droite laissait alors voir les
   montants qui defilent dessous. */
.monthly-analysis-table .num.zero-value {
  color: color-mix(in srgb, var(--text-faint) 45%, transparent);
}

/*
 * Mois pas encore termine (a partir du mois calendaire en cours) : rempli
 * cote backend avec le previsionnel (moyenne des mois passes de la meme
 * ligne, voir ServiceRapport#completeAnalysisRow) plutot qu'un 0 trompeur.
 * Italique + teinte attenuee (--text-muted, proche du gris previsionnel du graphique)
 * pour que ce ne soit jamais confondu avec une
 * vraie valeur constatee, sans pour autant la faire disparaitre comme
 * .zero-value (c'est une estimation utile, pas une absence de donnee) — d'ou
 * la regle placee apres .zero-value pour la primer en cas de cumul (un
 * previsionnel qui tombe pile a 0 doit rester visible comme previsionnel).
 */
.monthly-analysis-table .num.forecast-cell,
.monthly-analysis-table th.forecast-cell {
  color: var(--text-muted);
  font-style: italic;
}

/* Cartes Revenus/Depenses/Net du haut : meme traitement (italique, meme
   teinte que le previsionnel du graphique/tableau) quand summary.forecast
   est vrai (voir loadMonthlyReports). */
.stat-value.forecast-cell {
  color: var(--text-muted);
  font-style: italic;
}

/* Meme recette visuelle que .future-badge dans TransactionRow.vue (badge
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

/*
 * Case dont la valeur s'ecarte fortement (voir anomalyClass/ANOMALY_STDDEV_THRESHOLD)
 * de la moyenne des 12 mois de sa ligne : liseret corail pour la reperer d'un
 * coup d'œil au milieu du tableau — box-shadow inset plutot qu'une bordure
 * classique pour ne pas decaler la mise en page des cellules voisines (compte
 * pour l'element sans agrandir sa boite). Pas de fond colore ici : perdrait
 * en visibilite sur les lignes a fond alterne (category-group-alt ci-dessous,
 * de specificite CSS superieure sur cette propriete).
 */
.monthly-analysis-table .num.monthly-anomaly {
  box-shadow: inset 0 0 0 2px var(--coral);
  color: var(--coral);
  font-weight: 700;
}

/*
 * "Moyenne"/"Total" : colonnes d'agregat distinguees du detail mensuel par
 * un fond gris et une bordure epaisse a gauche (repere visuel net avec 12
 * colonnes de mois entre elles et la categorie). Selecteur a DEUX classes
 * (".num.summary-col", combinaison presente sur chaque cellule concernee,
 * voir le template) plutot qu'une seule (".summary-col") : une regle a une
 * seule classe a specificite CSS egale a ".category-row td" en nombre de
 * classes (une chacune), donc c'est le nombre de balises (3 pour "tbody tr td")
 * qui tranchait alors en faveur du fond blanc de la ligne — a tort, ca
 * ecrasait le gris voulu sur cette colonne dans les lignes de categorie
 * racine. Deux classes ici suffit a l'emporter d'entree, quelle que soit la
 * ligne.
 */
.monthly-analysis-table .num.summary-col {
  background: var(--ink-800);
  font-weight: 600;
}
.monthly-analysis-table .summary-col:nth-last-child(2) {
  border-left: 2px solid var(--line);
}
/*
 * "Moyenne" et "Total" (deux dernieres colonnes) restent visibles en
 * permanence pendant le defilement horizontal, meme avec 12 mois affiches
 * entre la categorie et eux : c'etait le principal reproche de lisibilite sur
 * ces tableaux (il fallait defiler jusqu'au bout pour comparer les lignes).
 * Les deux sont figees ensemble : avec "Total" seul fige, le tableau etant
 * plus large que sa carte, "Moyenne" passait dessous et son "€" etait masque
 * des l'affichage. Largeur fixe de "Total" = decalage de "Moyenne".
 * Meme principe que la colonne Actions de l'onglet Transactions.
 */
.monthly-analysis-table {
  --summary-total-width: 100px;
}
.monthly-analysis-table .summary-col:last-child {
  width: var(--summary-total-width);
  min-width: var(--summary-total-width);
  position: sticky;
  right: 0;
}
.monthly-analysis-table .summary-col:nth-last-child(2) {
  position: sticky;
  right: var(--summary-total-width);
  box-shadow: var(--sticky-edge-shadow);
}
.monthly-analysis-table th.summary-col:last-child,
.monthly-analysis-table th.summary-col:nth-last-child(2) {
  z-index: 7;
}

.monthly-analysis-table tbody tr.category-row td {
  background: var(--ink-900);
  font-weight: 600;
}

/* Alternance de fond par groupe categorie + sous-categories (voir
   category-group-alt sur le <tbody>, un par groupe) : repere visuel pour
   suivre une ligne sur toute sa largeur au milieu de 12 colonnes de mois,
   sans devoir se fier uniquement au trait de separation horizontal. */
.monthly-analysis-table tbody.category-group-alt tr.category-row td {
  background: var(--ink-950);
}
.monthly-analysis-table tbody.category-group-alt tr.subcategory-row td {
  background: var(--ink-950);
}
.monthly-analysis-table tbody.category-group-alt .num.summary-col {
  background: var(--ink-800);
}

.monthly-analysis-table tbody tr.category-row:hover td,
.monthly-analysis-table tbody tr.subcategory-row:hover td {
  background: var(--ink-800);
}

.monthly-analysis-table tbody tr.subcategory-row td {
  color: var(--text-muted);
}

.monthly-analysis-table tbody tr.subcategory-row .category-sticky {
  padding-left: 22px;
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
  width: 18px;
  height: 18px;
  padding: 0;
  margin-right: 4px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 12px;
}
.collapse-button:hover {
  background: var(--ink-700);
  color: var(--text-primary);
}

.collapse-placeholder {
  display: inline-block;
  width: 18px;
}

/* --- Export PDF (voir export/export-dashboard.mjs) ---
   Placees en fin de fichier pour gagner la cascade face aux regles ci-dessus
   (meme specificite, media print inclus) : une regle @media print definie
   plus haut dans ce fichier serait ecrasee par la regle non conditionnelle
   qui la suit, puisque toutes deux s'appliquent pendant l'impression. */

/* Sur mobile, la navigation est déjà collée en haut de l'écran : un sélecteur
   de mois en position fixe la recouvrirait. Il rejoint le flux de l'en-tête. */
@media (max-width: 760px) {
  .month-nav {
    position: static;
    width: fit-content;
    max-width: 100%;
    flex-wrap: wrap;
  }
}

/* Le position:fixed de .month-nav se repete sur chaque page imprimee et
   chevauche l'entete — on le desactive pour qu'il rejoigne le flux normal
   du .page-header, et on masque les boutons interactifs (fleches, export),
   inutiles sur un PDF. */
@media print {
  .month-nav {
    position: static;
    box-shadow: none;
    background: transparent;
    border: none;
    padding: 0;
  }
  .month-nav-btn,
  .export-btn {
    display: none;
  }
}

/* .monthly-analysis-wrap plafonne la hauteur a 720px avec defilement pour
   l'usage a l'ecran — on retire le plafond pour laisser le tableau s'etaler
   sur plusieurs pages (le thead se repete naturellement a chaque saut de
   page). Le position:sticky (pense pour le defilement a l'ecran) n'a plus de
   sens sans conteneur qui scrolle : on repasse en position statique (colonne
   "Total" comprise, sticky a droite a l'ecran depuis l'amelioration de
   lisibilite de ces tableaux). Et avec 14 colonnes (12 mois + moyenne +
   total), le padding ecran (8px 8px) fait deborder la derniere colonne meme
   en paysage — on resserre. */
@media print {
  .monthly-analysis-wrap {
    max-height: none;
    overflow: visible;
  }
  .monthly-analysis-table thead th,
  .monthly-analysis-table .category-sticky,
  .monthly-analysis-table .summary-col {
    position: static;
    box-shadow: none;
  }
  .monthly-analysis-table th,
  .monthly-analysis-table td {
    padding: 4px 3px;
  }
}

/* Tableau du graphique "Ou va mon argent ?" : contrairement aux tableaux
   d'analyse mensuelle, on VEUT que cette carte tienne sur une seule page —
   donc on garde une hauteur plafonnee (plus genereuse que les 260px ecran,
   pour montrer davantage de categories, mais bornee pour que carte
   (titre + donut + tableau) reste sous la hauteur d'une page A4 paysage).
   Le position:sticky de l'entete de colonne, lui, doit etre desactive dans
   tous les cas : meme sans defilement possible en PDF, il produit un
   artefact d'impression qui tronque le debut du texte de l'entete (le "C"
   de "Categorie" coupe). */
@media print {
  .where-table-wrap {
    max-height: 460px;
    overflow: hidden;
  }
  .where-table th {
    position: static;
  }
}

/* Page 1 de l'export : cartes de solde/revenus/depenses/net, evolution du
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
  .periodic-grid {
    margin-bottom: 28px;
  }
}

/* .compact-card et .periodic-table-wrap reduisent la place a l'ecran
   (voir plus haut) mais ne doivent pas affecter le PDF : la specificite du
   selecteur scope (attribut ajoute par Vue) l'emporterait sinon sur le
   padding de .card defini dans style.css pour l'impression, et le defilement
   de la liste des periodiques n'a pas de sens sur une page imprimee (pas de
   scrollbar : le contenu serait simplement tronque). */
@media print {
  .compact-card {
    padding: 16px;
  }
  .periodic-table-wrap {
    max-height: none;
    overflow: visible;
  }
}

/* Liste des comptes de la premiere page du PDF : n'existe qu'a l'impression
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


/* Ajustements finaux après les règles historiques du composant. */
.chart-card {
  border: 1px solid var(--line);
  box-shadow: var(--shadow-card);
}
.forecast-card { padding: 22px 24px 18px; }
.forecast-card > canvas { max-height: 310px; }
.chart-note { line-height: 1.65; max-width: 1050px; }
.where-money-card { padding: 20px 22px; }
.where-money-layout { grid-template-columns: 176px minmax(0, 1fr); gap: 32px; }
.donut-wrap { width: 176px; height: 176px; }
.donut-center-value { font-size: 17px; letter-spacing: -.035em; }
.donut-center-label { margin-top: 3px; font-size: 11px; text-transform: none; }
.where-table { font-size: 12.5px; }
.where-table th { padding: 0 8px 9px; }
.where-table td { padding: 8px; border-bottom-color: var(--grid-line); }
.where-table tbody tr:hover td { background: var(--row-hover); }
.monthly-analysis-wrap { border-color: var(--line); box-shadow: var(--shadow-sm); }
.monthly-analysis-table { font-variant-numeric: tabular-nums; }
.monthly-analysis-table thead th { padding-top: 11px; padding-bottom: 11px; background: var(--surface-sunken); color: var(--text-muted); }
.monthly-analysis-table tbody tr:hover td { background-color: var(--row-hover); }
.monthly-analysis-table .category-sticky { font-weight: 600; }
@media (max-width: 640px) {
  .forecast-card, .where-money-card { padding: 16px; }
  .where-money-layout { grid-template-columns: 1fr; gap: 16px; }
  .donut-wrap { width: 164px; height: 164px; }
}

</style>

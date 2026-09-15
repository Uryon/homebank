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
      <div class="card">
        <div class="stat-label">Solde total</div>
        <div class="stat-value" :class="totalBalance >= 0 ? 'positive amount' : 'negative amount'">
          {{ formatAmount(totalBalance) }}
        </div>
      </div>
      <div class="card">
        <div class="stat-label">
          Revenus ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount positive" :class="{ 'forecast-cell': summary.forecast }">{{ formatAmount(summary.income) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">
          Dépenses ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount negative" :class="{ 'forecast-cell': summary.forecast }">{{ formatAmount(summary.expenses) }}</div>
      </div>
      <div class="card">
        <div class="stat-label">
          Net ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount" :class="[summary.net >= 0 ? 'positive' : 'negative', { 'forecast-cell': summary.forecast }]">
          {{ formatAmount(summary.net) }}
        </div>
      </div>
    </div>

    <div class="card forecast-card" style="margin-bottom: 20px;">
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
      <div class="card compact-card savings-card">
        <div class="stat-label" style="margin-bottom: 8px;">Épargne mensuelle (12 derniers mois)</div>
        <div class="savings-chart-wrap">
          <canvas ref="savingsChart"></canvas>
        </div>
        <p class="chart-note">Épargne = revenus du mois − dépenses du mois.</p>
      </div>

      <div class="periodic-grid">
        <div class="card compact-card">
          <div class="stat-label" style="margin-bottom: 10px;">Dépenses périodiques</div>
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
          <div class="stat-label" style="margin-bottom: 10px;">Revenus périodiques</div>
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

    <div class="card compact-card" style="margin-bottom: 28px;">
      <div class="stat-label" style="margin-bottom: 4px;">Où va mon argent ?</div>
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

    <div class="card monthly-analysis-card monthly-summary-card">
      <div class="stat-label" style="margin-bottom: 4px;">Résumé mensuel — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Revenus, dépenses et solde par mois. Les 12 derniers mois entièrement terminés sont affichés<span v-if="hasForecastMonths">, complétés par un prévisionnel <em>(en italique)</em> sur les mois pas encore terminés</span>.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table class="monthly-analysis-table">
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
      <div class="stat-label" style="margin-bottom: 4px;">Dépenses par catégorie — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Évolution mensuelle par catégorie et sous-catégorie. Les 12 derniers mois entièrement terminés sont affichés<span v-if="hasForecastMonths">, complétés par un prévisionnel <em>(en italique)</em> sur les mois pas encore terminés</span>.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table class="monthly-analysis-table">
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
      <div class="stat-label" style="margin-bottom: 4px;">Revenus par catégorie — 12 derniers mois</div>
      <div class="monthly-analysis-subtitle">
        Évolution mensuelle par catégorie et sous-catégorie. Les 12 derniers mois entièrement terminés sont affichés<span v-if="hasForecastMonths">, complétés par un prévisionnel <em>(en italique)</em> sur les mois pas encore terminés</span>.
      </div>

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table class="monthly-analysis-table">
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
import { AccountsAPI, ReportsAPI, LoansAPI, ExportAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { formatAmount } from '../services/format'
import { alertApiError } from '../services/errors'
import CellTransactionsPopover from '../components/CellTransactionsPopover.vue'
import Chart from 'chart.js/auto'

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

/** Variation mensuelle moyenne appliquee a la partie previsionnelle de la courbe de solde (voir ServiceRapport#balanceForecast, champ "monthlyTrend"). */
const monthlyTrend = ref(0)

const exporting = ref(false)

/** Export manuel du tableau de bord en PDF (voir aussi Transactions.vue#validateAll, qui déclenche le même export après validation en masse). */
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
 * Fusionne les lignes qui partagent le meme tiers en une seule (montant
 * additionne, date la plus recente conservee) : sert surtout a recombiner
 * les tranches d'une transaction annualisee (voir ServiceRapport#attributedTransactions),
 * qui remontent sinon comme autant de lignes distinctes (une par mois de la
 * fenetre de 12 mois couverte par la cellule survolee, meme id de
 * transaction a chaque fois) pour ce qui est en realite UNE seule depense/
 * revenu. Les lignes sans tiers ne sont jamais fusionnees entre elles
 * (souvent des virements ou cas ponctuels sans rapport les uns aux autres).
 */
function mergeRowsByPayee(rows) {
  const merged = new Map()
  const withoutPayee = []
  for (const row of rows) {
    if (!row.payee) {
      withoutPayee.push(row)
      continue
    }
    const existing = merged.get(row.payee)
    if (existing) {
      existing.amount += Number(row.amount)
      if (row.date > existing.date) existing.date = row.date
    } else {
      merged.set(row.payee, { ...row, amount: Number(row.amount) })
    }
  }
  return [...merged.values(), ...withoutPayee]
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
/** Tri alphabetique francais par `name`, partage par analysisGroup ci-dessous (groupes et sous-categories). */
function byNameFr(a, b) {
  return String(a.name).localeCompare(String(b.name), 'fr')
}

/** Cle de regroupement d'une ligne "categorie/mois" (voir analysisGroup) : par categorie racine, elle-meme ou celle de son parent. */
function rootGroupKey(row) {
  return row.parentId != null ? `parent-${row.parentId}` : `category-${row.categoryId ?? 'uncategorized'}`
}

function zeroValuesFor(months) {
  return Object.fromEntries(months.map(m => [m.key, 0]))
}

/** Cumule les valeurs mensuelles (et total/moyenne) de source dans target — utilise pour agreger une racine ou ses sous-categories dans son groupe (voir analysisGroup). */
function addRowValues(target, source, months) {
  for (const month of months) {
    target.values[month.key] = Number(target.values[month.key] || 0) + Number(source.values[month.key] || 0)
  }
  target.total = Object.values(target.values).reduce((sum, value) => sum + Number(value || 0), 0)
  target.average = months.length ? target.total / months.length : 0
}

/** Nouveau groupe vierge (valeurs a zero) pour la categorie racine de row (voir analysisGroup). */
function newAnalysisGroup(row, months) {
  return {
    key: rootGroupKey(row),
    name: row.parentId != null ? row.parentName : row.name,
    // Categorie racine (elle-meme, ou celle dont ce groupe reunit les
    // sous-categories) pour retrouver au survol les transactions de tout
    // le groupe via rootCategoryId (voir groupCategoryFilter/ServiceRapport#cellTransactions).
    categoryId: row.parentId != null ? row.parentId : row.categoryId,
    uncategorized: row.parentId == null && row.categoryId == null,
    values: zeroValuesFor(months),
    total: 0,
    average: 0,
    children: []
  }
}

function analysisGroup(rows) {
  const months = monthlyAnalysis.value?.months || []
  const groups = new Map()

  for (const row of rows || []) {
    const rootKey = rootGroupKey(row)
    if (!groups.has(rootKey)) groups.set(rootKey, newAnalysisGroup(row, months))
    const group = groups.get(rootKey)

    if (row.parentId != null) {
      group.children.push(row)
    } else {
      // Les transactions directement affectées à la catégorie racine sont
      // intégrées au total du groupe.
      addRowValues(group, row, months)
    }
  }

  // Un parent qui n'a que des sous-catégories doit quand même afficher leur total.
  for (const group of groups.values()) {
    for (const child of group.children) addRowValues(group, child, months)
    group.children.sort(byNameFr)
  }

  return Array.from(groups.values()).sort(byNameFr)
}

const incomeGroups = computed(() => analysisGroup(monthlyAnalysis.value?.income || []))
const expenseGroups = computed(() => analysisGroup(monthlyAnalysis.value?.expenses || []))

const monthLabelByKey = computed(() =>
  new Map((monthlyAnalysis.value?.months || []).map(m => [m.key, m.label]))
)
function monthLabelFor(key) {
  return monthLabelByKey.value.get(key) || key
}

/** Vrai si la fenêtre de 12 mois affichée contient au moins un mois prévisionnel (voir monthlyAnalysis.months[].forecast) : en navigant vers le futur avec les flèches, pas seulement en vue par défaut. */
const hasForecastMonths = computed(() => (monthlyAnalysis.value?.months || []).some(m => m.forecast))

/**
 * Ecart (en ecarts-types) au-dela duquel une case mensuelle est signalee
 * comme anomalie par rapport a la moyenne de sa ligne. 1.5 remontait beaucoup
 * trop de mois (environ 1 valeur sur 7 depasse deja ce seuil dans une
 * distribution normale) ; 2.5 ne retient que les ecarts vraiment marques.
 */
const ANOMALY_STDDEV_THRESHOLD = 2.5

/** En dessous de ce nombre de mois avec une valeur non nulle, l'ecart-type n'est pas assez fiable pour signaler une anomalie. */
const ANOMALY_MIN_NONZERO_MONTHS = 4

/** Moyenne et ecart-type (population) d'un tableau de valeurs numeriques. */
function meanStdDev(values) {
  const nums = values.map(Number)
  const mean = nums.reduce((sum, v) => sum + v, 0) / (nums.length || 1)
  const variance = nums.reduce((sum, v) => sum + (v - mean) ** 2, 0) / (nums.length || 1)
  return { mean, stdDev: Math.sqrt(variance) }
}

/**
 * Signale (classe "monthly-anomaly") une case dont la valeur s'ecarte de plus
 * de ANOMALY_STDDEV_THRESHOLD ecarts-types de la moyenne des `rowValues`
 * (les valeurs des 12 mois de la meme ligne) — met en evidence les mois
 * atypiques (depense inhabituelle, revenu exceptionnel...) au premier coup
 * d'œil.
 *
 * Les mois a 0 (categorie pas utilisee ce mois-la) sont exclus du calcul de
 * la moyenne/ecart-type : sinon, sur une categorie utilisee occasionnellement,
 * les nombreux mois a 0 tirent la moyenne vers le bas et n'importe quel mois
 * avec une depense normale ressort comme "anomalie" — alors que ces mois a 0
 * sont deja mis en evidence separement (classe "zero-value"). Une valeur
 * elle-meme nulle n'est jamais signalee. Aucune case signalee si la ligne est
 * quasi constante (stdDev nul) ou si trop peu de mois ont une valeur pour
 * qu'un ecart-type soit fiable (voir ANOMALY_MIN_NONZERO_MONTHS).
 */
function anomalyClass(value, rowValues) {
  if (!Number(value)) return {}
  const nonZero = rowValues.map(Number).filter(Boolean)
  if (nonZero.length < ANOMALY_MIN_NONZERO_MONTHS) return {}
  const { mean, stdDev } = meanStdDev(nonZero)
  if (!stdDev) return {}
  return { 'monthly-anomaly': Math.abs(Number(value) - mean) / stdDev > ANOMALY_STDDEV_THRESHOLD }
}

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
  // "YYYY-MM-01" : meme mois que les cartes du haut, transmis aux rapports
  // en fenetre glissante de 12 mois pour qu'ils se terminent sur ce mois.
  const referenceMonth = `${viewedMonthStart.value.getFullYear()}-${String(viewedMonthStart.value.getMonth() + 1).padStart(2, '0')}-01`

  whereMoneyRange.value = last12MonthsRange(viewedMonthStart.value)
  const loansPromise = LoansAPI.list().then(async (loansList) => {
    const schedules = await Promise.all(loansList.map(l => LoansAPI.schedule(l.id)))
    return { loansList, schedules }
  })
  const [forecast, savings, whereMoney, monthly, { loansList, schedules }] = await Promise.all([
    ReportsAPI.balanceForecast({ referenceMonth }),
    ReportsAPI.monthlySavings({ months: 12, referenceMonth }),
    ReportsAPI.expensesByCategory(whereMoneyRange.value),
    ReportsAPI.monthlyCategoryAnalysis({ referenceMonth }),
    loansPromise
  ])
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

  await nextTick()
  renderForecastChart(forecast.points, buildLoanOwnedSeries(forecast.points, loansList, schedules))
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
 * Couleur commune a toutes les portions "previsionnel" des courbes du
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
const FORECAST_COLOR = '#9AA3B5'

/** Couleur hexadecimale -> rgba() avec une opacite donnee, pour les remplissages sous les courbes. */
function hexToRgba(hex, alpha) {
  const n = Number.parseInt(hex.slice(1), 16)
  const r = (n >> 16) & 0xff
  const g = (n >> 8) & 0xff
  const b = n & 0xff
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

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
function pastFutureDatasets(label, data, junctionIndex, color) {
  const futureColor = FORECAST_COLOR
  const pastData = data.map((v, i) => (i <= junctionIndex ? v : null))
  const futureData = data.map((v, i) => (i >= junctionIndex ? v : null))
  const pointRadiusAtJunction = (ctx) => (ctx.dataIndex === junctionIndex ? 4 : 0)

  return [
    {
      label,
      data: pastData,
      borderColor: color,
      backgroundColor: hexToRgba(color, 0.10),
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
      backgroundColor: hexToRgba(futureColor, 0.10),
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

  const datasets = showSeparate
    ? [
        ...pastFutureDatasets('Solde', points.map(p => Number(p.balance)), junctionIndex, '#5B4FCF'),
        ...pastFutureDatasets('Capital des prêts', loanOwnedSeries.map(Number), junctionIndex, '#C98A1B')
      ]
    : pastFutureDatasets(
        'Solde',
        points.map((p, i) => Number(p.balance) + (loanOwnedSeries ? Number(loanOwnedSeries[i]) : 0)),
        junctionIndex,
        '#5B4FCF'
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
            filter: (legendItem, data) => !data.datasets[legendItem.datasetIndex]?.isForecastVariant
          }
        },
        tooltip: {
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
        }
      },
      scales: {
        x: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C', maxRotation: 0, autoSkip: true } },
        y: { grid: { color: '#E2E6EE' }, ticks: { color: '#67707C' } }
      }
    }
  })
}

/**
 * Barres épargne mensuelle : vert si positif (on a épargné), rouge si négatif
 * (on a puisé dans le solde) — gris (FORECAST_COLOR, même teinte que le
 * prévisionnel du graphique de solde et des tableaux) pour un mois pas
 * encore terminé (p.forecast, voir ServiceRapport#monthlySavings), peu
 * importe son signe : ce n'est jamais une vraie valeur constatée.
 */
function renderSavingsChart(points) {
  if (savingsChartInstance) savingsChartInstance.destroy()
  savingsChartInstance = new Chart(savingsChart.value, {
    type: 'bar',
    data: {
      labels: points.map(p => p.label),
      datasets: [{
        label: 'Épargne',
        data: points.map(p => p.savings),
        backgroundColor: points.map(p => (p.forecast ? FORECAST_COLOR : (Number(p.savings) >= 0 ? '#1F9D6C' : '#D64545'))),
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
        tooltip: {
          callbacks: {
            label: (ctx) => formatAmount(ctx.parsed.y) + (points[ctx.dataIndex].forecast ? ' (prévisionnel)' : '')
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
  background: #5B4FCF;
}
.toggle-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
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
  text-transform: uppercase;
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
  padding: 8px 8px;
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

/* Montants a 0 dans les tableaux "Revenus/Depenses par categorie" : moins
   visibles (opacite reduite) plutot qu'affiches avec le meme contraste
   qu'une vraie valeur, pour que l'œil aille naturellement vers les
   categories qui ont effectivement bouge ce mois-la. */
.monthly-analysis-table .num.zero-value {
  color: var(--text-faint);
  opacity: 0.45;
}

/*
 * Mois pas encore termine (a partir du mois calendaire en cours) : rempli
 * cote backend avec le previsionnel (moyenne des mois passes de la meme
 * ligne, voir ServiceRapport#completeAnalysisRow) plutot qu'un 0 trompeur.
 * Italique + meme teinte que la portion previsionnelle du graphique de solde
 * (FORECAST_COLOR ci-dessous) pour que ce ne soit jamais confondu avec une
 * vraie valeur constatee, sans pour autant la faire disparaitre comme
 * .zero-value (c'est une estimation utile, pas une absence de donnee) — d'ou
 * la regle placee apres .zero-value pour la primer en cas de cumul (un
 * previsionnel qui tombe pile a 0 doit rester visible comme previsionnel).
 */
.monthly-analysis-table .num.forecast-cell,
.monthly-analysis-table th.forecast-cell {
  color: #9AA3B5;
  font-style: italic;
  opacity: 1;
}

/* Cartes Revenus/Depenses/Net du haut : meme traitement (italique, meme
   teinte que le previsionnel du graphique/tableau) quand summary.forecast
   est vrai (voir loadMonthlyReports). */
.stat-value.forecast-cell {
  color: #9AA3B5;
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
  text-transform: uppercase;
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
 * "Total" (derniere colonne) reste visible en permanence pendant le
 * defilement horizontal, meme avec 12 mois affiches entre la categorie et
 * lui : c'etait le principal reproche de lisibilite sur ces tableaux (il
 * fallait defiler jusqu'au bout pour comparer le total de plusieurs lignes).
 * Meme principe que la colonne Actions de l'onglet Transactions.
 */
.monthly-analysis-table td.summary-col:last-child {
  position: sticky;
  right: 0;
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
}
.monthly-analysis-table th.summary-col:last-child {
  position: sticky;
  right: 0;
  z-index: 7;
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
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

</style>

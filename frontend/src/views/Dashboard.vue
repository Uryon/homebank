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
      <div class="card kpi">
        <div class="stat-label">
          Revenus ({{ monthLabel }})
          <span v-if="summary.forecast" class="forecast-badge" title="Mois pas encore terminé : montant prévisionnel (moyenne des mois précédents)">Prévu</span>
        </div>
        <div class="stat-value amount positive" :class="{ 'forecast-cell': summary.forecast }"><Money :value="Number(summary.income)" /></div>
        <div v-if="kpiYearAgo.income" class="kpi-yoy" :class="kpiYearAgo.income.tone" :title="kpiYearAgo.income.title">{{ kpiYearAgo.income.text }}</div>
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
        <div v-if="kpiYearAgo.expenses" class="kpi-yoy" :class="kpiYearAgo.expenses.tone" :title="kpiYearAgo.expenses.title">{{ kpiYearAgo.expenses.text }}</div>
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
        <div v-if="savingsRate" class="kpi-rate">{{ savingsRate }}</div>
        <div v-if="kpiYearAgo.net" class="kpi-yoy" :class="kpiYearAgo.net.tone" :title="kpiYearAgo.net.title">{{ kpiYearAgo.net.text }}</div>
        <div class="kpi-foot">
          <span v-if="netDelta" class="kpi-delta" :class="netDelta.tone">{{ netDelta.text }}</span>
          <Sparkline :values="kpiSeries.net" :color="sparkColor(netDelta)" />
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

    <div class="card forecast-card chart-card">
      <div class="chart-header">
        <div class="stat-label chart-header-title">
          Évolution du solde — historique, présent &amp; prévisionnel
          <button
            type="button"
            class="chart-info-btn"
            :class="{ active: showForecastNote }"
            :aria-expanded="showForecastNote"
            aria-controls="forecast-note"
            aria-label="Comment le prévisionnel est-il calculé ?"
            title="Comment le prévisionnel est-il calculé ?"
            @click="showForecastNote = !showForecastNote"
          >
            <Icon name="info" size="sm" />
          </button>
        </div>
        <button
          v-if="hasLoans"
          type="button"
          role="switch"
          class="chart-mode-toggle"
          :class="{ 'is-on': loansAddedToBalance }"
          :aria-checked="loansAddedToBalance"
          title="Activé : le capital déjà remboursé de vos prêts est ajouté au solde (une seule courbe). Désactivé : solde et capital des prêts en deux courbes."
          @click="toggleChartMode"
        >
          <span class="toggle-track"><span class="toggle-thumb"></span></span>
          <span class="toggle-label">Additionner les prêts</span>
        </button>
        <span v-if="hasLoans" class="print-only chart-print-caption">
          {{ loansAddedToBalance ? 'Solde + capital déjà remboursé des prêts' : 'Solde et capital des prêts' }}
        </span>
      </div>
      <canvas ref="forecastChart" height="90"></canvas>
      <p v-show="showForecastNote" id="forecast-note" class="chart-note">
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
        <div class="card-title">Épargne mensuelle — {{ periodLabel }}</div>
        <div class="savings-chart-wrap">
          <canvas ref="savingsChart"></canvas>
        </div>
        <p class="chart-note">Épargne = revenus du mois − dépenses du mois. Cliquez sur une barre pour voir les transactions du mois.</p>
      </div>

      <div class="periodic-grid">
        <div v-for="section in periodicSections" :key="section.key" class="card compact-card">
          <div class="card-title">{{ section.title }}</div>
          <div v-if="section.group.items.length">
            <div class="periodic-table-wrap">
              <table class="plain-table">
                <thead class="sr-only">
                  <tr><th>Tiers</th><th>Montant</th></tr>
                </thead>
                <tbody>
                  <tr v-for="item in section.group.items" :key="item.transactionId"
                      class="drillable-row" title="Voir les transactions de ce tiers" @click="openPayeeTransactions(item.payee)">
                    <td>{{ item.payee || 'Sans nom' }}</td>
                    <td class="num amount" :class="section.tone"
                        @mouseenter="scheduleHover($event, item.payee || 'Sans nom', () => periodicItemRows(item, section.sign))"
                        @mouseleave="onCellLeave">
                      {{ formatAmount(item.amount) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="periodic-total">
              <span>Total</span>
              <span class="amount" :class="section.tone"
                    @mouseenter="scheduleHover($event, section.title, () => periodicGroupRows(section.group, section.sign))"
                    @mouseleave="onCellLeave">
                {{ formatAmount(section.group.total) }}
              </span>
            </div>
          </div>
          <div v-else class="empty-state compact-empty-state">
            <p class="empty-state-text">
              {{ section.emptyLabel }}<span class="screen-only"> Cochez «&nbsp;Périodique&nbsp;» sur une transaction récurrente
              ({{ section.examples }}) dans <router-link to="/transactions">Transactions</router-link>, ou consultez
              les <router-link to="/transactions/periodiques">suggestions automatiques</router-link>.</span>
            </p>
          </div>
        </div>
      </div>
    </div>

    <div class="card compact-card where-money-card">
      <div class="where-money-header">
        <div class="card-title has-subtitle">Où va mon argent ?</div>
        <div class="scope-toggle" role="group" aria-label="Période de la répartition">
          <button type="button" class="scope-option" :class="{ active: whereMoneyScope === 'month' }" :aria-pressed="whereMoneyScope === 'month'" @click="setWhereMoneyScope('month')">{{ monthLabel }}</button>
          <button type="button" class="scope-option" :class="{ active: whereMoneyScope === 'year' }" :aria-pressed="whereMoneyScope === 'year'" @click="setWhereMoneyScope('year')">12 mois</button>
        </div>
      </div>
      <div class="card-subtitle">
        Répartition des dépenses — {{ whereMoneyPeriodLabel }} (virements entre comptes exclus).<span class="screen-only"> Cliquez sur une catégorie pour voir ses transactions.</span>
      </div>
      <div v-if="whereMoneyData.length" class="where-money-layout">
        <div class="donut-wrap">
          <canvas ref="whereMoneyCanvas" width="150" height="150"></canvas>
          <div class="donut-center">
            <div class="donut-center-value">{{ formatAmountCompact(whereMoneyTotal) }}</div>
            <div class="donut-center-label">{{ whereMoneyScope === 'month' ? 'sur le mois' : 'sur 12 mois' }}</div>
          </div>
        </div>
        <div class="where-table-wrap">
          <table class="where-table">
            <thead>
              <tr>
                <th>Catégorie</th>
                <th class="where-share-col"><span class="sr-only">Part des dépenses</span></th>
                <th class="num">Montant</th>
                <th class="num">%</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in whereMoneyData" :key="row.category" class="drillable-row"
                  title="Voir les transactions"
                  @click="openTransactions(whereMoneyRange.from, whereMoneyRange.to, 'expense', whereMoneyFilter(row), row.category + ' — ' + whereMoneyPeriodLabel)">
                <td>
                  <span class="legend-dot" :style="{ background: legendColor(i) }"></span>
                  {{ iconPrefix(row.categoryId) }}{{ row.category }}
                </td>
                <td class="where-share-col" aria-hidden="true">
                  <span class="where-share-track">
                    <span class="where-share-fill" :style="{ width: row.percentage + '%', background: legendColor(i) }"></span>
                  </span>
                </td>
                <td class="num amount negative"
                    @mouseenter="scheduleHover($event, row.category + ' — ' + whereMoneyPeriodLabel, () => fetchCellTransactions(whereMoneyRange.from, whereMoneyRange.to, 'expense', whereMoneyFilter(row)))"
                    @mouseleave="onCellLeave">
                  {{ formatAmount(row.total) }}
                </td>
                <td class="num where-pct">{{ formatShare(row.percentage) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>Total</td>
                <td class="where-share-col"></td>
                <td class="num amount negative">{{ formatAmount(whereMoneyTotal) }}</td>
                <td class="num where-pct">{{ formatShare(100) }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>
      <div v-else class="empty-state">Aucune dépense — {{ whereMoneyPeriodLabel }}.</div>
    </div>

    <div class="card monthly-analysis-card monthly-summary-card">
      <div class="card-title has-subtitle">Résumé mensuel — {{ periodLabel }}</div>
      <AnalysisLegend description="Revenus, dépenses et solde par mois." :has-forecast="hasForecastMonths" />

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table v-grid-nav class="monthly-analysis-table" aria-describedby="grid-nav-hint">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  :title="month.forecast ? 'Mois pas encore terminé : prévisionnel (moyenne des mois précédents)' : null">
                {{ month.label }}
              </th>
              <th class="num summary-col col-average">Moyenne</th>
              <th class="num summary-col col-total">Total</th>
              <th class="num summary-col col-previous" :title="previousColumnTitle">12 mois préc.<span v-if="yearOverYear.partial" class="yoy-partial" aria-hidden="true">*</span></th>
              <th class="num summary-col col-change" title="Évolution par rapport aux mêmes 12 mois un an plus tôt">Évol.</th>
            </tr>
          </thead>

          <tbody>
            <tr v-for="row in summaryRows" :key="row.key" :class="{ 'monthly-balance-row': row.balance }">
              <td class="category-sticky summary-label">
                <strong v-if="row.balance">{{ row.rowLabel }}</strong>
                <template v-else>{{ row.rowLabel }}</template>
              </td>
              <td v-for="month in monthlyAnalysis.summary" :key="'summary-' + row.key + '-' + month.key" class="num"
                  :class="[summaryAmountClass(row, month[row.key], true), anomalyFor(row.key, month.key), { 'forecast-cell': month.forecast }]"
                  @mouseenter="scheduleMonthHover($event, row.label + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, row.flow), month.forecast)"
                  @mouseleave="onCellLeave"
                  @click="openMonthTransactions(month.key, row.flow, {}, row.label + ' — ' + monthLabelFor(month.key), month.forecast)">
                <component :is="row.balance ? 'strong' : 'span'">{{ formatAmount(month[row.key]) }}</component>
              </td>
              <td class="num summary-col col-average" :class="summaryAmountClass(row, row.average)"
                  @mouseenter="scheduleHover($event, row.label + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, row.flow))"
                  @mouseleave="onCellLeave"
                  @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, row.flow, {}, row.label + ' — ' + periodLabel)">
                <component :is="row.balance ? 'strong' : 'span'">{{ formatAmount(row.average) }}</component>
              </td>
              <td class="num summary-col col-total" :class="summaryAmountClass(row, row.total)"
                  @mouseenter="scheduleHover($event, row.label + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, row.flow))"
                  @mouseleave="onCellLeave"
                  @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, row.flow, {}, row.label + ' — ' + periodLabel)">
                <component :is="row.balance ? 'strong' : 'span'">{{ formatAmount(row.total) }}</component>
              </td>
              <td class="num summary-col col-previous" v-bind="previousCell(row.previous, row.flow, {}, row.label)">
                <component :is="row.balance ? 'strong' : 'span'">{{ previousAmountText(row.previous) }}</component>
              </td>
              <td class="num summary-col col-change" :class="changeFor(row.changeKey).tone" :title="changeFor(row.changeKey).title"><component :is="row.balance ? 'strong' : 'span'">{{ changeFor(row.changeKey).text }}</component></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state">Chargement de l'analyse mensuelle…</div>
    </div>

    <div v-for="section in categorySections" :key="section.flow" class="card monthly-analysis-card">
      <div class="card-title has-subtitle">{{ section.title }} — {{ periodLabel }}</div>
      <AnalysisLegend description="Évolution mensuelle par catégorie et sous-catégorie." :has-forecast="hasForecastMonths" />

      <div v-if="monthlyAnalysis" class="monthly-analysis-wrap">
        <table v-grid-nav class="monthly-analysis-table" aria-describedby="grid-nav-hint">
          <thead>
            <tr>
              <th class="category-sticky">Catégorie</th>
              <th v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  :title="month.forecast ? 'Mois pas encore terminé : prévisionnel (moyenne des mois précédents)' : null">
                {{ month.label }}
              </th>
              <th class="num summary-col col-average">Moyenne</th>
              <th class="num summary-col col-total">Total</th>
              <th class="num summary-col col-previous" :title="previousColumnTitle">12 mois préc.<span v-if="yearOverYear.partial" class="yoy-partial" aria-hidden="true">*</span></th>
              <th class="num summary-col col-change" title="Évolution par rapport aux mêmes 12 mois un an plus tôt">Évol.</th>
            </tr>
          </thead>

          <tbody v-for="(group, i) in section.groups" :key="section.flow + '-' + group.key" :class="{ 'category-group-alt': i % 2 === 1 }">
            <tr class="category-row">
              <td class="category-sticky category-name">
                <button v-if="group.children.length" class="collapse-button"
                        @click="toggleMonthlyGroup(section.flow, group.key)"
                        :aria-expanded="!isMonthlyGroupCollapsed(section.flow, group.key)"
                        :aria-label="(isMonthlyGroupCollapsed(section.flow, group.key) ? 'Développer ' : 'Replier ') + group.name"
                        :title="isMonthlyGroupCollapsed(section.flow, group.key) ? 'Développer' : 'Replier'">
                  <Icon :name="isMonthlyGroupCollapsed(section.flow, group.key) ? 'chevron-right' : 'chevron-down'" size="sm" />
                </button>
                <span v-else class="collapse-placeholder"></span>
                <strong>{{ iconPrefix(group.categoryId) }}{{ group.name }}</strong>
              </td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="[{ 'zero-value': !Number(group.values[month.key]) }, anomalyFor(group, month.key), { 'forecast-cell': month.forecast }]"
                  @mouseenter="scheduleMonthHover($event, group.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, section.flow, groupCategoryFilter(group)), month.forecast)"
                  @mouseleave="onCellLeave"
                  @click="openMonthTransactions(month.key, section.flow, groupCategoryFilter(group), group.name + ' — ' + monthLabelFor(month.key), month.forecast)">
                {{ formatAmount(group.values[month.key]) }}
              </td>
              <td class="num summary-col col-average" :class="{ 'zero-value': !Number(group.average) }"
                  @mouseenter="scheduleHover($event, group.name + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, groupCategoryFilter(group)))"
                  @mouseleave="onCellLeave"
                  @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, groupCategoryFilter(group), group.name + ' — ' + periodLabel)">
                {{ formatAmount(group.average) }}
              </td>
              <td class="num summary-col col-total" :class="{ 'zero-value': !Number(group.total) }"
                  @mouseenter="scheduleHover($event, group.name + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, groupCategoryFilter(group)))"
                  @mouseleave="onCellLeave"
                  @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, groupCategoryFilter(group), group.name + ' — ' + periodLabel)">
                {{ formatAmount(group.total) }}
              </td>
              <td class="num summary-col col-previous" v-bind="previousCell(group.previousTotal, section.flow, groupCategoryFilter(group), group.name)">
                {{ previousAmountText(group.previousTotal) }}
              </td>
              <td class="num summary-col col-change" :class="changeFor(group).tone" :title="changeFor(group).title">{{ changeFor(group).text }}</td>
            </tr>

            <template v-if="!isMonthlyGroupCollapsed(section.flow, group.key)">
              <tr v-for="child in group.children" :key="section.flow + '-child-' + child.key"
                  class="subcategory-row">
                <td class="category-sticky category-name subcategory-name">
                  └ {{ iconPrefix(child.categoryId) }}{{ child.name }}
                </td>
                <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                    :class="[{ 'zero-value': !Number(child.values[month.key]) }, anomalyFor(child, month.key), { 'forecast-cell': month.forecast }]"
                    @mouseenter="scheduleMonthHover($event, child.name + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, section.flow, childCategoryFilter(child)), month.forecast)"
                    @mouseleave="onCellLeave"
                    @click="openMonthTransactions(month.key, section.flow, childCategoryFilter(child), child.name + ' — ' + monthLabelFor(month.key), month.forecast)">
                  {{ formatAmount(child.values[month.key]) }}
                </td>
                <td class="num summary-col col-average" :class="{ 'zero-value': !Number(child.average) }"
                    @mouseenter="scheduleHover($event, child.name + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, childCategoryFilter(child)))"
                    @mouseleave="onCellLeave"
                    @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, childCategoryFilter(child), child.name + ' — ' + periodLabel)">
                  {{ formatAmount(child.average) }}
                </td>
                <td class="num summary-col col-total" :class="{ 'zero-value': !Number(child.total) }"
                    @mouseenter="scheduleHover($event, child.name + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, childCategoryFilter(child)))"
                    @mouseleave="onCellLeave"
                    @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, childCategoryFilter(child), child.name + ' — ' + periodLabel)">
                  {{ formatAmount(child.total) }}
                </td>
                <td class="num summary-col col-previous" v-bind="previousCell(child.previousTotal, section.flow, childCategoryFilter(child), child.name)">
                  {{ previousAmountText(child.previousTotal) }}
                </td>
                <td class="num summary-col col-change" :class="changeFor(child).tone" :title="changeFor(child).title">{{ changeFor(child).text }}</td>
              </tr>
            </template>
          </tbody>

          <tbody>
            <tr class="total-row" :class="section.flow + '-total-row'">
              <td class="category-sticky"><strong>{{ section.total.name }}</strong></td>
              <td v-for="month in monthlyAnalysis.months" :key="month.key" class="num"
                  :class="{ 'forecast-cell': month.forecast }"
                  @mouseenter="scheduleMonthHover($event, section.totalLabel + ' — ' + monthLabelFor(month.key), () => fetchMonthCellTransactions(month.key, section.flow), month.forecast)"
                  @mouseleave="onCellLeave"
                  @click="openMonthTransactions(month.key, section.flow, {}, section.totalLabel + ' — ' + monthLabelFor(month.key), month.forecast)">
                <strong>{{ formatAmount(section.total.values[month.key]) }}</strong>
              </td>
              <td class="num summary-col col-average"
                  @mouseenter="scheduleHover($event, section.totalLabel + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow))"
                  @mouseleave="onCellLeave"
                  @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, {}, section.totalLabel + ' — ' + periodLabel)">
                <strong>{{ formatAmount(section.total.average) }}</strong>
              </td>
              <td class="num summary-col col-total"
                  @mouseenter="scheduleHover($event, section.totalLabel + ' — ' + periodLabel, () => fetchCellTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow))"
                  @mouseleave="onCellLeave"
                  @click="openTransactions(monthlyAnalysis.from, monthlyAnalysis.to, section.flow, {}, section.totalLabel + ' — ' + periodLabel)">
                <strong>{{ formatAmount(section.total.total) }}</strong>
              </td>
              <td class="num summary-col col-previous" v-bind="previousCell(section.total.previousTotal, section.flow, {}, section.totalLabel)">
                <strong>{{ previousAmountText(section.total.previousTotal) }}</strong>
              </td>
              <td class="num summary-col col-change" :class="changeFor(section.total).tone" :title="changeFor(section.total).title"><strong>{{ changeFor(section.total).text }}</strong></td>
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
import { ref, onMounted, onBeforeUnmount, computed, nextTick, watch } from 'vue'
import { monthStepFromKey } from '../services/shortcuts'
import { notify } from '../services/feedback'
import { useRouteActions } from '../services/routeActions'
import { useRouter } from 'vue-router'
import { effectiveTheme } from '../services/theme'
import { areaGradient, axisOptions, barGradient, CHART_FONT_FAMILY, readChartColors, tooltipOptions } from '../services/chartTheme'
import { ReportsAPI, LoansAPI, ExportAPI, TransactionsAPI } from '../services/api'
import { needsCategory } from '../services/categories'
import { drillDownQuery } from '../services/drillDown'
import { dataStore } from '../services/dataStore'
import { formatAmount } from '../services/format'
import { accountBalanceRows, accountCountLabel, activeAccounts, amountToneClass, computeNetWorth, sortAccountsForDisplay } from '../services/accounts'
import { alertApiError } from '../services/errors'
import CellTransactionsPopover from '../components/CellTransactionsPopover.vue'
import { popoverPlacement } from '../services/popoverPlacement'
import Chart from '../services/chart'
import Money from '../components/Money.vue'
import Sparkline from '../components/Sparkline.vue'
import Icon from '../components/Icon.vue'
import AnalysisLegend from '../components/AnalysisLegend.vue'
import {
  analysisGroup,
  balanceChangeOverPeriod,
  buildLoanOwnedSeries,
  dashboardTodoItems,
  last12MonthsRange,
  mergeRowsByPayee,
  monthDelta,
  monthRange,
  monthWindowLabel,
  rowAnomalyClasses,
  savingsRateLabel,
  yearAgoKpi,
  yearOverYearChange,
  yearOverYearCoverage
} from '../services/dashboardAnalysis'

const router = useRouter()

// --- Bandeau « À traiter » : ce qui attend une action, avec un lien direct ---
// Transactions lues dans le cache partagé (comme le badge de la barre latérale) ;
// les doublons, eux, sont calculés côté backend : une requête, sans bloquer la page.
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
    const groups = await TransactionsAPI.duplicates()
    // Les remboursements (même montant en sens inverse) ne sont pas de vrais doublons.
    duplicateCount.value = groups.filter(g => !g.reimbursement).length
  } catch {
    duplicateCount.value = 0 // point secondaire : le bandeau se passe simplement de ce chiffre
  }
}

// --- Passage aux transactions : un clic sur un montant ouvre la liste filtrée ---
/** Ouvre la page Transactions sur la période, le sens et la catégorie d'un montant (voir services/drillDown.js). */
function openTransactions(from, to, flow, filters, label) {
  router.push({ path: '/transactions', query: drillDownQuery({ from, to, flow, filters, label }) })
}

/** Même chose pour une case d'un mois ; sans effet sur un mois prévisionnel (pas encore de transactions réelles). */
function openMonthTransactions(monthKey, flow, filters, label, isForecast) {
  if (isForecast) return
  const { from, to } = monthRange(monthKey)
  openTransactions(from, to, flow, filters, label)
}

/** Une dépense périodique : ses occurrences, retrouvées par une recherche exacte sur le tiers. */
function openPayeeTransactions(payee) {
  if (!payee) return
  router.push({ path: '/transactions', query: { search: `"${payee}"` } })
}

// Catégories issues du cache partagé (voir App.vue / dataStore.js), uniquement
// pour résoudre l'icône (emoji) à afficher devant chaque nom de catégorie
// dans les tableaux ci-dessous (voir iconPrefix) — le reste du tableau de
// bord ne consomme que les rapports agreges de ReportsAPI, jamais les
// catégories elles-mêmes.
const categoriesById = computed(() => new Map(dataStore.state.categories.map(c => [c.id, c])))

/** Préfixe un nom de catégorie avec son icône (emoji) resolue via son id, suivie d'un espace — chaîne vide si aucune icône ou id inconnu. */
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
const savingsRate = computed(() => savingsRateLabel(summary.value.income, summary.value.net))
const balanceDelta = computed(() => balanceChangeOverPeriod(balanceSeries.value, '12 mois'))

// Comptes et soldes lus dans le cache partagé (même source que le
// « Patrimoine net » de la barre latérale, rafraîchi après chaque
// modification) plutôt que rechargés à chaque visite du tableau de bord.
const accounts = computed(() => dataStore.state.accounts)
const balances = computed(() => dataStore.state.balances)
const summary = ref({ income: 0, expenses: 0, net: 0 })
const monthLabel = ref('')
const periodicExpenses = ref({ expenses: { items: [], total: 0 }, revenues: { items: [], total: 0 } })
/** Cartes « Dépenses périodiques » et « Revenus périodiques », rendues par le même gabarit (sign : signe des montants affichés). */
const periodicSections = computed(() => [
  { key: 'expenses', title: 'Dépenses périodiques', group: periodicExpenses.value.expenses, sign: -1, tone: 'negative', emptyLabel: 'Aucune dépense périodique marquée.', examples: 'loyer, abonnement…' },
  { key: 'revenues', title: 'Revenus périodiques', group: periodicExpenses.value.revenues, sign: 1, tone: 'positive', emptyLabel: 'Aucun revenu périodique marqué.', examples: 'salaire, remboursement régulier…' }
])

const forecastChart = ref(null)
const savingsChart = ref(null)
let forecastChartInstance = null
let savingsChartInstance = null
const hasLoans = ref(false)

/** Variation mensuelle moyenne appliquée à la partie prévisionnelle de la courbe de solde (voir ServiceRapport#balanceForecast, champ "monthlyTrend"). */
const monthlyTrend = ref(0)

const exporting = ref(false)

/** Export manuel du tableau de bord en PDF (le même endpoint est appelé automatiquement à la fermeture de l'appli par HomeBank.vbs). */
async function exportDashboard() {
  exporting.value = true
  try {
    await ExportAPI.dashboardPdf()
    notify('Tableau de bord exporté en PDF dans le dossier de sortie.', 'success')
  } catch (e) {
    alertApiError('Export PDF échoué', e)
  } finally {
    exporting.value = false
  }
}

/**
 * Mode d'affichage du graphique "Évolution du solde" : 'combined' additionné
 * solde et capital des prêts en une seule courbe (comportement historique),
 * 'separate' affiche les deux courbes indépendamment. Bascule via le bouton
 * toggleChartMode, sans recharger les données (voir lastForecastPoints/
 * lastLoanOwnedSeries ci-dessous).
 */
const chartDisplayMode = ref('combined')
/**
 * État de l'interrupteur « Additionner les prêts » : libellé fixe, l'état est
 * porté par l'interrupteur lui-même (un libellé qui changeait selon le mode
 * se lisait à l'envers : « Courbe additionnée » affiché interrupteur éteint).
 */
const loansAddedToBalance = computed(() => chartDisplayMode.value === 'combined')
/** Explication du prévisionnel, repliée par défaut (bouton ⓘ du titre) pour alléger la carte. */
const showForecastNote = ref(false)
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
// désormais integres au tableau de bord). Portee sur l'ensemble des comptes,
// sans filtre (le tableau de bord n'a jamais eu de sélecteur de compte).
const whereMoneyData = ref([])
const whereMoneyRange = ref({ from: '', to: '' })
const monthlyAnalysis = ref(null)
const collapsedMonthlyGroups = ref({ income: {}, expense: {} })
const whereMoneyCanvas = ref(null)
let whereMoneyChart = null

/**
 * Popover unique (voir CellTransactionsPopover.vue) affichant le détail des
 * transactions derrière une cellule survolée. Un seul état/instance partagée
 * pour tous les tableaux du tableau de bord plutôt qu'un popover par cellule :
 * évite de multiplier les composants pour une interaction qui n'est jamais
 * active qu'à un seul endroit à la fois.
 */
const HOVER_DELAY_MS = 1000
const HIDE_GRACE_MS = 150
const POPOVER_WIDTH = 320
const POPOVER_MAX_HEIGHT = 320 // max-height de .cell-popover

const hover = ref({ visible: false, loading: false, x: 0, y: 0, above: false, title: '', rows: [], total: 0 })
let showTimer = null
let hideTimer = null
let requestToken = 0

/** Sous la case, ou au-dessus pour une case en bas de l'écran (voir popoverPlacement). */
function positionFromEvent(event) {
  return popoverPlacement(
    event.currentTarget.getBoundingClientRect(),
    { width: window.innerWidth, height: window.innerHeight },
    { width: POPOVER_WIDTH, maxHeight: POPOVER_MAX_HEIGHT }
  )
}

/**
 * Declenche l'affichage du popover après HOVER_DELAY_MS de survol continu de
 * la cellule (pour éviter l'apparition intempestive au simple passage de la
 * souris). `loadRows` doit renvoyer un tableau de { id, date, payee, amount }
 * — les mêmes transactions individuelles qui composent le montant affiché
 * dans la cellule.
 */
function scheduleHover(event, title, loadRows) {
  cancelHideTimer()
  clearTimeout(showTimer)
  const { x, y, above } = positionFromEvent(event)
  const token = ++requestToken
  showTimer = setTimeout(async () => {
    hover.value = { visible: true, loading: true, x, y, above, title, rows: [], total: 0 }
    try {
      const rows = await loadRows()
      if (token !== requestToken) return
      const total = rows.reduce((sum, r) => sum + Number(r.amount || 0), 0)
      const mergedRows = mergeRowsByPayee(rows)
      // Tri par montant decroissant en valeur ABSOLUE (pas juste par valeur
      // signee) : sur une liste d'un seul sens (que des dépenses, ex. "Alimentation :
      // Courses"), trier par valeur signee décroissante mettait la plus PETITE
      // dépense en premier (-1,15€ avant -1,25€, puisque -1,15 > -1,25) — l'inverse
      // de "decroissant" au sens ou l'utilisateur l'entend (la plus grosse
      // transaction en premier, quel que soit le sens).
      const sortedRows = mergedRows.sort((a, b) => Math.abs(Number(b.amount)) - Math.abs(Number(a.amount)))
      hover.value = { visible: true, loading: false, x, y, above, title, rows: sortedRows, total }
    } catch {
      if (token !== requestToken) return
      hover.value = { ...hover.value, loading: false }
    }
  }, HOVER_DELAY_MS)
}

/**
 * Comme scheduleHover, mais sans effet sur une case prévisionnelle (mois pas
 * encore terminé, badge "Prevu" : voir ServiceRapport#completeAnalysisRow).
 * Son montant est une moyenne estimee sur les mois passes, jamais une somme
 * de transactions réelles de ce mois-là (qui n'a pas encore eu lieu) — un
 * survol y interrogerait cellTransactions pour un mois quasi vide (au mieux
 * quelques tranches de transaction annualisee qui ne totalisent pas la
 * moyenne affichée), un détail trompeur plutôt qu'absent.
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

/** Quitte la cellule survolée : annule un affichage pas encore déclenché, ou programme la fermeture. */
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

// Palette catégorielle (8 teintés distinguables, y compris pour les daltoniens),
// volontairement sans vert/rouge « sémantiques » : une catégorie n'est ni un gain ni une perte.
const PALETTE = ['#3E63DD', '#F76B15', '#12A594', '#D6409F', '#8E4EC6', '#00A2C7', '#E5A400', '#7B8794']
/** Couleur de la i-ème part du donut, reprise par la pastille et la barre de sa ligne. */
function legendColor(index) {
  return PALETTE[index % PALETTE.length]
}
const SMALL_TICK_FONT = { family: CHART_FONT_FAMILY, size: 10 }
const fmtTick = new Intl.NumberFormat('fr-FR')

// Mêmes calculs partagés que la sidebar (services/accounts.js). Tous les
// comptes, archivés compris (en fin de liste) : la liste imprimée somme ainsi
// exactement au « Solde total » affiché.
const totalBalance = computed(() => computeNetWorth(accounts.value, balances.value))
const totalBalanceClass = computed(() => amountToneClass(totalBalance.value))
// Comptes actifs seulement, comme la barre latérale que cette carte remplace dans le PDF
// (un compte archivé, soldé à 0, n'apporte rien sur l'export).
const printedAccounts = computed(() => accountBalanceRows(sortAccountsForDisplay(activeAccounts(accounts.value)), balances.value))
const printedAccountsCountLabel = computed(() => accountCountLabel(printedAccounts.value.length))

// Instance créée une seule fois, comme amountFormatter dans services/format.js :
// construire un Intl.NumberFormat à chaque appel est coûteux.
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

/**
 * Fenêtre de 12 mois des tableaux et graphiques, en clair pour les titres :
 * « 12 derniers mois » par défaut, les mois exacts dès qu'on navigue
 * (sinon le titre annonçait toujours « 12 derniers mois » à tort).
 */
const periodLabel = computed(() => {
  if (isCurrentMonth.value) return '12 derniers mois'
  const { from, to } = last12MonthsRange(viewedMonthStart.value)
  return monthWindowLabel(from, to)
})

/** « Où va mon argent ? » sur le mois affiché ('month') ou sur ses 12 mois ('year'). */
const whereMoneyScope = ref('year')
const whereMoneyPeriodLabel = computed(() => (whereMoneyScope.value === 'month' ? monthLabel.value : periodLabel.value))

function whereMoneyRangeFor(monthStart) {
  if (whereMoneyScope.value === 'year') return last12MonthsRange(monthStart)
  const key = `${monthStart.getFullYear()}-${String(monthStart.getMonth() + 1).padStart(2, '0')}-01`
  return monthRange(key)
}

/** Filtre catégorie d'une ligne de « Où va mon argent ? » (catégorie exacte, ou « Sans catégorie »). */
function whereMoneyFilter(row) {
  return row.uncategorized ? { uncategorized: true } : { categoryId: row.categoryId }
}

let whereMoneyToken = 0

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
    await nextTick()
    renderWhereMoneyChart(data)
  } catch (e) {
    alertApiError('Erreur lors du chargement de la répartition des dépenses', e)
  }
}

const whereMoneyTotal = computed(() =>
  whereMoneyData.value.reduce((sum, row) => sum + Number(row.total || 0), 0)
)

const analysisMonths = computed(() => monthlyAnalysis.value?.months || [])
const incomeGroups = computed(() => analysisGroup(monthlyAnalysis.value?.income || [], analysisMonths.value))
const expenseGroups = computed(() => analysisGroup(monthlyAnalysis.value?.expenses || [], analysisMonths.value))
/** Les deux tableaux « … par catégorie » (dépenses puis revenus), rendus par le même gabarit. */
const categorySections = computed(() => {
  // Toujours les deux sections, même pendant le chargement : chaque carte affiche alors son « Chargement… ».
  const analysis = monthlyAnalysis.value
  return [
    { flow: 'expense', title: 'Dépenses par catégorie', groups: expenseGroups.value, total: analysis?.expenseTotal, totalLabel: 'Total dépenses' },
    { flow: 'income', title: 'Revenus par catégorie', groups: incomeGroups.value, total: analysis?.incomeTotal, totalLabel: 'Total revenus' }
  ]
})

/** Mois en toutes lettres avec l'année (« Septembre 2026 ») pour les titres du détail et la pastille de la page Transactions : les en-têtes « Sept. » ne précisent pas l'année. */
function monthLabelFor(key) {
  const [year, month] = key.split('-').map(Number)
  return monthLabelOf(new Date(year, month - 1, 1))
}

/** Vrai si la fenêtre de 12 mois affichée contient au moins un mois prévisionnel (voir monthlyAnalysis.months[].forecast) : en navigant vers le futur avec les flèches, pas seulement en vue par défaut. */
const hasForecastMonths = computed(() => (monthlyAnalysis.value?.months || []).some(m => m.forecast))

/** Solde moyen et total sur 12 mois (colonnes Moyenne/Total de la ligne « Solde du mois »). */
const netAverage = computed(() => Number(monthlyAnalysis.value?.incomeTotal.average) - Number(monthlyAnalysis.value?.expenseTotal.average))
const netTotal = computed(() => Number(monthlyAnalysis.value?.incomeTotal.total) - Number(monthlyAnalysis.value?.expenseTotal.total))

// --- Comparaison avec l'année précédente : colonnes « 12 mois préc. » et
// « Évol. » des tableaux, ligne « vs … » des cartes du haut. Mêmes 12 mois un
// an plus tôt, calculés côté backend avec les mêmes règles (voir
// ServiceRapport#monthlyCategoryAnalysis).
const yearOverYear = computed(() => yearOverYearCoverage(monthlyAnalysis.value))
const previousPeriodLabel = computed(() => (monthlyAnalysis.value?.previousFrom
  ? monthWindowLabel(monthlyAnalysis.value.previousFrom, monthlyAnalysis.value.previousTo)
  : ''))
const previousNetTotal = computed(() =>
  Number(monthlyAnalysis.value?.incomeTotal.previousTotal) - Number(monthlyAnalysis.value?.expenseTotal.previousTotal))

/**
 * Lignes du tableau « Résumé mensuel », rendues par le même gabarit. key :
 * champ du mois (month.income…) et clé d'anomalie ; changeKey : clé de
 * yearOverYearChanges ; balance : ligne de solde (montants en <strong>, teintés selon le signe).
 */
const summaryRows = computed(() => {
  const analysis = monthlyAnalysis.value
  if (!analysis) return []
  const { incomeTotal, expenseTotal } = analysis
  return [
    { key: 'income', label: 'Revenus', rowLabel: 'Revenus', flow: 'income', average: incomeTotal.average, total: incomeTotal.total, previous: incomeTotal.previousTotal, changeKey: incomeTotal, balance: false },
    { key: 'expenses', label: 'Dépenses', rowLabel: 'Dépenses', flow: 'expense', average: expenseTotal.average, total: expenseTotal.total, previous: expenseTotal.previousTotal, changeKey: expenseTotal, balance: false },
    { key: 'net', label: 'Solde', rowLabel: 'Solde du mois', flow: null, average: netAverage.value, total: netTotal.value, previous: previousNetTotal.value, changeKey: 'net', balance: true }
  ]
})

/**
 * Classe d'un montant du résumé : teinte du solde selon le signe ; sur les
 * autres lignes, case estompée si nulle (seulement pour les mois : moyenne et
 * total des revenus/dépenses ne sont jamais estompés).
 */
function summaryAmountClass(row, value, dimZero = false) {
  if (row.balance) return balanceToneClass(value)
  return dimZero ? { 'zero-value': !Number(value) } : null
}

/** Infobulle de l'en-tête « 12 mois préc. » : période comparée, et historique incomplet le cas échéant. */
const previousColumnTitle = computed(() => {
  if (!yearOverYear.value.available) return 'Pas encore d\'historique sur l\'année précédente : rien à comparer.'
  const title = `Mêmes 12 mois un an plus tôt (${previousPeriodLabel.value})`
  if (!yearOverYear.value.partial) return title
  const start = new Date(`${monthlyAnalysis.value.historyStart}T00:00:00`).toLocaleDateString('fr-FR')
  return `${title} — historique incomplet : vos données commencent le ${start}.`
})

/**
 * Évolution de chaque ligne des tableaux (clé : l'objet ligne, ou 'net' pour
 * le solde), calculée une fois par chargement plutôt qu'à chaque rendu.
 */
const yearOverYearChanges = computed(() => {
  const changes = new Map()
  const analysis = monthlyAnalysis.value
  if (!analysis || !yearOverYear.value.available) return changes
  for (const [groups, kind] of [[expenseGroups.value, 'expense'], [incomeGroups.value, 'income']]) {
    for (const group of groups) {
      changes.set(group, yearOverYearChange(group.total, group.previousTotal, kind))
      for (const child of group.children) changes.set(child, yearOverYearChange(child.total, child.previousTotal, kind))
    }
  }
  changes.set(analysis.incomeTotal, yearOverYearChange(analysis.incomeTotal.total, analysis.incomeTotal.previousTotal, 'income'))
  changes.set(analysis.expenseTotal, yearOverYearChange(analysis.expenseTotal.total, analysis.expenseTotal.previousTotal, 'expense'))
  changes.set('net', yearOverYearChange(netTotal.value, previousNetTotal.value, 'net'))
  return changes
})
/** Sans historique sur l'année précédente, aucune ligne n'a d'évolution (voir yearOverYearChanges). */
const NO_CHANGE = Object.freeze({ text: '—', tone: '', title: "Pas encore d'historique sur l'année précédente" })

/** Évolution affichée pour une ligne (voir yearOverYearChanges). */
function changeFor(row) {
  return yearOverYearChanges.value.get(row) ?? NO_CHANGE
}

/** Montant de la colonne « 12 mois préc. » (tiret sans historique en face). */
function previousAmountText(previous) {
  return yearOverYear.value.available ? formatAmount(previous) : '—'
}

/**
 * Attributs de la cellule « 12 mois préc. » : même comportement que les
 * autres montants (détail au survol, transactions au clic), sur la période
 * précédente.
 */
function previousCell(previous, flow, filters, label) {
  const analysis = monthlyAnalysis.value
  if (!analysis || !yearOverYear.value.available) return { class: 'zero-value' }
  const title = `${label} — ${previousPeriodLabel.value}`
  return {
    class: { 'zero-value': !Number(previous) },
    onMouseenter: (event) => scheduleHover(event, title,
      () => fetchCellTransactions(analysis.previousFrom, analysis.previousTo, flow, filters)),
    onMouseleave: onCellLeave,
    onClick: () => openTransactions(analysis.previousFrom, analysis.previousTo, flow, filters, title)
  }
}

/** Même mois un an plus tôt que celui des cartes du haut (voir loadMonthlyReports). */
const summaryYearAgo = ref(null)
const yearAgoMonthLabel = computed(() => {
  const date = new Date(viewedMonthStart.value)
  date.setFullYear(date.getFullYear() - 1)
  return date.toLocaleDateString('fr-FR', { month: 'short', year: 'numeric' })
})
/** Ligne « vs oct. 2025 : … » des cartes Revenus/Dépenses/Net (null si ce mois n'est pas couvert). */
const kpiYearAgo = computed(() => {
  const yearAgo = yearOverYear.value.available ? summaryYearAgo.value : null
  const label = yearAgoMonthLabel.value
  return {
    income: yearAgoKpi(summary.value.income, yearAgo?.income, 'income', label),
    expenses: yearAgoKpi(summary.value.expenses, yearAgo?.expenses, 'expense', label),
    net: yearAgoKpi(summary.value.net, yearAgo?.net, 'net', label)
  }
})

/**
 * Teinte d'une case de solde : vert si positif, corail si négatif, et
 * estompée comme les autres cases vides (.zero-value) si nulle — un mois sans
 * aucun mouvement n'est pas un « bon » mois.
 */
function balanceToneClass(value) {
  const amount = Number(value)
  if (!amount) return 'zero-value'
  return amount > 0 ? 'amount positive' : 'amount negative'
}

/**
 * Cases atypiques de chaque ligne des tableaux d'analyse (clé : l'objet ligne,
 * ou 'income'/'expenses'/'net' pour le résumé mensuel), calculées une fois par
 * chargement : le tableau se re-rend à chaque survol de case.
 */
const anomalyClasses = computed(() => {
  const classes = new Map()
  const analysis = monthlyAnalysis.value
  if (!analysis) return classes
  for (const [groups, higherIsBetter] of [[expenseGroups.value, false], [incomeGroups.value, true]]) {
    for (const group of groups) {
      classes.set(group, rowAnomalyClasses(group.values, higherIsBetter))
      for (const child of group.children) classes.set(child, rowAnomalyClasses(child.values, higherIsBetter))
    }
  }
  for (const [field, higherIsBetter] of [['income', true], ['expenses', false], ['net', true]]) {
    classes.set(field, rowAnomalyClasses(Object.fromEntries(analysis.summary.map(m => [m.key, m[field]])), higherIsBetter))
  }
  return classes
})
const NO_ANOMALY = Object.freeze({})

/** Classe d'anomalie d'une case (voir anomalyClasses). */
function anomalyFor(row, monthKey) {
  return anomalyClasses.value.get(row)?.[monthKey] ?? NO_ANOMALY
}

function toggleMonthlyGroup(type, key) {
  collapsedMonthlyGroups.value[type][key] = !collapsedMonthlyGroups.value[type][key]
}
function isMonthlyGroupCollapsed(type, key) {
  return !!collapsedMonthlyGroups.value[type][key]
}

/**
 * Tout part en même temps. Les rapports n'attendent plus le cache partagé :
 * les graphiques ne le lisent pas, et ce qui en dépend dans le template
 * (icônes de catégories, soldes, bandeau « À traiter ») se met à jour seul.
 * Les attendre faisait patienter le tableau de bord jusqu'à la fin du
 * téléchargement de toutes les transactions (1,6 Mo pour 5 000 lignes).
 */
/** Résolue au montage de la page : le dessin des graphiques a besoin de leurs canvas (voir loadDashboard). */
const pageMounted = new Promise(resolve => onMounted(resolve))

async function loadDashboard() {
  // Échec signalé par le bandeau « Serveur injoignable » d'App.vue.
  dataStore.ensureLoaded().catch(() => {})
  // "Dépenses périodiques" ne dépend pas du mois navigué (voir plus haut :
  // toujours la dernière occurrence connue, dans l'esprit "abonnement actif
  // ou non aujourd'hui"), donc chargé une seule fois ici plutôt que dans
  // loadMonthlyReports.
  ReportsAPI.periodicExpenses()
    .then(periodic => { periodicExpenses.value = periodic })
    .catch(e => alertApiError('Erreur lors du chargement du tableau de bord', e))
  loadDuplicateCount()

  await loadMonthlyReports()
}

// Jeton du dernier chargement mensuel lancé : en cliquant plusieurs fois
// vite sur les flèches, une réponse plus ancienne arrivée en dernier
// affichait sinon les chiffres d'un autre mois que celui du libellé.
let monthlyReportsToken = 0
/** Dernier chargement des rapports en échec : affiche un encadré « Réessayer » (le message d'erreur, lui, disparaît seul). */
const reportsLoadFailed = ref(false)

/**
 * Recharge tout ce qui dépend du mois navigué (viewedMonthStart, voir les
 * flèches du tableau de bord) : les 3 cartes du haut, le graphique de solde,
 * l'épargne mensuelle, "Où va mon argent ?" et les deux tableaux d'analyse
 * mensuelle par catégorie. Appelée au montage puis à chaque navigation
 * (goToPreviousMonth/goToNextMonth), pour que tout le tableau de bord se
 * décale ensemble sur le même mois.
 */
async function loadMonthlyReports() {
  const token = ++monthlyReportsToken
  monthLabel.value = monthLabelOf(viewedMonthStart.value)
  // "YYYY-MM-01" : même mois que les cartes du haut, transmis aux rapports
  // en fenêtre glissante de 12 mois pour qu'ils se terminent sur ce mois.
  const referenceMonth = `${viewedMonthStart.value.getFullYear()}-${String(viewedMonthStart.value.getMonth() + 1).padStart(2, '0')}-01`

  whereMoneyToken++ // une bascule mois / 12 mois encore en vol est remplacée par ce chargement
  whereMoneyRange.value = whereMoneyRangeFor(viewedMonthStart.value)
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
    if (token === monthlyReportsToken) {
      reportsLoadFailed.value = true
      alertApiError('Erreur lors du chargement des rapports', e)
    }
    return
  }
  if (token !== monthlyReportsToken) return
  reportsLoadFailed.value = false
  const [forecast, savings, whereMoney, monthly, { loansList, schedules }] = reports
  // Les 3 cartes du haut reprennent le dernier mois de monthlyAnalysis.summary
  // (toujours exactement le mois affiché, voir referenceMonth ci-dessus) plutôt
  // qu'un appel séparé à /reports/summary : ce mois-là est déjà prévisionnel
  // (moyenne des mois passes, voir ServiceRapport#completeAnalysisRow) quand on
  // navigue dans le futur, au lieu d'un total réel forcément à 0/quasi-0.
  summary.value = monthly.summary[monthly.summary.length - 1] || { income: 0, expenses: 0, net: 0 }
  // Même mois un an plus tôt : dernier mois de la fenêtre précédente (cartes du haut).
  summaryYearAgo.value = monthly.previousSummary?.[monthly.previousSummary.length - 1] ?? null
  whereMoneyData.value = whereMoney
  monthlyAnalysis.value = monthly
  hasLoans.value = loansList.length > 0
  monthlyTrend.value = Number(forecast.monthlyTrend)
  balanceSeries.value = forecast.points.filter(p => p.type !== 'previsionnel').map(p => Number(p.balance))

  await pageMounted
  await nextTick()
  if (token !== monthlyReportsToken) return
  renderForecastChart(forecast.points, buildLoanOwnedSeries(forecast.points, loansList, schedules))
  renderSavingsChart(savings)
  renderWhereMoneyChart(whereMoneyData.value)
}

/**
 * Couleur commune (--chart-forecast) à toutes les portions "previsionnel" des courbes du
 * tableau de bord : gris neutre, plutôt qu'une teinte plus claire de la
 * couleur "reelle" de chaque série (essaye d'abord, voir historique du
 * fichier) — à l'échelle fine d'une ligne de graphique, un simple
 * eclaircissement restait trop proche du réel pour se voir sans zoomer,
 * même pousse. Un gris franc, lui, se distingue d'un coup d'œil quelle que
 * soit la couleur "reelle" de la série (indigo du solde, ambre du capital
 * des prêts...), au prix d'une petite concession en mode "courbes
 * séparées" : les deux prévisionnels (solde et capital des prêts) partagent
 * alors ce même gris plutôt que de garder chacun leur teinte — mais restent
 * distinguables par leur trajectoire, et surtout par le trait plein de
 * même couleur juste avant, dont ils prolongent directement la courbe.
 */


/**
 * Construit, pour une série de valeurs, la paire de datasets Chart.js
 * "reel" (trait plein, couleur pleine) / "previsionnel" (pointillé, couleur
 * éclaircie) — même principe que le graphique "Évolution du capital restant
 * du" de la page Prêts (voir Loans.vue#renderChart) : la distinction
 * réel/prévisionnel passe par une couleur ET un trait différents, pas
 * seulement le trait comme avant ici (segment.borderDash sur un dataset
 * unique). Les deux tableaux de données se chevauchent EXACTEMENT sur
 * junctionIndex (même valeur des deux côtés, le reste à null + spanGaps:
 * false) pour que les deux segments se rejoignent visuellement sans coupure.
 * `future.isForecastVariant` sert à exclure ce second dataset de la légende
 * (voir plugins.legend.labels.filter plus bas) : une seule entrée par série
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
 * Affiche le solde sur 12 mois historiques (déjà terminés) + 12 mois
 * prévisionnels à partir du mois en cours inclus, en couleur éclaircie et
 * pointillé à partir du mois en cours (voir pastFutureDatasets) pour
 * distinguer visuellement le prévisionnel du réel. Si l'utilisateur a des
 * prêts, deux modes sont possibles (voir chartDisplayMode, bascule via
 * toggleChartMode) : 'combined' additionné le capital déjà remboursé (voir
 * buildLoanOwnedSeries) au solde en une seule courbe (comportement
 * historique), 'separate' affiche les deux courbes indépendamment. Sans
 * prêt, la courbe correspond simplement au solde quel que soit le mode.
 */
function renderForecastChart(points, loanOwnedSeries) {
  lastForecastPoints = points
  lastLoanOwnedSeries = loanOwnedSeries

  const firstForecastIndex = points.findIndex(p => p.type === 'previsionnel')
  // Si aucun point prévisionnel n'est visible (fenêtre entièrement passée,
  // navigation loin en arrière), pas de jonction : toute la courbe reste
  // pleine plutôt que de tomber sur un index négatif qui pointillerait tout.
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
        // Détail solde / capital prêts / total même si une seule courbe est
        // affichée (voir "data" ci-dessus) : les deux composantes de la somme
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
          // Une seule entrée de légende par série logique (Solde, Capital
          // des prêts), pas une par dataset Chart.js : le dataset
          // "previsionnel" (voir pastFutureDatasets) est exclu.
          labels: {
            usePointStyle: true,
            pointStyle: 'circle',
            boxWidth: 8,
            boxHeight: 8,
            padding: 18,
            color: colors.legend,
            font: { family: CHART_FONT_FAMILY, size: 11, weight: '600' },
            filter: (legendItem, data) => !data.datasets[legendItem.datasetIndex]?.isForecastVariant
          }
        },
        tooltip: tooltipOptions(colors, {
          displayColors: true,
          // Au point de jonction, le dataset "previsionnel" porte
          // volontairement la même valeur que le dataset "reel" (voir
          // pastFutureDatasets, pour que les deux segments se rejoignent
          // visuellement) : sans ce filtre, le survol de ce point précis
          // affichait la même ligne deux fois (une par dataset). On exclut
          // donc la ligne du dataset "previsionnel" à cet index uniquement —
          // le réel, déjà identique à cet endroit précis, reste affiché seul.
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
 * Barres épargne mensuelle : couleur d'accent du thème (dégradé) si positif
 * — l'épargne n'est pas un revenu, d'où le bleu plutôt que le vert —, rouge
 * si négatif (on a puisé dans le solde), gris (--chart-forecast, même teinte que le
 * prévisionnel du graphique de solde et des tableaux) pour un mois pas
 * encore terminé (p.forecast, voir ServiceRapport#monthlySavings), peu
 * importe son signe : ce n'est jamais une vraie valeur constatée.
 */
function renderSavingsChart(points) {
  lastSavingsPoints = points
  const colors = readChartColors()
  const savedFill = barGradient(colors.accent)
  const barFill = (ctx) => {
    const point = points[ctx.dataIndex]
    if (point.forecast) return colors.forecast
    return Number(point.savings) >= 0 ? savedFill(ctx) : colors.negative
  }
  if (savingsChartInstance) savingsChartInstance.destroy()
  savingsChartInstance = new Chart(savingsChart.value, {
    type: 'bar',
    data: {
      labels: points.map(p => p.label),
      datasets: [{
        label: 'Épargne',
        data: points.map(p => p.savings),
        backgroundColor: barFill,
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
      // Clic sur une barre : les transactions du mois (revenus et dépenses), comme les tableaux mensuels.
      onClick: (_event, elements) => {
        const point = elements.length ? points[elements[0].index] : null
        if (point) openMonthTransactions(point.date, null, {}, `Épargne — ${monthLabelFor(point.date)}`, point.forecast)
      },
      onHover: (event, elements) => {
        const clickable = elements.length && !points[elements[0].index].forecast
        event.native.target.style.cursor = clickable ? 'pointer' : 'default'
      },
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
 * Donut dédié à "Où va mon argent ?" : pas de légende Chart.js interne (le
 * tableau à droite sert déjà de légende détaillée), anneau plus fin pour un
 * rendu plus moderne, et un total affiché au centre (overlay CSS, voir
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
        backgroundColor: data.map((_, i) => legendColor(i)),
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
      // Clic sur une part : mêmes transactions qu'un clic sur sa ligne du tableau.
      onClick: (_event, elements) => {
        const row = elements.length ? data[elements[0].index] : null
        if (row) openTransactions(whereMoneyRange.value.from, whereMoneyRange.value.to, 'expense', whereMoneyFilter(row), `${row.category} — ${whereMoneyPeriodLabel.value}`)
      },
      onHover: (event, elements) => {
        event.native.target.style.cursor = elements.length ? 'pointer' : 'default'
      },
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

/**
 * ← / → : mois précédent / suivant, comme les flèches ‹ › de l'en-tête (voir
 * monthStepFromKey). Ignoré pendant qu'une fenêtre ou la palette est ouverte.
 */
function onMonthKeydown(event) {
  const step = monthStepFromKey(event)
  if (!step || document.querySelector('.modal-overlay, .feedback-overlay, .palette-overlay')) return
  event.preventDefault()
  if (step < 0) goToPreviousMonth()
  else goToNextMonth()
}
onMounted(() => window.addEventListener('keydown', onMonthKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onMonthKeydown))

/**
 * Nettoyage en quittant le tableau de bord : sans lui, chaque visite laissait
 * 3 instances Chart.js vivantes (registre interne + observateurs de
 * redimensionnement sur des canvas detaches), les minuteurs du popover
 * pouvaient se declencher après coup, et un chargement encore en vol tentait
 * de dessiner sur des canvas qui n'existent plus.
 */
onBeforeUnmount(() => {
  monthlyReportsToken++
  whereMoneyToken++
  requestToken++
  clearTimeout(showTimer)
  clearTimeout(hideTimer)
  for (const chart of [forecastChartInstance, savingsChartInstance, whereMoneyChart]) chart?.destroy()
  forecastChartInstance = null
  savingsChartInstance = null
  whereMoneyChart = null
})

// Actions lancées depuis la palette de commandes (Ctrl+K), voir services/routeActions.js.
useRouteActions({ 'export-pdf': exportDashboard })

// Données demandées dès la création de la page, en fin de setup (tout est
// déclaré), sans attendre son premier rendu : ~120 ms plus tôt (mesuré).
// Seul le dessin des graphiques attend le montage (voir pageMounted).
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

/* --- Montants cliquables : ouvrent la liste des transactions correspondantes --- */
.monthly-analysis-table td.num:not(.forecast-cell),
.drillable-row { cursor: pointer; }
.monthly-analysis-table td.num:not(.forecast-cell):hover { text-decoration: underline; text-underline-offset: 3px; }
.drillable-row:hover td { background: var(--ink-800); }

/* --- Chiffres clés : variation + courbe miniature --- */
/* Taux d'épargne sous le solde du mois. */
.kpi-rate { margin-top: 4px; font-size: 12.5px; color: var(--text-muted); font-variant-numeric: tabular-nums; }
/* Même mois un an plus tôt (voir kpiYearAgo) : teinte selon le sens de l'écart. */
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

.card-title { font-size: 15px; font-weight: 600; letter-spacing: -0.01em; color: var(--text-primary); margin-bottom: 10px; }
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

/* Titre suivi d'une ligne d'explication (.card-subtitle, AnalysisLegend). */
.card-title.has-subtitle { margin-bottom: 4px; }
.card-subtitle { color: var(--text-muted); font-size: 12px; margin-bottom: 12px; }
.chart-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-faint);
  line-height: 1.65;
  max-width: 1050px;
}
.chart-header-title { display: inline-flex; align-items: center; gap: 6px; margin-bottom: 0; }
/* Bouton ⓘ qui déplie l'explication du prévisionnel. */
.chart-info-btn {
  display: inline-flex;
  padding: 3px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--text-faint);
  cursor: pointer;
}
.chart-info-btn:hover,
.chart-info-btn.active { color: var(--azure); background: var(--azure-dim); }

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
.chart-mode-toggle.is-on .toggle-track {
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
.chart-mode-toggle.is-on .toggle-thumb {
  transform: translateX(16px);
}
.toggle-label {
  font-weight: 600;
  white-space: nowrap;
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
  /* Même réserve de barre de défilement et même marge droite que la liste
     au-dessus (.periodic-table-wrap, td.num) : le total s'aligne sur les montants. */
  padding-right: 6px;
  overflow: hidden;
  scrollbar-gutter: stable;
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
.where-money-card {
  padding: 20px 22px;
  margin-bottom: 28px;
}
.where-money-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
/* Bascule mois affiché / 12 mois de « Où va mon argent ? ». */
.scope-toggle {
  display: inline-flex;
  flex-shrink: 0;
  padding: 2px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--ink-800);
}
.scope-option {
  padding: 3px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.scope-option:hover { color: var(--text-primary); }
.scope-option.active { background: var(--ink-900); color: var(--text-primary); box-shadow: var(--shadow-xs); }

.where-money-layout {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  gap: 32px;
  align-items: center;
}
@media (max-width: 640px) {
  .where-money-card { padding: 16px; }
  .where-money-layout {
    grid-template-columns: 1fr;
    gap: 16px;
    justify-items: center;
  }
  .donut-wrap { width: 164px; height: 164px; }
  /* Sur mobile, la barre de proportion prendrait la place du libellé. */
  .where-share-col { display: none; }
}

.donut-wrap {
  position: relative;
  width: 176px;
  height: 176px;
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
  letter-spacing: -.035em;
  /* Rend visible l'espace fine des milliers (« 5 065 € » et non « 5065 € »). */
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
  line-height: 1.2;
}
.donut-center-label {
  font-size: 11px;
  letter-spacing: .04em;
  color: var(--text-faint);
  margin-top: 3px;
}

/* En-tête (25 px) + 5 lignes entières (35,75 px) : la coupure tombe entre
   deux lignes. À 190 px elle passait sous la ligne de base de la 5e ligne et
   amputait la queue des virgules (« 199,00 € » se lisait « 199.00 € »). */
.where-table-wrap {
  max-height: 204px;
  overflow-y: auto;
}
.where-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.where-table th {
  position: sticky;
  top: 0;
  background: var(--ink-900);
  text-align: left;
  padding: 0 8px 9px;
  font-size: 10px;
  text-transform: none;
  letter-spacing: .06em;
  color: var(--text-faint);
  font-weight: 700;
}
/* En-têtes « Montant » et « % » alignés à droite comme leurs chiffres */
.where-table th.num {
  text-align: right;
}
.where-table td {
  padding: 8px;
  border-bottom: 1px solid var(--grid-line);
}
.where-table tbody tr:hover td {
  background: var(--row-hover);
}
/* Barre de proportion : occupe l'espace libre entre le libellé et le montant,
   plafonnée pour rester lisible d'un coup d'œil sur grand écran. */
.where-share-col {
  width: 34%;
  max-width: 320px;
}
.where-share-track {
  display: block;
  height: 6px;
  border-radius: 999px;
  background: var(--ink-700);
  overflow: hidden;
}
.where-share-fill {
  display: block;
  height: 100%;
  min-width: 2px;
  border-radius: inherit;
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
  /* 6px lateraux (et non 8) : 15 colonnes, les 12 mois + catégorie +
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
  /* Ligne de séparation nette entre l'en-tête figé et le contenu qui défile
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

/* Montants à 0 dans les tableaux "Revenus/Depenses par categorie" : moins
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
 * Mois pas encore terminé (à partir du mois calendaire en cours) : rempli
 * côté backend avec le prévisionnel (moyenne des mois passés de la même
 * ligne, voir ServiceRapport#completeAnalysisRow) plutôt qu'un 0 trompeur.
 * Italique + teinte atténuée (--text-muted, proche du gris prévisionnel du graphique)
 * pour que ce ne soit jamais confondu avec une
 * vraie valeur constatée, sans pour autant la faire disparaître comme
 * .zero-value (c'est une estimation utile, pas une absence de donnée) — d'ou
 * la règle placée après .zero-value pour la primer en cas de cumul (un
 * prévisionnel qui tombe pile à 0 doit rester visible comme prévisionnel).
 */
.monthly-analysis-table .num.forecast-cell,
.monthly-analysis-table th.forecast-cell {
  color: var(--text-muted);
  font-style: italic;
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

/*
 * Case dont la valeur s'ecarte fortement (voir anomalyClass/ANOMALY_STDDEV_THRESHOLD)
 * de la moyenne des 12 mois de sa ligne : liseret corail (vert si l'écart est
 * favorable, voir anomaly-favorable juste en dessous) pour la repérer d'un
 * coup d'œil au milieu du tableau — box-shadow inset plutôt qu'une bordure
 * classique pour ne pas décaler la mise en page des cellules voisines (compte
 * pour l'élément sans agrandir sa boîte). Pas de fond coloré ici : perdrait
 * en visibilité sur les lignes à fond alterné (category-group-alt ci-dessous,
 * de spécificité CSS supérieure sur cette propriété).
 */
.monthly-analysis-table .num.monthly-anomaly {
  box-shadow: inset 0 0 0 2px var(--coral);
  color: var(--coral);
  font-weight: 700;
}
/* Légende des encadrés, dans le sous-titre des tableaux */
/* Écart dans le bon sens (revenu exceptionnel, dépense au plus bas) : vert */
.monthly-analysis-table .num.monthly-anomaly.anomaly-favorable {
  box-shadow: inset 0 0 0 2px var(--mint);
  color: var(--mint);
}

/*
 * "Moyenne"/"Total" : colonnes d'agregat distinguees du détail mensuel par
 * un fond gris et une bordure épaisse à gauche (repère visuel net avec 12
 * colonnes de mois entre elles et la catégorie). Sélecteur à DEUX classes
 * (".num.summary-col", combinaison présente sur chaque cellule concernée,
 * voir le template) plutôt qu'une seule (".summary-col") : une règle à une
 * seule classe à spécificité CSS égale à ".category-row td" en nombre de
 * classes (une chacune), donc c'est le nombre de balises (3 pour "tbody tr td")
 * qui tranchait alors en faveur du fond blanc de la ligne — à tort, ça
 * écrasait le gris voulu sur cette colonne dans les lignes de catégorie
 * racine. Deux classes ici suffit à l'emporter d'entrée, quelle que soit la
 * ligne.
 */
.monthly-analysis-table .num.summary-col {
  background: var(--ink-800);
  font-weight: 600;
}
.monthly-analysis-table .col-average {
  border-left: 2px solid var(--line);
}
/* « Évol. » : couleur selon le sens de l'écart (voir yearOverYearChange). */
.monthly-analysis-table .num.col-change.good { color: var(--mint); }
.monthly-analysis-table .num.col-change.bad { color: var(--coral); }
/*
 * Ligne absente l'an dernier (●) ou tombée à zéro (○) : paire de marqueurs
 * discrets, détail en infobulle. Dessinés en CSS (deux glyphes n'auraient pas
 * la même taille selon la police) ; le texte de la case, masqué par
 * font-size: 0, est le libellé lu par les lecteurs d'écran.
 */
.monthly-analysis-table .num.col-change.new,
.monthly-analysis-table .num.col-change.gone { color: var(--text-faint); font-size: 0; cursor: help; }
.monthly-analysis-table .num.col-change.new::after,
.monthly-analysis-table .num.col-change.gone::after {
  content: '';
  display: inline-block;
  width: 7px;
  height: 7px;
  box-sizing: border-box;
  border: 1.5px solid currentColor;
  border-radius: 50%;
}
.monthly-analysis-table .num.col-change.new::after { background: currentColor; }
/* Historique incomplet sur l'année précédente (voir previousColumnTitle). */
.yoy-partial { margin-left: 2px; color: var(--amber); }
/*
 * Colonnes de synthèse (Moyenne, Total, 12 mois préc., Évol.) visibles en
 * permanence pendant le défilement horizontal, même avec 12 mois affichés
 * entre la catégorie et elles : c'était le principal reproche de lisibilité
 * sur ces tableaux (il fallait défiler jusqu'au bout pour comparer les
 * lignes). Figees ensemble : une colonne figée seule laissait passer sa
 * voisine dessous, montant masqué dès l'affichage. Largeurs fixes = décalages
 * des colonnes suivantes. Classes explicites (col-*) plutôt que :last-child,
 * pour qu'une colonne ajoutée ne décale pas tout. Même principe que la
 * colonne Actions de l'onglet Transactions. Pas de figeage sur petit écran :
 * ~370 px figes ne laisseraient plus voir aucun mois sur un téléphone.
 */
.monthly-analysis-table {
  --col-change-width: 70px;
  --col-previous-width: 100px;
  --summary-total-width: 100px;
}
.monthly-analysis-table .col-change { width: var(--col-change-width); min-width: var(--col-change-width); }
.monthly-analysis-table .col-previous { width: var(--col-previous-width); min-width: var(--col-previous-width); }
.monthly-analysis-table .col-total { width: var(--summary-total-width); min-width: var(--summary-total-width); }
@media (min-width: 1100px) {
  .monthly-analysis-table .col-change { position: sticky; right: 0; }
  .monthly-analysis-table .col-previous { position: sticky; right: var(--col-change-width); }
  .monthly-analysis-table .col-total {
    position: sticky;
    right: calc(var(--col-change-width) + var(--col-previous-width));
  }
  .monthly-analysis-table .col-average {
    position: sticky;
    right: calc(var(--col-change-width) + var(--col-previous-width) + var(--summary-total-width));
    box-shadow: var(--sticky-edge-shadow);
  }
  .monthly-analysis-table th.col-average,
  .monthly-analysis-table th.col-total,
  .monthly-analysis-table th.col-previous,
  .monthly-analysis-table th.col-change {
    z-index: 7;
  }
}

.monthly-analysis-table tbody tr.category-row td {
  background: var(--ink-900);
  font-weight: 600;
}

/* Alternance de fond par groupe catégorie + sous-catégories (voir
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
  display: inline-flex;
  align-items: center;
  justify-content: center;
  vertical-align: middle;
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
/* Téléphone : zones de toucher agrandies sans changer le dessin. */
@media (max-width: 640px) {
  .chart-info-btn,
  .collapse-button,
  .scope-option { position: relative; }
  .chart-info-btn::after,
  .collapse-button::after { content: ''; position: absolute; inset: -5px; }
  .scope-option::after { content: ''; position: absolute; inset: -5px 0; }
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
  /* Une seule ligne sur toute la largeur : flèches aux bords, mois au centre
     (sans largeur minimale, qui renvoyait l'export à la ligne), export à droite. */
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
  .export-btn { flex-shrink: 0; }
  /* Même écart sous l'en-tête que les autres pages en mobile. */
  .dashboard-page > .page-header { margin-bottom: 20px; }
}

/* Le position:fixed de .month-nav se répète sur chaque page imprimée et
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

/* .monthly-analysis-wrap plafonne la hauteur à 720px avec défilement pour
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
  /* Colonnes de synthèse : leurs règles écran (col-*) sont plus spécifiques
     que le sélecteur ci-dessus et les laissaient collées à droite, par-dessus
     les mois les plus récents, qui disparaissaient du PDF. */
  .monthly-analysis-table .col-average,
  .monthly-analysis-table .col-total,
  .monthly-analysis-table .col-previous,
  .monthly-analysis-table .col-change {
    position: static;
    width: auto;
    min-width: 0;
    box-shadow: none;
  }
  /* Comparaison annuelle : seule l'évolution figure dans le PDF, pour que
     le tableau tienne dans la largeur d'une page A4 paysage. */
  .monthly-analysis-table .col-previous { display: none; }
  /* Tenir dans la largeur d'une page A4 paysage (≈ 1 060 px) : les largeurs
     minimales écran (catégorie 160 px + 15 colonnes de 68 px) et
     width: max-content faisaient déborder le tableau hors de la page. 10 px
     (et non 10,5) depuis la colonne « Évol. » : le tableau des dépenses
     dépassait sinon de ~20 px (mesuré). */
  .monthly-analysis-table {
    width: 100%;
    font-size: 10px;
  }
  .monthly-analysis-table .num,
  .monthly-analysis-table .category-sticky {
    min-width: 0;
  }
  /* Boutons replier/déplier : sans objet sur papier (tout est déplié). */
  .monthly-analysis-table .collapse-button,
  .monthly-analysis-table .collapse-placeholder {
    display: none;
  }
  /* Interrupteur et bouton d'aide du graphique de solde remplacés par une
     légende fixe (.chart-print-caption) ; sélecteur Mois / 12 mois de
     « Où va mon argent ? » réduit à l'option affichée ; flèches des liens
     « À traiter » retirées (non cliquables sur papier). */
  .chart-mode-toggle,
  .chart-info-btn,
  .scope-option:not(.active),
  .todo-banner-item .icon {
    display: none;
  }
  .scope-toggle {
    border: none;
    background: none;
  }
  .chart-print-caption {
    font-size: 12px;
    font-weight: 600;
    color: var(--text-muted);
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
  .periodic-grid {
    margin-bottom: 28px;
  }
}

/* .compact-card et .periodic-table-wrap réduisent la place à l'écran
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


/* Ajustements finaux après les règles historiques du composant. */
.chart-card {
  border: 1px solid var(--line);
  box-shadow: var(--shadow-card);
}
.forecast-card { padding: 22px 24px 18px; margin-bottom: 20px; }
.forecast-card > canvas { max-height: 310px; }
.chart-note { line-height: 1.65; max-width: 1050px; }
.monthly-analysis-wrap { border-color: var(--line); box-shadow: var(--shadow-sm); }
.monthly-analysis-table { font-variant-numeric: tabular-nums; }
.monthly-analysis-table thead th { padding-top: 11px; padding-bottom: 11px; background: var(--table-head-bg); color: var(--table-head-text); }
/* Cellules figées de l'en-tête : même bandeau que le reste du thead. */
.monthly-analysis-table thead th.category-sticky,
.monthly-analysis-table thead th.num.summary-col { background: var(--table-head-bg); }
.monthly-analysis-table tbody tr:hover td { background-color: var(--row-hover); }
.monthly-analysis-table .category-sticky { font-weight: 600; }
@media (max-width: 640px) {
  .forecast-card { padding: 16px; }
}

</style>

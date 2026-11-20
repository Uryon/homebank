<template>
  <div class="periodic-grid">
    <div v-for="section in sections" :key="section.key" class="card compact-card" :class="{ 'section-collapsed': section.collapsed }">
      <div class="card-title"><SectionToggle :section="section.sectionId" :label="section.title" />{{ section.title }}</div>
      <div class="section-body">
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
                    @mouseenter="scheduleHover($event, item.payee || 'Sans nom', () => itemRows(item, section.sign))"
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
                @mouseenter="scheduleHover($event, section.title, () => groupRows(section.group, section.sign))"
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
</template>

<script setup>
/**
 * Cartes « Dépenses périodiques » et « Revenus périodiques » : dernière
 * occurrence de chaque transaction marquée périodique, par tiers (voir
 * ServiceRapport#periodicExpensesSummary). Indépendantes du mois affiché.
 */
import { computed } from 'vue'
import { formatAmount } from '../../services/format'
import { useCellDetail } from '../../services/cellDetail'
import { useCollapsibleSection } from '../../services/collapsibleSections'
import SectionToggle from './SectionToggle.vue'

const props = defineProps({
  /** { expenses: { items, total }, revenues: { items, total } }, montants en valeur absolue. */
  periodic: { type: Object, required: true }
})

const { scheduleHover, onCellLeave, openPayeeTransactions } = useCellDetail()

/** Les deux cartes, rendues par le même gabarit (sign : signe des montants affichés). */
const expensesSection = useCollapsibleSection('periodiques-depenses')
const revenuesSection = useCollapsibleSection('periodiques-revenus')

const sections = computed(() => [
  { key: 'expenses', sectionId: 'periodiques-depenses', collapsed: expensesSection.collapsed.value, title: 'Dépenses périodiques', group: props.periodic.expenses, sign: -1, tone: 'negative', emptyLabel: 'Aucune dépense périodique marquée.', examples: 'loyer, abonnement…' },
  { key: 'revenues', sectionId: 'periodiques-revenus', collapsed: revenuesSection.collapsed.value, title: 'Revenus périodiques', group: props.periodic.revenues, sign: 1, tone: 'positive', emptyLabel: 'Aucun revenu périodique marqué.', examples: 'salaire, remboursement régulier…' }
])

/**
 * Lignes du popover : les données sont déjà chargées, pas d'appel réseau.
 * `sign` remet le signe (montants stockés en valeur absolue côté backend).
 */
function itemRow(item, sign) {
  return { id: item.transactionId, date: item.date, payee: item.payee, amount: sign * Number(item.amount) }
}
function itemRows(item, sign) {
  return Promise.resolve([itemRow(item, sign)])
}
function groupRows(group, sign) {
  return Promise.resolve(group.items.map(item => itemRow(item, sign)))
}
</script>

<style scoped>
.periodic-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
@media (max-width: 860px) {
  .periodic-grid { grid-template-columns: 1fr; }
}

/* Au-delà de quelques tiers, la liste défile plutôt que d'agrandir la carte.
   scrollbar-gutter réserve la place de la barre même absente : les montants
   ne sautent pas selon le nombre de lignes. */
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
/* Montant décalé de la bordure droite : sinon collé à la barre de défilement. */
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
  /* Même réserve de barre de défilement et même marge droite que la liste :
     le total s'aligne sur les montants. */
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

@media print {
  .periodic-grid {
    margin-bottom: 28px;
  }
  /* Pas de barre de défilement sur papier : liste entière. */
  .periodic-table-wrap {
    max-height: none;
    overflow: visible;
  }
}
</style>

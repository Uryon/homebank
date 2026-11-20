/**
 * Passage du tableau de bord à la liste des transactions (« drill-down ») :
 * un clic sur un montant du tableau de bord ouvre la page Transactions
 * filtrée sur les mêmes critères que ce montant (période, sens, catégorie).
 *
 * Les critères voyagent dans l'adresse (?from=&to=&flow=&category=…) pour
 * qu'un retour arrière ou un rechargement retrouve la même liste.
 */

import { toLocalIsoDate } from './format'

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
const FLOWS = new Set(['income', 'expense'])

/**
 * Paramètres d'adresse à passer à /transactions. `filters` reprend le format
 * des filtres de catégorie du tableau de bord ({ categoryId }, { rootCategoryId }
 * ou { uncategorized: true }) ; `label` décrit la sélection dans la pastille
 * affichée au-dessus de la liste (« Alimentation · juin 2026 »).
 */
export function drillDownQuery({ from, to, flow = null, filters = {}, label = '' }) {
  const query = { from, to, label }
  if (flow) query.flow = flow
  if (filters.uncategorized) query.uncategorized = '1'
  else if (filters.categoryId != null) query.category = String(filters.categoryId)
  else if (filters.rootCategoryId != null) query.rootCategory = String(filters.rootCategoryId)
  return query
}

function positiveId(value) {
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : null
}

/**
 * Critères lus dans l'adresse de la page Transactions, ou null s'il n'y a pas
 * de période valide (un lien incomplet n'applique aucun filtre plutôt qu'un
 * filtre à moitié). « Sans catégorie » reste géré par le filtre existant
 * de la page (?uncategorized=1).
 */
export function parseDrillDown(query) {
  const from = query?.from
  const to = query?.to
  if (!ISO_DATE.test(from || '') || !ISO_DATE.test(to || '') || from > to) return null
  return {
    from,
    to,
    flow: FLOWS.has(query.flow) ? query.flow : null,
    categoryId: positiveId(query.category),
    rootCategoryId: positiveId(query.rootCategory),
    label: typeof query.label === 'string' && query.label.trim() ? query.label.trim() : `Du ${from} au ${to}`
  }
}

/** Clés d'adresse propres au drill-down, retirées quand on efface le filtre. */
export const DRILL_DOWN_QUERY_KEYS = ['from', 'to', 'flow', 'category', 'rootCategory', 'label', 'uncategorized']

/**
 * La transaction correspond-elle aux critères ? Mêmes règles que les montants
 * du tableau de bord (voir AttributionTransactions#attributedTransactions et
 * #cellTransactions) : virements, ajustements de solde, transactions
 * exceptionnelles et transactions futures exclus ; sens donné par le signe ;
 * catégorie exacte ou catégorie racine avec ses sous-catégories.
 * `categoriesById` sert à retrouver le parent.
 */
export function matchesDrillDown(transaction, drill, categoriesById, today = toLocalIsoDate()) {
  if (!countsInPeriod(transaction, drill, today)) return false
  const amount = Number(transaction.amount)
  if (drill.flow === 'income' && !(amount > 0)) return false
  if (drill.flow === 'expense' && !(amount < 0)) return false
  return matchesCategory(transaction, drill, categoriesById)
}

function countsInPeriod(transaction, drill, today) {
  const date = transaction.date
  if (!date || date < drill.from || date > drill.to || date > today) return false
  return !(transaction.transferTransactionId || transaction.balanceAdjustment || transaction.exceptional)
}

function matchesCategory(transaction, drill, categoriesById) {
  if (drill.categoryId != null) return transaction.categoryId === drill.categoryId
  if (drill.rootCategoryId != null) {
    if (transaction.categoryId == null) return false
    if (transaction.categoryId === drill.rootCategoryId) return true
    return categoriesById.get(transaction.categoryId)?.parentId === drill.rootCategoryId
  }
  return true
}

/**
 * Filtre de la liste ouverte depuis un montant du tableau de bord. Comme ce
 * montant (voir AttributionTransactions#netByCategoryMonth), le sens se lit sur le net
 * de la catégorie dans le mois : un remboursement apparaît avec la dépense
 * dont il est déduit, et le total de la liste retombe sur le montant cliqué.
 */
export function drillDownMatcher(transactions, drill, categoriesById, today = toLocalIsoDate()) {
  if (!drill.flow) return t => matchesDrillDown(t, drill, categoriesById, today)
  const categoryMonth = t => `${t.categoryId ?? 'aucune'}|${t.date.slice(0, 7)}`
  const netCents = new Map()
  for (const t of transactions) {
    if (!countsInPeriod(t, drill, today)) continue
    const key = categoryMonth(t)
    netCents.set(key, (netCents.get(key) ?? 0) + Math.round(Number(t.amount) * 100))
  }
  const wantedSign = drill.flow === 'income' ? 1 : -1
  return t => countsInPeriod(t, drill, today)
    && Math.sign(netCents.get(categoryMonth(t))) === wantedSign
    && matchesCategory(t, drill, categoriesById)
}

/**
 * Vrai si la transaction appartient à une catégorie « annuelle » : le tableau
 * de bord la lisse sur 12 mois (1/12 par mois), alors que la liste montre son
 * montant réel. La page Transactions le signale pour expliquer l'écart de total.
 */
export function isSmoothedInReports(transaction, categoriesById) {
  return !!categoriesById.get(transaction.categoryId)?.annualized
}

/** Premier jour du mois, `months` mois avant la date ISO donnée. */
function monthStartBefore(isoDate, months) {
  const [year, month] = isoDate.split('-').map(Number)
  return toLocalIsoDate(new Date(year, month - 1 - months, 1))
}

/**
 * Vrai si une transaction de catégorie annuelle contribue au montant du
 * tableau de bord pour ces critères : lissée sur ses 12 mois, elle y compte
 * pour 1/12 même si elle est datée jusqu'à 11 mois avant la période (et donc
 * absente de la liste). Sert à expliquer l'écart entre les deux totaux.
 */
export function hasSmoothedContribution(transactions, drill, categoriesById, today = toLocalIsoDate()) {
  const widened = { ...drill, from: monthStartBefore(drill.from, 11) }
  return transactions.some(t => isSmoothedInReports(t, categoriesById) && matchesDrillDown(t, widened, categoriesById, today))
}

import { formatAmount } from './format'
import { needsCategory } from './categories'
import { matchesAmountQuery, matchesSearchTerm, normalizeSearch, parseSearchTerms } from './search'

/**
 * Filtrage de la page Transactions, en trois étapes mémoïsées séparément par
 * la page (computed) pour que la frappe dans la recherche ne refasse que la
 * dernière :
 * 1. buildSearchIndex : champs de recherche normalisés de chaque transaction,
 *    recalculés seulement quand la liste change (normaliser et formater des
 *    milliers de montants à chaque caractère tapé avait rendu la recherche
 *    lente, voir CLAUDE.md) ;
 * 2. filterByStatusAndPeriod : statut, catégorie manquante, critères du
 *    tableau de bord, mois / année — indépendant de la recherche ;
 * 3. filterBySearch : recherche texte ou comparaison de montant, sur des
 *    chaînes déjà normalisées.
 */

/**
 * Index id -> champs normalisés (tiers, catégorie, mémo, libellé du relevé,
 * montants). `categoryLabelById` : libellé complet « Parent : Enfant » ;
 * `categoryNameById` : nom seul, pour la recherche exacte entre guillemets.
 */
export function buildSearchIndex(transactions, categoryLabelById, categoryNameById) {
  const index = new Map()
  for (const t of transactions) {
    index.set(t.id, {
      payeeNormalized: normalizeSearch(t.payee),
      categoryNormalized: normalizeSearch(categoryLabelById.get(t.categoryId) || ''),
      categoryNameNormalized: normalizeSearch(categoryNameById.get(t.categoryId) || ''),
      memoNormalized: normalizeSearch(t.memo),
      bankLabelNormalized: normalizeSearch(t.bankLabel),
      rawAmount: String(t.amount ?? '').toLowerCase(),
      absAmount: String(Math.abs(t.amount ?? 0)),
      // Montant formaté (« 0,60 € ») avec un point décimal : « 0,60 » et
      // « 0.60 » trouvent tous deux 0,60 € (rawAmount vaut « 0.6 »).
      amountFormattedNormalized: normalizeSearch(formatAmount(t.amount)).replace(',', '.')
    })
  }
  return index
}

/**
 * Filtres de statut et de période. `drillDownMatch` : prédicat des critères
 * reçus du tableau de bord (voir services/drillDown.js), null sans eux.
 */
export function filterByStatusAndPeriod(transactions, { pendingOnly, uncategorizedOnly, drillDownMatch, month, year }) {
  let filtered = pendingOnly ? transactions.filter(t => !t.validated) : transactions
  if (uncategorizedOnly) filtered = filtered.filter(needsCategory)
  if (drillDownMatch) filtered = filtered.filter(drillDownMatch)
  if (month == null && year == null) return filtered
  return filtered.filter(t => {
    if (!t.date) return false
    if (year != null && Number(t.date.slice(0, 4)) !== year) return false
    if (month != null && Number(t.date.slice(5, 7)) !== month) return false
    return true
  })
}

/**
 * Recherche : comparaison de montant (« > 100 », voir parseAmountQuery) sur
 * le montant signé, sinon critères séparés par « ; » tous exigés (ET), chacun
 * « contient » sur tiers / catégorie / mémo / libellé / montant, ou exact
 * entre guillemets (voir services/search.js).
 */
export function filterBySearch(transactions, query, amountQuery, searchIndex) {
  if (amountQuery) return transactions.filter(t => matchesAmountQuery(t, amountQuery))
  const terms = parseSearchTerms(query)
  if (!terms.length) return transactions
  return transactions.filter(t => {
    const fields = searchIndex.get(t.id)
    return fields && terms.every(criterion => matchesSearchTerm(fields, criterion))
  })
}

import { pluralize } from './format'

/** Ce que le nettoyage fera d'un faux tiers, tel qu'annoncé dans l'aperçu. */
export function cleanupOutcome(item) {
  if (item.willBeDeleted) return 'Le tiers sera supprimé.'
  if (item.otherTransactions > 0) {
    return `Tiers conservé : encore utilisé par ${pluralize(item.otherTransactions, 'autre transaction', 'autres transactions')}.`
  }
  return 'Tiers conservé : il a une catégorie, un montant ou un motif personnalisé.'
}

/** Nombre de transactions détachées par les tiers cochés. */
export function selectedTransactionCount(items, selectedIds) {
  const selected = new Set(selectedIds)
  return items.reduce((total, item) => total + (selected.has(item.payeeId) ? item.transactionsToDetach : 0), 0)
}

/**
 * Tri de la liste des transactions par clic sur un en-tête de colonne
 * (Date, Tiers, Dépense, Revenu). Recliquer la même colonne inverse le sens
 * (voir nextSort dans payeeUsage.js, partagé avec la page Tiers).
 *
 * L'ordre par défaut (date décroissante) est celui de la liste reçue : elle
 * est renvoyée telle quelle, sans copie ni tri, pour ne rien coûter dans le
 * cas courant et garder exactement l'ordre habituel.
 */

export const DEFAULT_TRANSACTION_SORT = Object.freeze({ key: 'date', dir: 'desc' })

/**
 * Sens au premier clic : date la plus récente d'abord, tiers A→Z, et pour
 * « Dépense » la plus grosse dépense d'abord (montant le plus négatif),
 * pour « Revenu » le plus gros revenu d'abord.
 */
export const TRANSACTION_SORT_DIRECTIONS = Object.freeze({ date: 'desc', payee: 'asc', expense: 'asc', income: 'desc' })

const payeeCollator = new Intl.Collator('fr', { sensitivity: 'base', numeric: true })

function compareBy(key) {
  if (key === 'date') return (a, b) => (a.date || '').localeCompare(b.date || '')
  if (key === 'payee') return (a, b) => payeeCollator.compare(a.payee || '', b.payee || '')
  if (key === 'expense' || key === 'income') return (a, b) => Number(a.amount) - Number(b.amount)
  return null
}

/** Vrai pour l'ordre chronologique, le seul où les intertitres de mois ont un sens. */
export function isChronologicalSort(sort) {
  return !sort || sort.key === 'date'
}

/**
 * Copie triée selon sort = { key, dir }. Les transactions sans tiers vont
 * toujours en fin de liste pour le tri par tiers ; les égalités sont
 * départagées par la date la plus récente, pour un ordre stable et lisible.
 */
export function sortTransactions(transactions, sort) {
  if (!sort || (sort.key === DEFAULT_TRANSACTION_SORT.key && sort.dir === DEFAULT_TRANSACTION_SORT.dir)) return transactions
  const compare = compareBy(sort.key)
  if (!compare) return transactions
  const sign = sort.dir === 'desc' ? -1 : 1
  const byRecentDate = (a, b) => (b.date || '').localeCompare(a.date || '')
  return [...transactions].sort((a, b) => {
    if (sort.key === 'payee' && !a.payee !== !b.payee) return a.payee ? -1 : 1
    return sign * compare(a, b) || byRecentDate(a, b)
  })
}

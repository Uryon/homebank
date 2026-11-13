/**
 * Utilisation des tiers (nombre de transactions, date de la dernière) et tri
 * de la liste des tiers, pour la page Tiers.
 */

/** Map payeeId → { count, lastDate } en un seul passage sur les transactions. */
export function usageByPayeeId(transactions) {
  const usage = new Map()
  for (const t of transactions) {
    if (!t.payeeId) continue
    const entry = usage.get(t.payeeId)
    if (entry) {
      entry.count += 1
      if (t.date > entry.lastDate) entry.lastDate = t.date
    } else {
      usage.set(t.payeeId, { count: 1, lastDate: t.date || '' })
    }
  }
  return usage
}

const EMPTY_USAGE = { count: 0, lastDate: '' }
const nameCollator = new Intl.Collator('fr', { sensitivity: 'base', numeric: true })

/** Sens par défaut au premier clic sur une colonne : A→Z pour le nom, le plus grand / le plus récent d'abord sinon. */
export const DEFAULT_SORT_DIRECTION = { name: 'asc', count: 'desc', lastDate: 'desc' }

/**
 * Nouveau tri après un clic sur l'en-tête `key` : même colonne → sens inversé ;
 * autre colonne → son sens par défaut.
 */
export function nextSort(current, key) {
  if (current?.key === key) return { key, dir: current.dir === 'asc' ? 'desc' : 'asc' }
  return { key, dir: DEFAULT_SORT_DIRECTION[key] || 'asc' }
}

function compareBy(key, usage) {
  if (key === 'name') return (a, b) => nameCollator.compare(a.name, b.name)
  const get = (p) => usage.get(p.id) || EMPTY_USAGE
  if (key === 'count') return (a, b) => get(a).count - get(b).count || get(a).lastDate.localeCompare(get(b).lastDate)
  if (key === 'lastDate') return (a, b) => get(a).lastDate.localeCompare(get(b).lastDate) || get(a).count - get(b).count
  return null
}

/**
 * Copie triée de la liste selon sort = { key: 'name' | 'count' | 'lastDate', dir: 'asc' | 'desc' }.
 * Sans tri (null) ou clé inconnue : la liste d'origine, inchangée. Les égalités
 * sont départagées par le nom, pour un ordre stable et lisible.
 */
export function sortPayees(payees, sort, usage) {
  const compare = sort && compareBy(sort.key, usage)
  if (!compare) return payees
  const sign = sort.dir === 'desc' ? -1 : 1
  return [...payees].sort((a, b) => sign * compare(a, b) || nameCollator.compare(a.name, b.name))
}

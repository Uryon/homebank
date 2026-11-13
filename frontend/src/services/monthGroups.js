/**
 * Regroupement d'une liste de transactions par mois, pour les intertitres de
 * la page Transactions (« Novembre 2026 · 34 transactions · solde … »).
 */

/** Clé de mois « AAAA-MM » d'une date ISO « AAAA-MM-JJ ». */
export function monthKey(isoDate) {
  return (isoDate || '').slice(0, 7)
}

/** Nombre de transactions et solde (somme des montants) par mois. Un seul passage sur la liste. */
export function summarizeByMonth(transactions) {
  const summary = new Map()
  for (const t of transactions) {
    const key = monthKey(t.date)
    const entry = summary.get(key) || { count: 0, total: 0 }
    entry.count += 1
    entry.total += Number(t.amount || 0)
    summary.set(key, entry)
  }
  // Arrondi au centime : évite les 0,1 + 0,2 = 0,30000000000000004.
  for (const entry of summary.values()) entry.total = Math.round(entry.total * 100) / 100
  return summary
}

/** Vrai si la transaction à cet index ouvre un nouveau mois dans la liste affichée. */
export function startsNewMonth(transactions, index) {
  if (index === 0) return true
  return monthKey(transactions[index].date) !== monthKey(transactions[index - 1].date)
}

const headingFormatter = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' })

/** « novembre 2026 » → « Novembre 2026 ». */
export function formatMonthHeading(isoDate) {
  const [year, month] = monthKey(isoDate).split('-').map(Number)
  if (!year || !month) return ''
  const label = headingFormatter.format(new Date(Date.UTC(year, month - 1, 1)))
  return label.charAt(0).toUpperCase() + label.slice(1)
}

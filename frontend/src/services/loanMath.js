/**
 * Mensualité théorique (hors assurance) d'un prêt amortissable à taux fixe :
 * P × r / (1 − (1 + r)^−n), avec r le taux mensuel ; P / n à taux nul.
 * Arrondie au centime. Renvoie null tant que capital ou durée manquent.
 */
export function monthlyPaymentFor(principal, annualRatePercent, termMonths) {
  const capital = Number(principal)
  const months = Number(termMonths)
  if (!(capital > 0) || !(months > 0)) return null
  const monthlyRate = Number(annualRatePercent || 0) / 100 / 12
  const payment = monthlyRate === 0
    ? capital / months
    : capital * monthlyRate / (1 - (1 + monthlyRate) ** -months)
  return Math.round(payment * 100) / 100
}

/** Durée maximale d'un prêt, même limite que le backend (ServicePret#MAX_TERM_MONTHS). */
export const MAX_TERM_MONTHS = 600

/** Message d'erreur du champ Durée, vide si la durée est valide. */
export function termMonthsError(termMonths) {
  if (!termMonths) return 'Saisissez la durée en mois.'
  if (termMonths > MAX_TERM_MONTHS) return `La durée ne peut pas dépasser ${MAX_TERM_MONTHS} mois (50 ans).`
  return ''
}

/** Mensualité totale payée chaque mois : remboursement et assurance. */
export function totalMonthly(loan) {
  return (loan.monthlyPayment || 0) + (loan.monthlyInsurance || 0)
}

/** Capital déjà remboursé = ce qui vous appartient réellement dans le bien financé. */
export function ownedAmount(loan) {
  return (loan.principal || 0) - (loan.currentRemainingBalance || 0)
}

export function ownedPercent(loan) {
  if (!loan.principal) return 0
  return Math.round((ownedAmount(loan) / loan.principal) * 100)
}

/** Avancement du remboursement en %, borné à 0–100 pour la barre de progression. */
export function progressPercent(loan) {
  if (!loan.principal) return 0
  const paid = loan.principal - loan.currentRemainingBalance
  return Math.min(100, Math.max(0, (paid / loan.principal) * 100))
}

/** Paramètres saisis d'un prêt (sans les valeurs calculées par le backend), pour le recréer à l'identique. */
export function loanPayload(loan) {
  const { name, principal, annualRate, termMonths, startDate, monthlyPayment, monthlyInsurance, notes } = loan
  return { name, principal, annualRate, termMonths, startDate, monthlyPayment, monthlyInsurance, notes }
}

/** Échéancier avec `isCurrent` sur la première échéance non payée (ligne mise en évidence). */
export function markCurrentRow(schedule) {
  let currentAssigned = false
  return schedule.map(row => {
    const isCurrent = !currentAssigned && !row.paid
    if (isCurrent) currentAssigned = true
    return { ...row, isCurrent }
  })
}

/**
 * Date d'une échéance au format jj/mm/aaaa. Repli « — » (au lieu de « »
 * côté format.js) : une échéance sans date doit rester visuellement signalée
 * dans le tableau d'amortissement, contrairement à un mémo vide.
 */
export function formatScheduleDate(iso) {
  if (!iso) return '—'
  const [y, m, d] = iso.split('-')
  return `${d}/${m}/${y}`
}

/**
 * Libellé de l'axe du graphique pour l'échéance d'index donné : l'année si
 * elle ouvre une année retenue, sinon rien. Au plus ~12 repères, quel que
 * soit la durée du prêt.
 */
export function yearTickLabel(rows, index) {
  const year = Number(rows[index]?.date?.slice(0, 4))
  if (!year) return ''
  const previousYear = index > 0 ? Number(rows[index - 1].date.slice(0, 4)) : null
  if (previousYear === year) return ''
  const firstYear = Number(rows[0].date.slice(0, 4))
  const lastYear = Number(rows[rows.length - 1].date.slice(0, 4))
  const step = Math.max(1, Math.ceil((lastYear - firstYear + 1) / 12))
  return (year - firstYear) % step === 0 ? String(year) : ''
}

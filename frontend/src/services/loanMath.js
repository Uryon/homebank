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

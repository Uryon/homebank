/**
 * Petits formatteurs partages entre plusieurs vues/composants (montants,
 * dates, memo), extraits pour eviter de les dupliquer entre Transactions.vue
 * et TransactionRow.vue.
 */

export function formatAmount(value) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(value || 0)
}

export function formatDate(iso) {
  if (!iso) return ''
  const [y, m, d] = iso.split('-')
  return `${d}/${m}/${y}`
}

/**
 * N'affiche rien si le mémo est vide, null, ou contient littéralement
 * "(NULL)" (certains exports CSV/QIF écrivent cette valeur telle quelle
 * pour un champ vide).
 */
export function displayMemo(memo) {
  if (!memo) return ''
  if (memo.trim().toUpperCase() === '(NULL)') return ''
  return memo
}

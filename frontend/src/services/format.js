/**
 * Petits formatteurs partages entre plusieurs vues/composants (montants,
 * dates, memo), extraits pour eviter de les dupliquer entre Transactions.vue
 * et TransactionRow.vue.
 */

// Instance creee une seule fois (et non a chaque appel de formatAmount) :
// construire un Intl.NumberFormat est couteux, et formatAmount est appele
// pour chaque transaction a chaque frappe dans la recherche (Transactions.vue)
// en plus de chaque rendu de ligne — recreer l'instance a chaque fois ralentit
// nettement la saisie sur un historique de plusieurs milliers de transactions.
const amountFormatter = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' })

export function formatAmount(value) {
  return amountFormatter.format(value || 0)
}

const rateFormatter = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 3 })

/** Taux en pourcentage déjà exprimé en % (ex : 1.52) affiché à la française : « 1,52 % ». */
export function formatRate(rate) {
  return `${rateFormatter.format(Number(rate || 0))} %`
}

/**
 * Date "AAAA-MM-JJ" dans le fuseau LOCAL (par defaut : aujourd'hui).
 * Jamais via toISOString(), qui convertit en UTC : en France, minuit local
 * devient la veille a 22h/23h UTC, ce qui decalait d'un jour la date du jour
 * (entre minuit et 1h/2h du matin) et les bornes de mois des rapports.
 */
export function toLocalIsoDate(date = new Date()) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
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

/**
 * Petits formatteurs partages entre plusieurs vues/composants (montants,
 * dates, memo), extraits pour eviter de les dupliquer entre Transactions.vue
 * et TransactionRow.vue.
 */

export const DEFAULT_CURRENCY = 'EUR'

// Une instance par devise, creee une seule fois (et non a chaque appel de
// formatAmount) : construire un Intl.NumberFormat est couteux, et
// formatAmount est appele pour chaque transaction a chaque frappe dans la
// recherche (Transactions.vue) en plus de chaque rendu de ligne — recreer
// l'instance a chaque fois ralentit nettement la saisie sur un historique de
// plusieurs milliers de transactions.
const amountFormatters = new Map()

/**
 * Formatteur fr-FR de la devise d'un compte (code ISO a 3 lettres, voir
 * Compte#currency) ; euro si la devise est absente ou refusee par Intl.
 */
export function currencyFormatter(currency) {
  const code = (currency || DEFAULT_CURRENCY).toUpperCase()
  let formatter = amountFormatters.get(code)
  if (!formatter) {
    try {
      formatter = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: code })
    } catch {
      // Code mal forme (RangeError d'Intl) : on retombe sur l'euro plutot que de casser l'affichage.
      formatter = currencyFormatter(DEFAULT_CURRENCY)
    }
    amountFormatters.set(code, formatter)
  }
  return formatter
}

/**
 * Montant dans la devise donnee (euro par defaut). Les totaux qui melangent
 * plusieurs comptes (patrimoine, tableau de bord) restent en euro : aucun
 * taux de change n'est gere.
 */
export function formatAmount(value, currency = DEFAULT_CURRENCY) {
  return currencyFormatter(currency).format(value || 0)
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

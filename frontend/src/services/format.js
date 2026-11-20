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

/** Bornes des champs date (attributs min/max), identiques à Periode#verifierAnnee côté backend. */
export const MIN_DATE = '1900-01-01'
export const MAX_DATE = '2099-12-31'

/**
 * Message d'erreur d'un champ date obligatoire : vide, ou année hors de
 * [1900, 2099] — typiquement « 17/11/26 » tapé dans le champ, que Chrome lit
 * comme l'an 26.
 */
export function dateFieldError(iso) {
  if (!iso) return 'Choisissez une date.'
  return iso < MIN_DATE || iso > MAX_DATE ? `Année invalide (${Number(iso.slice(0, 4))}) : saisissez-la sur 4 chiffres.` : ''
}

/**
 * Message d'erreur du champ Montant, vide si valide : obligatoire et non nul
 * (même règle que ServiceTransaction#ensureNonZeroAmount). `previousAmount` :
 * montant actuel d'une transaction modifiée, qui peut rester à 0 si elle
 * l'était déjà (ligne importée d'un relevé).
 */
export function amountFieldError(amount, previousAmount = null) {
  if (amount == null || amount === '') return 'Saisissez un montant.'
  const wasAlreadyZero = previousAmount != null && Number(previousAmount) === 0
  if (Number(amount) === 0 && !wasAlreadyZero) return 'Le montant ne peut pas être nul.'
  return ''
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

/**
 * Rythmes usuels reconnus, avec leur tolérance en jours : un écart moyen de
 * 29 à 32 jours se lit « mensuel », etc. Au-delà, on garde la durée brute.
 */
const FREQUENCIES = [
  { days: 7, tolerance: 1, label: 'hebdomadaire' },
  { days: 14, tolerance: 2, label: 'toutes les deux semaines' },
  { days: 30.5, tolerance: 2.5, label: 'mensuel' },
  { days: 61, tolerance: 4, label: 'bimestriel' },
  { days: 91, tolerance: 6, label: 'trimestriel' },
  { days: 182, tolerance: 10, label: 'semestriel' },
  { days: 365, tolerance: 15, label: 'annuel' }
]

/** Libellé lisible d'un intervalle moyen entre occurrences (en jours). */
export function formatFrequency(days) {
  const value = Number(days)
  if (!Number.isFinite(value) || value <= 0) return ''
  const known = FREQUENCIES.find(f => Math.abs(value - f.days) <= f.tolerance)
  return known ? known.label : `tous les ~${Math.round(value)} jours`
}

/**
 * Valeur d'un montant telle qu'affichée dans un champ de saisie : virgule
 * décimale française et deux décimales (« -48,90 »), entier sans décimales
 * (« 2400 »), sans séparateur de milliers ni symbole pour rester simple à
 * retoucher. Vide si aucun montant.
 */
export function formatAmountForInput(value) {
  if (value === null || value === undefined || value === '') return ''
  const number = Number(value)
  if (!Number.isFinite(number)) return ''
  return Number.isInteger(number) ? String(number) : number.toFixed(2).replace('.', ',')
}

/**
 * Nombre suivi de son libellé accordé (règle française : pluriel au-delà de 1) :
 * pluralize(3, 'transaction validée', 'transactions validées') → « 3 transactions validées »,
 * plutôt qu'un « transaction(s) validée(s) » à lire en deux fois.
 */
export function pluralize(count, singular, pluralForm) {
  return `${count} ${Math.abs(Number(count)) >= 2 ? pluralForm : singular}`
}

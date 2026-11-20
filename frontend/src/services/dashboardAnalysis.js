/**
 * Calculs purs du tableau de bord (Dashboard.vue), sans état Vue ni appel
 * réseau : regroupement de l'analyse mensuelle par catégorie racine,
 * detection des mois atypiques, fusion des lignes du popover par tiers,
 * capital des prêts remboursé et plages de dates des rapports.
 * Extraits du composant pour être testés unitairement.
 */
import { formatAmount, pluralize, toLocalIsoDate } from './format'

/**
 * Fusionne les lignes qui partagent le même tiers en une seule (montant
 * additionné, date la plus récente conservée) : sert surtout à recombiner
 * les tranches d'une transaction annualisee (voir ServiceRapport#attributedTransactions),
 * qui remontent sinon comme autant de lignes distinctes (une par mois de la
 * fenêtre de 12 mois couverte par la cellule survolée, même id de
 * transaction à chaque fois) pour ce qui est en réalité UNE seule dépense/
 * revenu. Les lignes sans tiers ne sont jamais fusionnees entre elles
 * (souvent des virements ou cas ponctuels sans rapport les uns aux autres).
 */
export function mergeRowsByPayee(rows) {
  const merged = new Map()
  const withoutPayee = []
  for (const row of rows) {
    if (!row.payee) {
      withoutPayee.push(row)
      continue
    }
    const existing = merged.get(row.payee)
    if (existing) {
      existing.amount += Number(row.amount)
      if (row.date > existing.date) existing.date = row.date
    } else {
      merged.set(row.payee, { ...row, amount: Number(row.amount) })
    }
  }
  return [...merged.values(), ...withoutPayee]
}

/** Découpe une clé de mois "YYYY-MM-01" en plage [début, fin] de ce mois (calcul en local, sans passer par Date(string) qui parse en UTC). */
export function monthRange(monthKey) {
  const [year, month] = monthKey.split('-').map(Number)
  return { from: monthKey, to: toLocalIsoDate(new Date(year, month, 0)) }
}

/** 12 mois pleins se terminant à monthStart inclus, utilisé par "Où va mon argent ?" (aligné sur le même mois que le reste du tableau de bord, plutôt qu'une fenêtre glissante figee sur aujourd'hui). */
export function last12MonthsRange(monthStart) {
  const from = new Date(monthStart.getFullYear(), monthStart.getMonth() - 11, 1)
  const to = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0)
  return { from: toLocalIsoDate(from), to: toLocalIsoDate(to) }
}

/** Tri alphabétique français par `name`, partagé par analysisGroup ci-dessous (groupes et sous-catégories). */
function byNameFr(a, b) {
  return String(a.name).localeCompare(String(b.name), 'fr')
}

/**
 * Clé de regroupement d'une ligne "catégorie/mois" (voir analysisGroup) : l'id
 * de la catégorie racine, qu'il s'agisse de la racine elle-même (categoryId)
 * ou d'une sous-catégorie (parentId). Une même clé des deux côtés, sinon une
 * racine ayant à la fois des transactions directes et des sous-catégories
 * apparaissait en deux groupes homonymes.
 */
function rootGroupKey(row) {
  return `root-${row.parentId ?? row.categoryId ?? 'uncategorized'}`
}

function zeroValuesFor(months) {
  return Object.fromEntries(months.map(m => [m.key, 0]))
}

/** Cumule les valeurs mensuelles (et total/moyenne, total de l'année précédente) de source dans target — utilise pour agréger une racine ou ses sous-catégories dans son groupe (voir analysisGroup). */
function addRowValues(target, source, months) {
  for (const month of months) {
    target.values[month.key] = Number(target.values[month.key] || 0) + Number(source.values[month.key] || 0)
  }
  target.total = Object.values(target.values).reduce((sum, value) => sum + Number(value || 0), 0)
  target.average = months.length ? target.total / months.length : 0
  target.previousTotal += Number(source.previousTotal || 0)
}

/**
 * Ligne sans rien à montrer : nulle sur toute la fenêtre ET sur l'année
 * précédente. Une catégorie active l'an dernier seulement reste affichée,
 * pour que sa disparition se voie dans la comparaison (voir yearOverYearChange).
 */
function hasNothingToShow(row, months) {
  return isEmptyRow(row, months) && !Number(row.previousTotal || 0)
}

/** Nouveau groupe vierge (valeurs à zéro) pour la catégorie racine de row (voir analysisGroup). */
function newAnalysisGroup(row, months) {
  return {
    key: rootGroupKey(row),
    name: row.parentId != null ? row.parentName : row.name,
    // Categorie racine (elle-même, ou celle dont ce groupe réunit les
    // sous-catégories) pour retrouver au survol les transactions de tout
    // le groupe via rootCategoryId (voir groupCategoryFilter/ServiceRapport#cellTransactions).
    categoryId: row.parentId != null ? row.parentId : row.categoryId,
    uncategorized: row.parentId == null && row.categoryId == null,
    values: zeroValuesFor(months),
    total: 0,
    average: 0,
    previousTotal: 0,
    children: []
  }
}

/**
 * Regroupe les lignes plates (catégorie/sous-catégorie) renvoyées par
 * monthlyCategoryAnalysis en groupes racine + enfants, avec des totaux/
 * moyennes recalcules par groupe (une racine sans transaction directe
 * affiche quand même le total de ses sous-catégories).
 * `months` : les mois de la fenêtre ({ key }), dans l'ordre d'affichage.
 */
export function analysisGroup(rows, months) {
  const groups = new Map()

  for (const row of rows || []) {
    const rootKey = rootGroupKey(row)
    if (!groups.has(rootKey)) groups.set(rootKey, newAnalysisGroup(row, months))
    const group = groups.get(rootKey)

    if (row.parentId != null) {
      group.children.push(row)
    } else {
      // Les transactions directement affectées à la catégorie racine sont
      // intégrées au total du groupe.
      addRowValues(group, row, months)
    }
  }

  // Un parent qui n'a que des sous-catégories doit quand même afficher leur total.
  for (const group of groups.values()) {
    for (const child of group.children) addRowValues(group, child, months)
    // Sous-catégorie à 0 sur tous les mois (et l'an dernier) : rien à lire, on ne l'affiche pas.
    group.children = group.children.filter(child => !hasNothingToShow(child, months))
    group.children.sort(byNameFr)
  }

  return Array.from(groups.values())
    .filter(group => !hasNothingToShow(group, months))
    .sort(byNameFr)
}

/**
 * Ligne nulle sur toute la fenêtre (ex : catégorie de dépense qui apparaît
 * dans le tableau des revenus sans aucun montant) : masquée pour ne garder
 * que les lignes porteuses d'information.
 */
export function isEmptyRow(row, months) {
  return months.every(month => !Number(row.values?.[month.key] || 0))
}

/**
 * Écart (en écarts-types) au-delà duquel une case mensuelle est signalée
 * comme anomalie par rapport à la moyenne de sa ligne. 1.5 remontait beaucoup
 * trop de mois (environ 1 valeur sur 7 dépasse déjà ce seuil dans une
 * distribution normale) ; 2.5 ne retient que les écarts vraiment marqués.
 */
const ANOMALY_STDDEV_THRESHOLD = 2.5

/** En dessous de ce nombre de mois avec une valeur non nulle, l'écart-type n'est pas assez fiable pour signaler une anomalie. */
const ANOMALY_MIN_NONZERO_MONTHS = 4

/**
 * Écart minimal à la moyenne, en proportion de celle-ci, pour signaler une
 * case : sur une ligne quasi constante (abonnement, crédit d'impôts fixe),
 * l'écart-type est minuscule et quelques centimes suffiraient sinon.
 */
const ANOMALY_MIN_RELATIVE_GAP = 0.1

/** Moyenne et écart-type (population) d'un tableau de valeurs numériques. */
export function meanStdDev(values) {
  const nums = values.map(Number)
  const mean = nums.reduce((sum, v) => sum + v, 0) / (nums.length || 1)
  const variance = nums.reduce((sum, v) => sum + (v - mean) ** 2, 0) / (nums.length || 1)
  return { mean, stdDev: Math.sqrt(variance) }
}

/**
 * Signale (classe "monthly-anomaly") une case dont la valeur s'écarte de plus
 * de ANOMALY_STDDEV_THRESHOLD écarts-types de la moyenne des `rowValues`
 * (les valeurs des 12 mois de la même ligne) — met en évidence les mois
 * atypiques (dépense inhabituelle, revenu exceptionnel...) au premier coup
 * d'œil.
 *
 * Les mois à 0 (catégorie pas utilisée ce mois-là) sont exclus du calcul de
 * la moyenne/écart-type : sinon, sur une catégorie utilisée occasionnellement,
 * les nombreux mois à 0 tirent la moyenne vers le bas et n'importe quel mois
 * avec une dépense normale ressort comme "anomalie" — alors que ces mois à 0
 * sont déjà mis en évidence séparément (classe "zero-value"). Une valeur
 * elle-même nulle n'est jamais signalée. Aucune case signalée si la ligne est
 * quasi constante (stdDev nul) ou si trop peu de mois ont une valeur pour
 * qu'un écart-type soit fiable (voir ANOMALY_MIN_NONZERO_MONTHS).
 *
 * `higherIsBetter` (revenus, solde) indique le sens favorable : la classe
 * "anomaly-favorable" est ajoutée quand l'écart va dans ce sens (revenu
 * exceptionnel, dépense inhabituellement basse), pour l'afficher en vert
 * plutôt qu'en corail.
 */
export function anomalyClass(value, rowValues, higherIsBetter = false) {
  return classifyAnomaly(value, anomalyStats(rowValues), higherIsBetter)
}

/**
 * Classes d'anomalie de toutes les cases d'une ligne ({ cleMois: valeur }),
 * avec une seule moyenne/écart-type pour la ligne : à calculer dans un
 * computed, pas case par case à chaque rendu du tableau.
 */
export function rowAnomalyClasses(valuesByKey, higherIsBetter = false) {
  const stats = anomalyStats(Object.values(valuesByKey))
  const classes = {}
  for (const [key, value] of Object.entries(valuesByKey)) classes[key] = classifyAnomaly(value, stats, higherIsBetter)
  return classes
}

/** Moyenne/écart-type des mois non nuls d'une ligne, null si trop peu de mois ou ligne constante (voir anomalyClass). */
function anomalyStats(rowValues) {
  const nonZero = rowValues.map(Number).filter(Boolean)
  if (nonZero.length < ANOMALY_MIN_NONZERO_MONTHS) return null
  const stats = meanStdDev(nonZero)
  return stats.stdDev ? stats : null
}

function classifyAnomaly(value, stats, higherIsBetter) {
  if (!stats || !Number(value)) return {}
  const { mean, stdDev } = stats
  const gap = Math.abs(Number(value) - mean)
  const isAnomaly = gap / stdDev > ANOMALY_STDDEV_THRESHOLD && gap > Math.abs(mean) * ANOMALY_MIN_RELATIVE_GAP
  return { 'monthly-anomaly': isAnomaly, 'anomaly-favorable': isAnomaly && (Number(value) > mean) === higherIsBetter }
}

/**
 * Capital des prêts déjà remboursé (= vous appartient), mois par mois, aligné
 * sur les points du graphique "Evolution du solde" (12 mois historiques +
 * 12 mois prévisionnels). Contrairement au solde, cette série n'a rien
 * d'incertain : l'echeancier d'un prêt à taux fixe est connu à l'avance, donc
 * pas de distinction historique/prévisionnel nécessaire ici, juste une somme
 * sur tous les prêts pour chaque mois. Retourne null si l'utilisateur n'a
 * aucun prêt, pour ne pas ajouter une ligne à plat inutile au graphique.
 */
export function buildLoanOwnedSeries(points, loansList, schedules) {
  if (!loansList.length) return null

  const perLoan = loansList.map((loan, i) => {
    const rows = schedules[i]?.rows || []
    const byMonth = new Map(rows.map(r => [r.date.slice(0, 7), Number(r.remainingBalance)]))
    return {
      principal: Number(loan.principal || 0),
      byMonth,
      firstMonth: rows.length ? rows[0].date.slice(0, 7) : null,
      lastMonth: rows.length ? rows[rows.length - 1].date.slice(0, 7) : null
    }
  })

  return points.map(p => {
    const monthKey = p.date.slice(0, 7)
    let owned = 0
    for (const loan of perLoan) {
      if (!loan.firstMonth || monthKey < loan.firstMonth) continue // pret pas encore commence
      if (monthKey > loan.lastMonth) { owned += loan.principal; continue } // deja solde
      owned += loan.principal - (loan.byMonth.get(monthKey) ?? 0)
    }
    return owned
  })
}


const deltaPercentFormatter = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 1, signDisplay: 'exceptZero' })

/** Variation en dessous de laquelle aucune tendance n'est affichée (0,05 %), pour ne pas afficher « +0 % ». */
const NEGLIGIBLE_RATIO = 0.0005
/** Écart de solde (en euros) en dessous duquel aucune tendance n'est affichée. */
const NEGLIGIBLE_AMOUNT = 0.5
/**
 * Écart sur un an (en euros) en dessous duquel la colonne « Évol. » reste en
 * couleur neutre : « +25 % » sur un abonnement passé de 24 € à 30 € ne mérite
 * pas le même rouge qu'une hausse de plusieurs centaines d'euros.
 */
const MINOR_CHANGE_AMOUNT = 10

/** Montant précédé de son signe (« +1 234,00 € », « -12,00 € »). */
function signedAmount(amount) {
  return `${amount > 0 ? '+' : ''}${formatAmount(amount)}`
}

/**
 * Variation du dernier mois d'une série par rapport au précédent, pour les
 * chiffres clés du tableau de bord. goodWhenUp indique si une hausse est une
 * bonne nouvelle (revenus : oui, dépenses : non). null si non calculable
 * (moins de deux mois, mois précédent à zéro) ou négligeable.
 */
export function monthDelta(series, goodWhenUp) {
  if (series.length < 2) return null
  const current = series[series.length - 1]
  const previous = series[series.length - 2]
  if (!previous) return null
  const ratio = (current - previous) / Math.abs(previous)
  if (!Number.isFinite(ratio) || Math.abs(ratio) < NEGLIGIBLE_RATIO) return null
  return { text: `${deltaPercentFormatter.format(ratio)} vs mois préc.`, tone: (ratio > 0) === goodWhenUp ? 'good' : 'bad' }
}

/** Évolution du solde entre le premier et le dernier point de la série (ex : « +1 234,00 € sur 12 mois »), null si négligeable. */
export function balanceChangeOverPeriod(series, periodLabel) {
  if (series.length < 2) return null
  const diff = series[series.length - 1] - series[0]
  if (Math.abs(diff) < NEGLIGIBLE_AMOUNT) return null
  return { text: `${signedAmount(diff)} sur ${periodLabel}`, tone: diff > 0 ? 'good' : 'bad' }
}

/**
 * Couverture de la comparaison avec l'année précédente du tableau d'analyse
 * mensuelle (même fenêtre de 12 mois un an plus tôt, voir
 * ServiceRapport#monthlyCategoryAnalysis), d'après le début de l'historique :
 * available s'il commence au plus tard à la fin de cette fenêtre (sinon rien
 * à comparer), partial s'il commence après son premier mois (moins de 12 mois
 * de données en face). Comparaison au mois près : un relevé commencé le 2 du
 * premier mois couvre bien ce mois. Dates ISO, comparables comme chaînes.
 */
export function yearOverYearCoverage(analysis) {
  const { historyStart, previousFrom, previousTo } = analysis || {}
  if (!historyStart || !previousFrom || !previousTo || historyStart > previousTo) return { available: false, partial: false }
  return { available: true, partial: historyStart.slice(0, 7) > previousFrom.slice(0, 7) }
}

/** Comparaison impossible (pas d'historique en face) ou sans objet (0 des deux côtés). */
const NO_COMPARISON = Object.freeze({ text: '—', tone: '', title: 'Aucun montant sur les deux périodes' })
/**
 * Ligne absente l'an dernier (catégorie nouvelle) : pas d'évolution chiffrable.
 * Affichée comme un point plein discret (dessiné en CSS, tone 'new') ; text
 * reste le libellé lu par les lecteurs d'écran, masqué à l'écran.
 */
const NO_PREVIOUS = Object.freeze({ text: "Absent l'an dernier", tone: 'new', title: "Absent l'an dernier" })
/** Ligne tombée à zéro cette année : un « -100 % » vert ou rouge disait peu ; cercle vide discret (tone 'gone'), même principe. */
const vanished = (diff) => ({ text: 'Plus aucun montant cette année', tone: 'gone', title: `Plus aucun montant cette année (${signedAmount(diff)} sur un an)` })
const STABLE = Object.freeze({ text: '=', tone: '', title: 'Stable sur un an' })

/**
 * Évolution d'un montant par rapport à l'année précédente, pour la colonne
 * « Évol. » du tableau d'analyse. kind : 'income' (hausse favorable) ou
 * 'expense' (hausse défavorable), montants positifs comparés en pourcentage ;
 * 'net' (solde, de signe quelconque : un pourcentage n'y aurait pas de sens)
 * comparé en écart en euros. { text, tone : 'good' | 'bad' | 'new' | 'gone' | '', title }
 * — title (infobulle) donne l'écart en euros derrière un pourcentage.
 */
export function yearOverYearChange(current, previous, kind) {
  const now = Number(current) || 0
  const before = Number(previous) || 0
  const diff = now - before
  const minor = Math.abs(diff) < MINOR_CHANGE_AMOUNT
  if (kind === 'net') {
    if (Math.abs(diff) < NEGLIGIBLE_AMOUNT) return STABLE
    return { text: signedAmount(diff), tone: changeTone(diff > 0, minor), title: 'Écart du solde sur un an' }
  }
  if (!before) return now ? NO_PREVIOUS : NO_COMPARISON
  if (!now) return vanished(diff)
  const ratio = diff / before
  if (Math.abs(ratio) < NEGLIGIBLE_RATIO) return STABLE
  return {
    text: deltaPercentFormatter.format(ratio),
    tone: changeTone((ratio > 0) === (kind === 'income'), minor),
    title: `${signedAmount(diff)} sur un an`
  }
}

/** Teinte d'une évolution : favorable/défavorable, neutre si l'écart en euros est faible (voir MINOR_CHANGE_AMOUNT). */
function changeTone(favorable, minor) {
  if (minor) return ''
  return favorable ? 'good' : 'bad'
}

/**
 * Comparaison d'une carte du haut (Revenus/Dépenses/Net du mois affiché) avec
 * le même mois un an plus tôt (« vs oct. 2025 : -8 % ») ; null si ce mois
 * n'est pas couvert par l'historique ou si l'évolution est sans objet.
 */
export function yearAgoKpi(current, yearAgo, kind, yearAgoLabel) {
  if (yearAgo == null) return null
  const change = yearOverYearChange(current, yearAgo, kind)
  if (change === NO_COMPARISON || change === NO_PREVIOUS) return null
  // Une carte affiche toujours un chiffre : pas de cercle « disparu », mais -100 %.
  const text = change.tone === 'gone' ? deltaPercentFormatter.format(-1) : change.text
  const tone = change.tone === 'gone' ? (kind === 'income' ? 'bad' : 'good') : change.tone
  return { text: `vs ${yearAgoLabel} : ${text}`, tone, title: `${yearAgoLabel} : ${formatAmount(Number(yearAgo) || 0)}` }
}

const savingsRateFormatter = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 0 })

/**
 * Taux d'épargne du mois : part des revenus mise de côté (« 18 % des revenus »).
 * null sans revenus (taux sans signification) ; négatif si les dépenses
 * dépassent les revenus.
 */
export function savingsRateLabel(income, net) {
  const revenue = Number(income)
  if (!(revenue > 0)) return null
  return `${savingsRateFormatter.format(Number(net) / revenue)} des revenus`
}

/**
 * Libellé court d'une fenêtre de mois (« déc. 2025 – nov. 2026 ») à partir
 * de ses bornes ISO, pour les titres du tableau de bord quand on n'est plus
 * sur les 12 derniers mois.
 */
export function monthWindowLabel(from, to) {
  const short = (iso) => {
    const [year, month] = iso.split('-').map(Number)
    return new Date(year, month - 1, 1).toLocaleDateString('fr-FR', { month: 'short', year: 'numeric' })
  }
  return `${short(from)} – ${short(to)}`
}

/**
 * Points « à traiter » affichés en haut du tableau de bord, avec le lien vers
 * la liste correspondante. Seuls les points non nuls apparaissent : sans
 * rien à traiter, le bandeau disparaît.
 */
export function dashboardTodoItems({ pending = 0, uncategorized = 0, duplicates = 0 }) {
  return [
    { key: 'pending', count: pending, label: pluralize(pending, 'transaction en attente', 'transactions en attente'), to: { path: '/transactions', query: { pending: '1' } } },
    { key: 'uncategorized', count: uncategorized, label: pluralize(uncategorized, 'transaction sans catégorie', 'transactions sans catégorie'), to: { path: '/transactions', query: { uncategorized: '1' } } },
    { key: 'duplicates', count: duplicates, label: pluralize(duplicates, 'doublon possible', 'doublons possibles'), to: { path: '/transactions/doublons' } }
  ].filter(item => item.count > 0)
}

/** Clé de mois « AAAA-MM-01 » (premier jour) d'une date, format des rapports (referenceMonth, months[].key). */
export function monthKeyOf(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-01`
}

/** Mois en toutes lettres avec l'année, majuscule initiale (« Septembre 2026 »). */
export function monthTitle(date) {
  const label = date.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
  return label.charAt(0).toUpperCase() + label.slice(1)
}

/** monthTitle d'une clé « AAAA-MM-01 » : les en-têtes « Sept. » des tableaux ne précisent pas l'année. */
export function monthTitleFromKey(key) {
  const [year, month] = key.split('-').map(Number)
  return monthTitle(new Date(year, month - 1, 1))
}

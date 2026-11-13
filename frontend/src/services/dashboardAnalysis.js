/**
 * Calculs purs du tableau de bord (Dashboard.vue), sans etat Vue ni appel
 * reseau : regroupement de l'analyse mensuelle par categorie racine,
 * detection des mois atypiques, fusion des lignes du popover par tiers,
 * capital des prets rembourse et plages de dates des rapports.
 * Extraits du composant pour etre testes unitairement.
 */
import { formatAmount, toLocalIsoDate } from './format'

/**
 * Fusionne les lignes qui partagent le meme tiers en une seule (montant
 * additionne, date la plus recente conservee) : sert surtout a recombiner
 * les tranches d'une transaction annualisee (voir ServiceRapport#attributedTransactions),
 * qui remontent sinon comme autant de lignes distinctes (une par mois de la
 * fenetre de 12 mois couverte par la cellule survolee, meme id de
 * transaction a chaque fois) pour ce qui est en realite UNE seule depense/
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

/** Decoupe une cle de mois "YYYY-MM-01" en plage [debut, fin] de ce mois (calcul en local, sans passer par Date(string) qui parse en UTC). */
export function monthRange(monthKey) {
  const [year, month] = monthKey.split('-').map(Number)
  return { from: monthKey, to: toLocalIsoDate(new Date(year, month, 0)) }
}

/** 12 mois pleins se terminant a monthStart inclus, utilise par "Où va mon argent ?" (aligne sur le meme mois que le reste du tableau de bord, plutot qu'une fenetre glissante figee sur aujourd'hui). */
export function last12MonthsRange(monthStart) {
  const from = new Date(monthStart.getFullYear(), monthStart.getMonth() - 11, 1)
  const to = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0)
  return { from: toLocalIsoDate(from), to: toLocalIsoDate(to) }
}

/** Tri alphabetique francais par `name`, partage par analysisGroup ci-dessous (groupes et sous-categories). */
function byNameFr(a, b) {
  return String(a.name).localeCompare(String(b.name), 'fr')
}

/**
 * Cle de regroupement d'une ligne "categorie/mois" (voir analysisGroup) : l'id
 * de la categorie racine, qu'il s'agisse de la racine elle-meme (categoryId)
 * ou d'une sous-categorie (parentId). Une meme cle des deux cotes, sinon une
 * racine ayant a la fois des transactions directes et des sous-categories
 * apparaissait en deux groupes homonymes.
 */
function rootGroupKey(row) {
  return `root-${row.parentId ?? row.categoryId ?? 'uncategorized'}`
}

function zeroValuesFor(months) {
  return Object.fromEntries(months.map(m => [m.key, 0]))
}

/** Cumule les valeurs mensuelles (et total/moyenne) de source dans target — utilise pour agreger une racine ou ses sous-categories dans son groupe (voir analysisGroup). */
function addRowValues(target, source, months) {
  for (const month of months) {
    target.values[month.key] = Number(target.values[month.key] || 0) + Number(source.values[month.key] || 0)
  }
  target.total = Object.values(target.values).reduce((sum, value) => sum + Number(value || 0), 0)
  target.average = months.length ? target.total / months.length : 0
}

/** Nouveau groupe vierge (valeurs a zero) pour la categorie racine de row (voir analysisGroup). */
function newAnalysisGroup(row, months) {
  return {
    key: rootGroupKey(row),
    name: row.parentId != null ? row.parentName : row.name,
    // Categorie racine (elle-meme, ou celle dont ce groupe reunit les
    // sous-categories) pour retrouver au survol les transactions de tout
    // le groupe via rootCategoryId (voir groupCategoryFilter/ServiceRapport#cellTransactions).
    categoryId: row.parentId != null ? row.parentId : row.categoryId,
    uncategorized: row.parentId == null && row.categoryId == null,
    values: zeroValuesFor(months),
    total: 0,
    average: 0,
    children: []
  }
}

/**
 * Regroupe les lignes plates (categorie/sous-categorie) renvoyees par
 * monthlyCategoryAnalysis en groupes racine + enfants, avec des totaux/
 * moyennes recalcules par groupe (une racine sans transaction directe
 * affiche quand meme le total de ses sous-categories).
 * `months` : les mois de la fenetre ({ key }), dans l'ordre d'affichage.
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
    // Sous-catégorie à 0 sur tous les mois : rien à lire, on ne l'affiche pas.
    group.children = group.children.filter(child => !isEmptyRow(child, months))
    group.children.sort(byNameFr)
  }

  return Array.from(groups.values())
    .filter(group => !isEmptyRow(group, months))
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
 * Ecart (en ecarts-types) au-dela duquel une case mensuelle est signalee
 * comme anomalie par rapport a la moyenne de sa ligne. 1.5 remontait beaucoup
 * trop de mois (environ 1 valeur sur 7 depasse deja ce seuil dans une
 * distribution normale) ; 2.5 ne retient que les ecarts vraiment marques.
 */
const ANOMALY_STDDEV_THRESHOLD = 2.5

/** En dessous de ce nombre de mois avec une valeur non nulle, l'ecart-type n'est pas assez fiable pour signaler une anomalie. */
const ANOMALY_MIN_NONZERO_MONTHS = 4

/** Moyenne et ecart-type (population) d'un tableau de valeurs numeriques. */
export function meanStdDev(values) {
  const nums = values.map(Number)
  const mean = nums.reduce((sum, v) => sum + v, 0) / (nums.length || 1)
  const variance = nums.reduce((sum, v) => sum + (v - mean) ** 2, 0) / (nums.length || 1)
  return { mean, stdDev: Math.sqrt(variance) }
}

/**
 * Signale (classe "monthly-anomaly") une case dont la valeur s'ecarte de plus
 * de ANOMALY_STDDEV_THRESHOLD ecarts-types de la moyenne des `rowValues`
 * (les valeurs des 12 mois de la meme ligne) — met en evidence les mois
 * atypiques (depense inhabituelle, revenu exceptionnel...) au premier coup
 * d'œil.
 *
 * Les mois a 0 (categorie pas utilisee ce mois-la) sont exclus du calcul de
 * la moyenne/ecart-type : sinon, sur une categorie utilisee occasionnellement,
 * les nombreux mois a 0 tirent la moyenne vers le bas et n'importe quel mois
 * avec une depense normale ressort comme "anomalie" — alors que ces mois a 0
 * sont deja mis en evidence separement (classe "zero-value"). Une valeur
 * elle-meme nulle n'est jamais signalee. Aucune case signalee si la ligne est
 * quasi constante (stdDev nul) ou si trop peu de mois ont une valeur pour
 * qu'un ecart-type soit fiable (voir ANOMALY_MIN_NONZERO_MONTHS).
 */
export function anomalyClass(value, rowValues) {
  if (!Number(value)) return {}
  const nonZero = rowValues.map(Number).filter(Boolean)
  if (nonZero.length < ANOMALY_MIN_NONZERO_MONTHS) return {}
  const { mean, stdDev } = meanStdDev(nonZero)
  if (!stdDev) return {}
  return { 'monthly-anomaly': Math.abs(Number(value) - mean) / stdDev > ANOMALY_STDDEV_THRESHOLD }
}

/**
 * Capital des prets deja rembourse (= vous appartient), mois par mois, aligne
 * sur les points du graphique "Evolution du solde" (12 mois historiques +
 * 12 mois previsionnels). Contrairement au solde, cette serie n'a rien
 * d'incertain : l'echeancier d'un pret a taux fixe est connu a l'avance, donc
 * pas de distinction historique/previsionnel necessaire ici, juste une somme
 * sur tous les prets pour chaque mois. Retourne null si l'utilisateur n'a
 * aucun pret, pour ne pas ajouter une ligne a plat inutile au graphique.
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
  return { text: `${diff > 0 ? '+' : ''}${formatAmount(diff)} sur ${periodLabel}`, tone: diff > 0 ? 'good' : 'bad' }
}

import { describe, it, expect } from 'vitest'
import {
  dashboardTodoItems,
  monthWindowLabel,
  savingsRateLabel,
  analysisGroup,
  anomalyClass,
  rowAnomalyClasses,
  balanceChangeOverPeriod,
  buildLoanOwnedSeries,
  isEmptyRow,
  last12MonthsRange,
  meanStdDev,
  monthDelta,
  mergeRowsByPayee,
  monthRange,
  yearAgoKpi,
  yearOverYearChange,
  yearOverYearCoverage
} from './dashboardAnalysis'
import { formatAmount } from './format'

const MONTHS = [{ key: '2026-01-01' }, { key: '2026-02-01' }]

describe('monthRange', () => {
  it('renvoie le premier et le dernier jour du mois', () => {
    expect(monthRange('2026-02-01')).toEqual({ from: '2026-02-01', to: '2026-02-28' })
    expect(monthRange('2024-02-01')).toEqual({ from: '2024-02-01', to: '2024-02-29' })
    expect(monthRange('2026-12-01')).toEqual({ from: '2026-12-01', to: '2026-12-31' })
  })
})

describe('last12MonthsRange', () => {
  it('couvre 12 mois pleins se terminant sur le mois donne, sans decalage UTC', () => {
    expect(last12MonthsRange(new Date(2026, 9, 1))).toEqual({ from: '2025-11-01', to: '2026-10-31' })
  })
})

describe('mergeRowsByPayee', () => {
  it('additionne les lignes d un meme tiers et garde la date la plus recente', () => {
    const merged = mergeRowsByPayee([
      { id: 1, payee: 'EDF', amount: '-10', date: '2026-01-05' },
      { id: 1, payee: 'EDF', amount: '-10', date: '2026-02-05' },
      { id: 2, payee: null, amount: '-3', date: '2026-01-01' },
      { id: 3, payee: null, amount: '-4', date: '2026-01-02' }
    ])
    expect(merged).toHaveLength(3)
    expect(merged[0]).toMatchObject({ payee: 'EDF', amount: -20, date: '2026-02-05' })
  })

  it('ne modifie pas les lignes recues', () => {
    const rows = [{ payee: 'A', amount: 1, date: '2026-01-01' }, { payee: 'A', amount: 2, date: '2026-01-02' }]
    mergeRowsByPayee(rows)
    expect(rows[0].amount).toBe(1)
  })
})

describe('analysisGroup', () => {
  it('regroupe les sous-categories sous leur racine et cumule les totaux', () => {
    const groups = analysisGroup([
      { categoryId: 1, parentId: null, name: 'Logement', values: { '2026-01-01': 100, '2026-02-01': 0 } },
      { categoryId: 2, parentId: 1, parentName: 'Logement', name: 'Loyer', values: { '2026-01-01': 500, '2026-02-01': 500 } }
    ], MONTHS)
    expect(groups).toHaveLength(1)
    expect(groups[0]).toMatchObject({ name: 'Logement', categoryId: 1, total: 1100, average: 550 })
    expect(groups[0].values).toEqual({ '2026-01-01': 600, '2026-02-01': 500 })
    expect(groups[0].children.map(c => c.name)).toEqual(['Loyer'])
  })

  it('cree le groupe racine meme sans transaction directe, et trie par nom', () => {
    const groups = analysisGroup([
      { categoryId: 5, parentId: 4, parentName: 'Transport', name: 'Essence', values: { '2026-01-01': 50 } },
      { categoryId: 9, parentId: 8, parentName: 'Alimentation', name: 'Courses', values: { '2026-02-01': 80 } }
    ], MONTHS)
    expect(groups.map(g => g.name)).toEqual(['Alimentation', 'Transport'])
    expect(groups[1]).toMatchObject({ categoryId: 4, total: 50 })
  })

  it('masque les lignes et groupes nuls sur toute la fenetre', () => {
    const groups = analysisGroup([
      { categoryId: 2, parentId: 1, parentName: 'Loisirs', name: 'Cinema', values: { '2026-01-01': 0 } },
      { categoryId: 3, parentId: 1, parentName: 'Loisirs', name: 'Sport', values: { '2026-01-01': 20 } },
      { categoryId: 7, parentId: null, name: 'Vide', values: {} }
    ], MONTHS)
    expect(groups).toHaveLength(1)
    expect(groups[0].children.map(c => c.name)).toEqual(['Sport'])
  })

  it("cumule le total de l'annee precedente et garde une ligne active seulement l'an dernier", () => {
    const groups = analysisGroup([
      { categoryId: 2, parentId: 1, parentName: 'Loisirs', name: 'Sport', values: { '2026-01-01': 0 }, previousTotal: 420 },
      { categoryId: 3, parentId: 1, parentName: 'Loisirs', name: 'Cinema', values: { '2026-01-01': 30 }, previousTotal: 10 },
      { categoryId: 4, parentId: 1, parentName: 'Loisirs', name: 'Rien', values: {}, previousTotal: 0 }
    ], MONTHS)
    expect(groups).toHaveLength(1)
    expect(groups[0]).toMatchObject({ total: 30, previousTotal: 430 })
    expect(groups[0].children.map(c => c.name)).toEqual(['Cinema', 'Sport'])
  })

  it('marque le groupe des transactions non categorisees', () => {
    const [group] = analysisGroup([{ categoryId: null, parentId: null, name: 'Non catégorisé', values: { '2026-01-01': 5 } }], MONTHS)
    expect(group).toMatchObject({ key: 'root-uncategorized', uncategorized: true })
  })
})

describe('isEmptyRow', () => {
  it('est vrai seulement si toutes les valeurs sont nulles ou absentes', () => {
    expect(isEmptyRow({ values: { '2026-01-01': '0' } }, MONTHS)).toBe(true)
    expect(isEmptyRow({}, MONTHS)).toBe(true)
    expect(isEmptyRow({ values: { '2026-02-01': '-1' } }, MONTHS)).toBe(false)
  })
})

describe('meanStdDev', () => {
  it('calcule moyenne et ecart-type de population', () => {
    expect(meanStdDev([2, 4, 4, 4, 5, 5, 7, 9])).toEqual({ mean: 5, stdDev: 2 })
  })

  it('ne divise pas par zero sur une liste vide', () => {
    expect(meanStdDev([])).toEqual({ mean: 0, stdDev: 0 })
  })
})

describe('rowAnomalyClasses', () => {
  it('donne, case par case, le même résultat que anomalyClass sur la ligne', () => {
    const values = { m1: 100, m2: 110, m3: 90, m4: 105, m5: 95, m6: 100, m7: 1000, m8: 0, m9: 100, m10: 98, m11: 102, m12: 104, m13: 96 }
    const rowValues = Object.values(values)
    const classes = rowAnomalyClasses(values, true)
    for (const [key, value] of Object.entries(values)) expect(classes[key]).toEqual(anomalyClass(value, rowValues, true))
    expect(classes.m7).toEqual({ 'monthly-anomaly': true, 'anomaly-favorable': true })
  })
})

describe('anomalyClass', () => {
  const row = [100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 1000]

  it('signale une valeur tres eloignee de la moyenne de la ligne', () => {
    expect(anomalyClass(1000, row)).toEqual({ 'monthly-anomaly': true, 'anomaly-favorable': false })
    expect(anomalyClass(100, row)).toEqual({ 'monthly-anomaly': false, 'anomaly-favorable': false })
  })

  it('marque favorable un écart dans le bon sens selon la ligne', () => {
    const lowMonth = [1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 100]
    // Dépense : un pic est défavorable, un creux favorable
    expect(anomalyClass(1000, row)['anomaly-favorable']).toBe(false)
    expect(anomalyClass(100, lowMonth)['anomaly-favorable']).toBe(true)
    // Revenu ou solde : l'inverse
    expect(anomalyClass(1000, row, true)['anomaly-favorable']).toBe(true)
    expect(anomalyClass(100, lowMonth, true)['anomaly-favorable']).toBe(false)
  })

  it('ne signale jamais une valeur nulle', () => {
    expect(anomalyClass(0, row)).toEqual({})
  })

  it('ignore les mois a zero et exige assez de mois renseignes', () => {
    expect(anomalyClass(1000, [100, 100, 1000, 0, 0, 0])).toEqual({})
  })

  it('ignore un écart de quelques centimes sur une ligne quasi constante', () => {
    const steady = [105.92, 105.92, 105.92, 105.92, 105.92, 105.92, 105.92, 105.88]
    expect(anomalyClass(105.88, steady)['monthly-anomaly']).toBe(false)
  })

  it('ne signale rien sur une ligne constante', () => {
    expect(anomalyClass(50, [50, 50, 50, 50, 50])).toEqual({})
  })
})

describe('buildLoanOwnedSeries', () => {
  const points = [{ date: '2025-12-01' }, { date: '2026-01-01' }, { date: '2026-02-01' }, { date: '2026-03-01' }]

  it('renvoie null sans pret', () => {
    expect(buildLoanOwnedSeries(points, [], [])).toBeNull()
  })

  it('cumule le capital rembourse : 0 avant le debut, principal une fois solde', () => {
    const loans = [{ principal: 1000 }]
    const schedules = [{ rows: [
      { date: '2026-01-15', remainingBalance: 600 },
      { date: '2026-02-15', remainingBalance: 0 }
    ] }]
    expect(buildLoanOwnedSeries(points, loans, schedules)).toEqual([0, 400, 1000, 1000])
  })

  it('ignore un pret sans echeancier', () => {
    expect(buildLoanOwnedSeries(points, [{ principal: 500 }], [{ rows: [] }])).toEqual([0, 0, 0, 0])
  })
})

describe('monthDelta', () => {
  // Intl insère une espace insécable fine avant « % » : texte attendu construit avec le même formateur.
  const pct = ratio => new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 1, signDisplay: 'exceptZero' }).format(ratio)

  it('compare le dernier mois au précédent, en bonne nouvelle quand une hausse est souhaitée', () => {
    expect(monthDelta([100, 200, 250], true)).toEqual({ text: `${pct(0.25)} vs mois préc.`, tone: 'good' })
    expect(monthDelta([200, 150], true)).toMatchObject({ tone: 'bad' })
  })

  it('inverse le ton pour les dépenses : une baisse est une bonne nouvelle', () => {
    expect(monthDelta([400, 300], false)).toMatchObject({ tone: 'good' })
    expect(monthDelta([300, 400], false)).toMatchObject({ tone: 'bad' })
  })

  it('se base sur la valeur absolue du mois précédent quand il est négatif (solde net)', () => {
    expect(monthDelta([-100, 50], true)).toMatchObject({ text: `${pct(1.5)} vs mois préc.`, tone: 'good' })
  })

  it('ne renvoie rien sans mois précédent exploitable ou pour une variation négligeable', () => {
    expect(monthDelta([], true)).toBeNull()
    expect(monthDelta([100], true)).toBeNull()
    expect(monthDelta([0, 100], true)).toBeNull()
    expect(monthDelta([10000, 10001], true)).toBeNull()
  })
})

describe('balanceChangeOverPeriod', () => {
  it('donne l’écart entre le premier et le dernier point', () => {
    expect(balanceChangeOverPeriod([1000, 500, 2500], '12 mois')).toEqual({ text: `+${formatAmount(1500)} sur 12 mois`, tone: 'good' })
    expect(balanceChangeOverPeriod([1000, 800], '12 mois')).toEqual({ text: `${formatAmount(-200)} sur 12 mois`, tone: 'bad' })
  })

  it('ne renvoie rien pour une série trop courte ou un écart de moins de 50 centimes', () => {
    expect(balanceChangeOverPeriod([1000], '12 mois')).toBeNull()
    expect(balanceChangeOverPeriod([1000, 1000.3], '12 mois')).toBeNull()
  })
})

describe('savingsRateLabel', () => {
  it('exprime le solde du mois en part des revenus', () => {
    expect(savingsRateLabel(2000, 360)).toBe('18 % des revenus')
    expect(savingsRateLabel(2000, -200)).toBe('-10 % des revenus')
  })

  it('ne calcule rien sans revenus', () => {
    expect(savingsRateLabel(0, -50)).toBeNull()
  })
})

describe('monthWindowLabel', () => {
  it('affiche les mois de début et de fin en abrégé', () => {
    expect(monthWindowLabel('2025-12-01', '2026-11-30')).toBe('déc. 2025 – nov. 2026')
  })
})

describe('dashboardTodoItems', () => {
  it('ne garde que les points non nuls, accordés au nombre', () => {
    const items = dashboardTodoItems({ pending: 4, uncategorized: 0, duplicates: 1 })
    expect(items.map(i => i.label)).toEqual(['4 transactions en attente', '1 doublon possible'])
    expect(items[0].to).toEqual({ path: '/transactions', query: { pending: '1' } })
  })

  it('renvoie une liste vide quand tout est à jour', () => {
    expect(dashboardTodoItems({})).toEqual([])
  })
})

/** Pourcentage signe tel que l'affiche l'appli (espace fine insecable, signe moins typographique). */
const pourcentage = ratio => new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 1, signDisplay: 'exceptZero' }).format(ratio)

describe('yearOverYearCoverage', () => {
  const fenetre = { previousFrom: '2024-11-01', previousTo: '2025-10-31' }

  it("compare si l'historique couvre toute l'annee precedente", () => {
    expect(yearOverYearCoverage({ ...fenetre, historyStart: '2023-05-02' })).toEqual({ available: true, partial: false })
    // Releve commence le 2 du premier mois : ce mois est couvert, pas de comparaison partielle.
    expect(yearOverYearCoverage({ ...fenetre, historyStart: '2024-11-02' })).toEqual({ available: true, partial: false })
  })

  it("signale une comparaison partielle si l'historique commence en cours d'annee precedente", () => {
    expect(yearOverYearCoverage({ ...fenetre, historyStart: '2025-03-15' })).toEqual({ available: true, partial: true })
  })

  it("ne compare rien sans historique avant la fenetre affichee", () => {
    expect(yearOverYearCoverage({ ...fenetre, historyStart: '2025-11-02' }).available).toBe(false)
    expect(yearOverYearCoverage({ ...fenetre, historyStart: null }).available).toBe(false)
    expect(yearOverYearCoverage(null).available).toBe(false)
  })
})

describe('yearOverYearChange', () => {
  const surUnAn = (diff) => `${diff > 0 ? '+' : ''}${formatAmount(diff)} sur un an`

  it('exprime une depense en pourcentage, hausse defavorable, avec l ecart en euros en infobulle', () => {
    expect(yearOverYearChange(860.2, 742.1, 'expense')).toEqual({ text: pourcentage(860.2 / 742.1 - 1), tone: 'bad', title: surUnAn(860.2 - 742.1) })
    expect(yearOverYearChange(50, 100, 'expense')).toEqual({ text: pourcentage(-0.5), tone: 'good', title: surUnAn(-50) })
  })

  it('exprime un revenu en pourcentage, hausse favorable', () => {
    expect(yearOverYearChange(2400, 2000, 'income')).toEqual({ text: pourcentage(0.2), tone: 'good', title: surUnAn(400) })
  })

  it("compare le solde en euros (un pourcentage n'a pas de sens sur un montant de signe quelconque)", () => {
    expect(yearOverYearChange(500, -200, 'net')).toMatchObject({ text: `+${formatAmount(700)}`, tone: 'good' })
    expect(yearOverYearChange(-100, 300, 'net')).toMatchObject({ text: formatAmount(-400), tone: 'bad' })
  })

  it('laisse en couleur neutre un écart faible en euros, même en pourcentage élevé', () => {
    expect(yearOverYearChange(30, 24, 'expense')).toEqual({ text: pourcentage(0.25), tone: '', title: surUnAn(6) })
    expect(yearOverYearChange(108, 100, 'income')).toMatchObject({ tone: '' })
    expect(yearOverYearChange(5, -3, 'net')).toMatchObject({ text: `+${formatAmount(8)}`, tone: '' })
    expect(yearOverYearChange(34, 24, 'expense')).toMatchObject({ tone: 'bad' })
  })

  it("distingue une ligne nouvelle, disparue, inchangee ou sans objet", () => {
    expect(yearOverYearChange(80, 0, 'expense')).toEqual({ text: "Absent l'an dernier", tone: 'new', title: "Absent l'an dernier" })
    expect(yearOverYearChange(0, 35, 'expense')).toEqual({ text: 'Plus aucun montant cette année', tone: 'gone', title: `Plus aucun montant cette année (${surUnAn(-35).replace(' sur un an', '')} sur un an)` })
    expect(yearOverYearChange(100, 100, 'income')).toMatchObject({ text: '=', tone: '' })
    expect(yearOverYearChange(0, 0, 'income')).toMatchObject({ text: '—', tone: '' })
    expect(yearOverYearChange('12.5', '12.5', 'net')).toMatchObject({ text: '=', tone: '' })
  })
})

describe('yearAgoKpi', () => {
  it("compare le mois affiche au meme mois un an plus tot", () => {
    expect(yearAgoKpi('920', '1000', 'expense', 'oct. 2025'))
      .toEqual({ text: `vs oct. 2025 : ${pourcentage(-0.08)}`, tone: 'good', title: `oct. 2025 : ${formatAmount(1000)}` })
  })

  it('garde un pourcentage (pas de cercle « disparu ») quand le mois est tombé à zéro', () => {
    expect(yearAgoKpi(0, 500, 'income', 'oct. 2025')).toMatchObject({ text: `vs oct. 2025 : ${pourcentage(-1)}`, tone: 'bad' })
    expect(yearAgoKpi(0, 500, 'expense', 'oct. 2025')).toMatchObject({ tone: 'good' })
  })

  it("ne dit rien si ce mois n'est pas couvert, s'il était nul un an plus tôt ou si les deux sont nuls", () => {
    expect(yearAgoKpi(920, null, 'expense', 'oct. 2025')).toBeNull()
    expect(yearAgoKpi(920, 0, 'expense', 'oct. 2025')).toBeNull()
    expect(yearAgoKpi(920, undefined, 'expense', 'oct. 2025')).toBeNull()
    expect(yearAgoKpi(0, 0, 'income', 'oct. 2025')).toBeNull()
  })
})

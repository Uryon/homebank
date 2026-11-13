import { describe, it, expect } from 'vitest'
import {
  analysisGroup,
  anomalyClass,
  balanceChangeOverPeriod,
  buildLoanOwnedSeries,
  isEmptyRow,
  last12MonthsRange,
  meanStdDev,
  monthDelta,
  mergeRowsByPayee,
  monthRange
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

describe('anomalyClass', () => {
  const row = [100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 1000]

  it('signale une valeur tres eloignee de la moyenne de la ligne', () => {
    expect(anomalyClass(1000, row)).toEqual({ 'monthly-anomaly': true })
    expect(anomalyClass(100, row)).toEqual({ 'monthly-anomaly': false })
  })

  it('ne signale jamais une valeur nulle', () => {
    expect(anomalyClass(0, row)).toEqual({})
  })

  it('ignore les mois a zero et exige assez de mois renseignes', () => {
    expect(anomalyClass(1000, [100, 100, 1000, 0, 0, 0])).toEqual({})
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

import { describe, expect, it } from 'vitest'
import { drillDownMatcher, drillDownQuery, hasSmoothedContribution, isSmoothedInReports, matchesDrillDown, parseDrillDown } from './drillDown'

describe('drillDownQuery', () => {
  it('traduit un filtre de catégorie du tableau de bord en paramètres d\'adresse', () => {
    expect(drillDownQuery({ from: '2026-06-01', to: '2026-06-30', flow: 'expense', filters: { rootCategoryId: 4 }, label: 'Alimentation · juin 2026' }))
      .toEqual({ from: '2026-06-01', to: '2026-06-30', flow: 'expense', rootCategory: '4', label: 'Alimentation · juin 2026' })
  })

  it('passe par le filtre « Sans catégorie » existant de la page', () => {
    expect(drillDownQuery({ from: '2026-01-01', to: '2026-12-31', filters: { uncategorized: true } }).uncategorized).toBe('1')
  })
})

describe('drillDownMatcher', () => {
  const periode = { from: '2026-06-01', to: '2026-07-31', categoryId: 7, rootCategoryId: null }
  const depense = { id: 1, date: '2026-06-05', amount: -100, categoryId: 7 }
  const remboursement = { id: 2, date: '2026-06-09', amount: 20, categoryId: 7 }
  const juillet = { id: 3, date: '2026-07-09', amount: 30, categoryId: 7 }
  const liste = [depense, remboursement, juillet]
  const garde = (flow) => liste.filter(drillDownMatcher(liste, { ...periode, flow }, new Map(), '2026-12-31')).map(t => t.id)

  it('garde le remboursement avec la dépense dont il est déduit', () => {
    expect(garde('expense')).toEqual([1, 2])
  })

  it('classe chaque mois selon son net', () => {
    expect(garde('income')).toEqual([3])
  })

  it('sans sens demandé, garde tout ce qui compte sur la période', () => {
    expect(garde(null)).toEqual([1, 2, 3])
  })
})

describe('parseDrillDown', () => {
  it('relit les critères transmis', () => {
    const drill = parseDrillDown({ from: '2026-06-01', to: '2026-06-30', flow: 'income', category: '7', label: 'Salaire' })
    expect(drill).toEqual({ from: '2026-06-01', to: '2026-06-30', flow: 'income', categoryId: 7, rootCategoryId: null, label: 'Salaire' })
  })

  it('ignore une adresse sans période valide', () => {
    expect(parseDrillDown({})).toBeNull()
    expect(parseDrillDown({ from: '2026-06-30', to: '2026-06-01' })).toBeNull()
    expect(parseDrillDown({ from: 'hier', to: '2026-06-01' })).toBeNull()
  })

  it('ignore un sens ou un identifiant invalide', () => {
    const drill = parseDrillDown({ from: '2026-06-01', to: '2026-06-30', flow: 'tout', category: 'abc' })
    expect(drill.flow).toBeNull()
    expect(drill.categoryId).toBeNull()
  })
})

describe('matchesDrillDown', () => {
  const categoriesById = new Map([[4, { id: 4, parentId: null }], [9, { id: 9, parentId: 4 }], [12, { id: 12, parentId: null }]])
  const drill = parseDrillDown({ from: '2026-06-01', to: '2026-06-30', flow: 'expense', rootCategory: '4' })
  const tx = (overrides) => ({ date: '2026-06-15', amount: -20, categoryId: 9, transferTransactionId: null, ...overrides })

  it('retient une dépense de la période dans une sous-catégorie de la racine', () => {
    expect(matchesDrillDown(tx({}), drill, categoriesById)).toBe(true)
    expect(matchesDrillDown(tx({ categoryId: 4 }), drill, categoriesById)).toBe(true)
  })

  it('écarte hors période, autre sens, autre catégorie et virements', () => {
    expect(matchesDrillDown(tx({ date: '2026-07-01' }), drill, categoriesById)).toBe(false)
    expect(matchesDrillDown(tx({ amount: 20 }), drill, categoriesById)).toBe(false)
    expect(matchesDrillDown(tx({ categoryId: 12 }), drill, categoriesById)).toBe(false)
    expect(matchesDrillDown(tx({ transferTransactionId: 3 }), drill, categoriesById)).toBe(false)
  })

  it('écarte, comme les rapports, les exceptionnelles, ajustements de solde et transactions futures', () => {
    expect(matchesDrillDown(tx({ exceptional: true }), drill, categoriesById)).toBe(false)
    expect(matchesDrillDown(tx({ balanceAdjustment: true }), drill, categoriesById)).toBe(false)
    expect(matchesDrillDown(tx({}), drill, categoriesById, '2026-06-10')).toBe(false)
    expect(matchesDrillDown(tx({}), drill, categoriesById, '2026-06-20')).toBe(true)
  })
})

describe('isSmoothedInReports', () => {
  it('repère les transactions des catégories annuelles', () => {
    const byId = new Map([[3, { id: 3, annualized: true }], [5, { id: 5 }]])
    expect(isSmoothedInReports({ categoryId: 3 }, byId)).toBe(true)
    expect(isSmoothedInReports({ categoryId: 5 }, byId)).toBe(false)
    expect(isSmoothedInReports({ categoryId: null }, byId)).toBe(false)
  })
})

describe('hasSmoothedContribution', () => {
  const byId = new Map([[3, { id: 3, parentId: null, annualized: true }], [5, { id: 5, parentId: null }]])
  const drill = parseDrillDown({ from: '2026-10-01', to: '2026-10-31', flow: 'expense' })

  it("détecte une transaction annuelle datée jusqu'à 11 mois avant la période", () => {
    expect(hasSmoothedContribution([{ date: '2025-11-20', amount: -980, categoryId: 3 }], drill, byId, '2026-11-15')).toBe(true)
  })

  it('ignore une transaction annuelle trop ancienne ou une catégorie ordinaire', () => {
    expect(hasSmoothedContribution([{ date: '2025-10-20', amount: -980, categoryId: 3 }], drill, byId, '2026-11-15')).toBe(false)
    expect(hasSmoothedContribution([{ date: '2026-10-05', amount: -40, categoryId: 5 }], drill, byId, '2026-11-15')).toBe(false)
  })
})

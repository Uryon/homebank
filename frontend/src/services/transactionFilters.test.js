import { describe, expect, it } from 'vitest'
import { buildSearchIndex, filterByStatusAndPeriod, filterBySearch } from './transactionFilters'
import { parseAmountQuery } from './search'

const transactions = [
  { id: 1, date: '2026-01-05', payee: 'Bouygues Telecom', amount: -29.99, categoryId: 10, validated: true, memo: 'forfait' },
  { id: 2, date: '2026-01-20', payee: 'Bouygues Telecom', amount: -15, categoryId: 10, validated: false },
  { id: 3, date: '2026-02-01', payee: 'CAF', amount: 120.6, categoryId: null, validated: true },
  { id: 4, date: '2025-02-10', payee: 'Carrefour', amount: -0.6, categoryId: 11, validated: true, bankLabel: 'CB CARREFOUR 0902' }
]
const labels = new Map([[10, 'Abonnements : Téléphone'], [11, 'Alimentation : Courses']])
const names = new Map([[10, 'Téléphone'], [11, 'Courses']])
const index = buildSearchIndex(transactions, labels, names)
const search = (query) => filterBySearch(transactions, query, parseAmountQuery(query), index).map(t => t.id)

describe('filterByStatusAndPeriod', () => {
  const none = { pendingOnly: false, uncategorizedOnly: false, drillDownMatch: null, month: null, year: null }

  it('renvoie la liste telle quelle sans aucun filtre', () => {
    expect(filterByStatusAndPeriod(transactions, none)).toBe(transactions)
  })

  it('combine statut, catégorie manquante et période', () => {
    expect(filterByStatusAndPeriod(transactions, { ...none, pendingOnly: true }).map(t => t.id)).toEqual([2])
    expect(filterByStatusAndPeriod(transactions, { ...none, uncategorizedOnly: true }).map(t => t.id)).toEqual([3])
    expect(filterByStatusAndPeriod(transactions, { ...none, month: 2 }).map(t => t.id)).toEqual([3, 4])
    expect(filterByStatusAndPeriod(transactions, { ...none, month: 2, year: 2026 }).map(t => t.id)).toEqual([3])
  })

  it('applique les critères du tableau de bord', () => {
    expect(filterByStatusAndPeriod(transactions, { ...none, drillDownMatch: t => t.amount > 0 }).map(t => t.id)).toEqual([3])
  })
})

describe('filterBySearch', () => {
  it('cherche dans le tiers, la catégorie, le mémo et le libellé du relevé', () => {
    expect(search('bouygues')).toEqual([1, 2])
    expect(search('téléphone')).toEqual([1, 2])
    expect(search('forfait')).toEqual([1])
    expect(search('0902')).toEqual([4])
  })

  it('exige tous les critères séparés par « ; »', () => {
    expect(search('bouygues;29,99')).toEqual([1])
  })

  it('retrouve un montant tapé avec une virgule ou un point (« contient » : 120,60 € aussi)', () => {
    expect(search('0,60')).toEqual([3, 4])
    expect(search('0.60')).toEqual([3, 4])
  })

  it('compare le montant signé avec un opérateur', () => {
    expect(search('> 100')).toEqual([3])
  })

  it('ne retient que la correspondance exacte entre guillemets', () => {
    expect(search('"Courses"')).toEqual([4])
    expect(search('"Cour"')).toEqual([])
  })
})

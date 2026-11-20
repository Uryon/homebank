import { describe, expect, it } from 'vitest'
import { buildPayeeSearchIndex, categoryIdsByPayeeId, filterPayees, suggestedCategoryIdFor } from './payeeFilters'

const payees = [
  { id: 1, name: 'CAF', pattern: 'VIR CAF', amount: null, categoryId: 10 },
  { id: 2, name: 'Décaféiné', pattern: 'DECAF', amount: null, categoryId: null },
  { id: 3, name: 'Bouygues', pattern: 'BOUYGUES', amount: 29.99, categoryId: 11 },
  { id: 4, name: 'Bouygues', pattern: 'BOUYGUES', amount: 15.99, categoryId: 11 }
]
const labels = new Map([[10, 'Revenus : Allocations'], [11, 'Logement : Téléphone']])
const searchIndex = buildPayeeSearchIndex(payees, id => labels.get(id) || '—')

const filter = (query, options = {}) => filterPayees(payees, {
  query, withoutCategoryOnly: false, unusedOnly: false, isUnused: () => false, searchIndex, ...options
}).map(p => p.id)

describe('filterPayees', () => {
  it('sans recherche ni filtre, renvoie tous les tiers', () => {
    expect(filter('')).toEqual([1, 2, 3, 4])
  })

  it('« contient » cherche dans le nom, le motif et la catégorie, sans tenir compte des accents', () => {
    expect(filter('caf')).toEqual([1, 2])
    expect(filter('telephone')).toEqual([3, 4])
  })

  it('un terme entre guillemets exige une correspondance exacte', () => {
    expect(filter('"CAF"')).toEqual([1])
  })

  it('plusieurs critères séparés par « ; » sont tous exigés, montant à la virgule compris', () => {
    expect(filter('Bouygues;29,99')).toEqual([3])
  })

  it('filtres « Sans catégorie » et « Inutilisés »', () => {
    expect(filter('', { withoutCategoryOnly: true })).toEqual([2])
    expect(filter('', { unusedOnly: true, isUnused: p => p.id === 4 })).toEqual([4])
  })
})

describe('suggestedCategoryIdFor', () => {
  const transactions = [
    { payeeId: 2, categoryId: 20 },
    { payeeId: 2, categoryId: null },
    { payeeId: 5, categoryId: 20 },
    { payeeId: 5, categoryId: 21 },
    { payeeId: 6, categoryId: 22 },
    { payeeId: null, categoryId: 20 }
  ]
  const idsByPayee = categoryIdsByPayeeId(transactions)
  const categoriesById = new Map([[20, { id: 20 }], [21, { id: 21 }], [22, { id: 22, hidden: true }]])

  it('suggère la catégorie unanime des transactions catégorisées, sans compter les autres', () => {
    expect(suggestedCategoryIdFor({ id: 2, categoryId: null }, idsByPayee, categoriesById)).toBe(20)
  })

  it('rien si le tiers a déjà une catégorie, si les catégories divergent ou si elle est masquée', () => {
    expect(suggestedCategoryIdFor({ id: 2, categoryId: 21 }, idsByPayee, categoriesById)).toBeNull()
    expect(suggestedCategoryIdFor({ id: 5, categoryId: null }, idsByPayee, categoriesById)).toBeNull()
    expect(suggestedCategoryIdFor({ id: 6, categoryId: null }, idsByPayee, categoriesById)).toBeNull()
    expect(suggestedCategoryIdFor({ id: 7, categoryId: null }, idsByPayee, categoriesById)).toBeNull()
  })
})

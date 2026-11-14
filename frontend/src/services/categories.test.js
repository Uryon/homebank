import { describe, it, expect } from 'vitest'
import { flattenCategories, needsCategory } from './categories'

describe('flattenCategories', () => {
  it('une racine sans enfant reste selectable', () => {
    const result = flattenCategories([{ id: 1, name: 'Loisirs', type: 'EXPENSE', parentId: null }])

    expect(result).toEqual([
      { id: 1, label: 'Loisirs', type: 'EXPENSE', selectable: true, hidden: false }
    ])
  })

  it('une racine avec des enfants devient non selectable, seuls les enfants le restent', () => {
    const categories = [
      { id: 1, name: 'Alimentation', type: 'EXPENSE', parentId: null },
      { id: 2, name: 'Restaurant', type: 'EXPENSE', parentId: 1 }
    ]

    const result = flattenCategories(categories)

    expect(result).toEqual([
      { id: 1, label: 'Alimentation', type: 'EXPENSE', selectable: false, hidden: false },
      { id: 2, label: 'Alimentation : Restaurant', type: 'EXPENSE', selectable: true, hidden: false }
    ])
  })

  it('reprend le flag "hidden" de chaque categorie', () => {
    const categories = [{ id: 1, name: 'Ancienne categorie', type: 'EXPENSE', parentId: null, hidden: true }]

    const result = flattenCategories(categories)

    expect(result[0].hidden).toBe(true)
  })

  it('indexe les enfants par parentId sans depender de leur ordre dans le tableau source', () => {
    const categories = [
      { id: 2, name: 'Restaurant', type: 'EXPENSE', parentId: 1 }, // enfant avant sa racine
      { id: 1, name: 'Alimentation', type: 'EXPENSE', parentId: null }
    ]

    const result = flattenCategories(categories)

    expect(result.map(r => r.id)).toEqual([1, 2])
    expect(result[1].label).toBe('Alimentation : Restaurant')
  })

  it('renvoie une liste vide pour une liste de categories vide', () => {
    expect(flattenCategories([])).toEqual([])
  })
})

describe('needsCategory', () => {
  it('attend une catégorie pour une dépense ou un revenu sans catégorie', () => {
    expect(needsCategory({ categoryId: null })).toBe(true)
    expect(needsCategory({ categoryId: 4 })).toBe(false)
  })

  it("n'attend pas de catégorie pour un virement interne ni un ajustement de solde", () => {
    expect(needsCategory({ categoryId: null, transferTransactionId: 12 })).toBe(false)
    expect(needsCategory({ categoryId: null, balanceAdjustment: true })).toBe(false)
  })
})

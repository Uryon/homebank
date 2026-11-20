import { describe, it, expect } from 'vitest'
import { CATEGORY_TYPE_OPTIONS, categoryChipInfo, categoryIconPrefix, categoryTypeLabel, flattenCategories, needsCategory, transactionSearchForCategory, transactionSearchForPayee } from './categories'

describe('flattenCategories', () => {
  it('garde visible une catégorie de troisième niveau, rattachée à sa racine', () => {
    const categories = [
      { id: 1, name: 'Loisirs', type: 'EXPENSE', parentId: null },
      { id: 2, name: 'Sport', type: 'EXPENSE', parentId: 1 },
      { id: 3, name: 'Tennis', type: 'EXPENSE', parentId: 2 }
    ]

    expect(flattenCategories(categories).map(c => c.label)).toEqual(['Loisirs', 'Loisirs : Sport', 'Loisirs : Tennis'])
  })

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

describe('categoryChipInfo', () => {
  const byId = new Map([
    [3, { id: 3, name: 'Alimentation', icon: '🍽', parentId: null }],
    [8, { id: 8, name: 'Restaurant', icon: '🍔', parentId: 3 }],
    [9, { id: 9, name: 'Divers', parentId: null }]
  ])

  it("une sous-catégorie prend la teinte et l'icône de sa racine", () => {
    expect(categoryChipInfo(8, byId)).toEqual({
      name: 'Restaurant', parent: 'Alimentation', icon: '🍽', hue: (3 * 47) % 360, path: 'Alimentation › Restaurant'
    })
  })

  it("une racine sans icône s'affiche seule, sans parent", () => {
    expect(categoryChipInfo(9, byId)).toEqual({ name: 'Divers', parent: '', icon: '', hue: (9 * 47) % 360, path: 'Divers' })
  })

  it('renvoie null sans catégorie ou pour une catégorie inconnue', () => {
    expect(categoryChipInfo(null, byId)).toBeNull()
    expect(categoryChipInfo(42, byId)).toBeNull()
  })
})

describe('liens vers les transactions', () => {
  it('cherche le libellé exact de la catégorie, racine comprise', () => {
    expect(transactionSearchForCategory({ label: 'Alimentation : Bio', selectable: true })).toBe('"Alimentation : Bio"')
    expect(transactionSearchForCategory({ label: 'Alimentation', selectable: false })).toBe('"Alimentation"')
  })

  it('cherche le nom exact d’un tiers', () => {
    expect(transactionSearchForPayee({ name: 'Carrefour' })).toBe('"Carrefour"')
  })
})

describe('categoryTypeLabel', () => {
  it('donne le libellé de chaque type proposé, et rend tel quel un type inconnu', () => {
    expect(CATEGORY_TYPE_OPTIONS.map(o => categoryTypeLabel(o.value))).toEqual(['Dépense', 'Revenu', 'Les deux'])
    expect(categoryTypeLabel('AUTRE')).toBe('AUTRE')
    expect(categoryTypeLabel(undefined)).toBeUndefined()
  })
})

describe('categoryIconPrefix', () => {
  it("préfixe l'icône suivie d'une espace, rien sans icône", () => {
    expect(categoryIconPrefix('🛒')).toBe('🛒 ')
    expect(categoryIconPrefix('')).toBe('')
    expect(categoryIconPrefix(null)).toBe('')
  })
})

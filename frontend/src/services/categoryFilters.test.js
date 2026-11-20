import { describe, expect, it } from 'vitest'
import { categoryFullLabel, filterCategoryRoots, hiddenCategoriesMatching, mergeableTargets, visibleChildrenByRoot } from './categoryFilters'
import { childrenByRootId } from './categories'
import { parseSearchTerms } from './search'

const categories = [
  { id: 1, name: 'Alimentation', parentId: null, icon: null },
  { id: 2, name: 'Courses', parentId: 1, icon: null },
  { id: 3, name: 'Café', parentId: 1, icon: null },
  { id: 4, name: 'Restaurant', parentId: 1, icon: null, hidden: true },
  { id: 5, name: 'CAF', parentId: null, icon: null },
  { id: 6, name: 'Ancienne', parentId: null, icon: null, hidden: true },
  { id: 7, name: 'Logement', parentId: null, icon: null }
]
const roots = categories.filter(c => !c.parentId)
const byParent = childrenByRootId(categories)
const children = id => byParent.get(id) || []
const byId = new Map(categories.map(c => [c.id, c]))
const terms = query => parseSearchTerms(query)

describe('filtres de la page Catégories', () => {
  it('une racine est retenue si elle ou une de ses sous-catégories répond à la recherche', () => {
    expect(filterCategoryRoots(roots, terms('cours'), children).map(r => r.id)).toEqual([1])
    expect(filterCategoryRoots(roots, terms('caf'), children).map(r => r.id)).toEqual([1, 5])
    expect(filterCategoryRoots(roots, terms('"CAF"'), children).map(r => r.id)).toEqual([5])
  })

  it('sous-catégories visibles : jamais les masquées ; toutes si la racine répond, sinon celles qui répondent', () => {
    expect(visibleChildrenByRoot(roots, [], children).get(1).map(c => c.id)).toEqual([2, 3])
    expect(visibleChildrenByRoot(roots, terms('alim'), children).get(1).map(c => c.id)).toEqual([2, 3])
    expect(visibleChildrenByRoot(roots, terms('cafe'), children).get(1).map(c => c.id)).toEqual([3])
  })

  it('encart des masquées : libellé complet, trié, filtré par la recherche', () => {
    // « Alimentation : Restaurant » avant « Ancienne » : tri sur le libellé complet.
    expect(hiddenCategoriesMatching(categories, [], byId).map(c => c.id)).toEqual([4, 6])
    expect(hiddenCategoriesMatching(categories, terms('alimentation'), byId).map(c => c.id)).toEqual([4])
    expect(categoryFullLabel(byId.get(4), byId)).toBe('Alimentation : Restaurant')
  })
})

describe('mergeableTargets', () => {
  const targets = ids => mergeableTargets(ids.map(id => byId.get(id)), new Set(ids), byId, children).map(c => c.id)

  it('une sous-catégorie dont la parente est aussi cochée ne peut pas être la cible', () => {
    expect(targets([1, 2])).toEqual([1])
  })

  it('avec une racine qui a des sous-catégories, seule une racine peut être la cible', () => {
    expect(targets([1, 7])).toEqual([1, 7])
    expect(targets([1, 7, 3]).includes(3)).toBe(false)
  })

  it('entre sous-catégories, chacune peut être la cible', () => {
    expect(targets([2, 3])).toEqual([2, 3])
  })
})

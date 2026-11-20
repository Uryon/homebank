import { describe, expect, it, vi } from 'vitest'
import { isEmptyRestorePlan, planCategoryStateRestore, restoreCategoryState, snapshotCategoryState } from './categoryRestore'

const before = snapshotCategoryState({
  categories: [
    { id: 1, name: 'Courses', type: 'EXPENSE', parentId: null },
    { id: 2, name: 'Alimentation', type: 'EXPENSE', parentId: null, annualized: true },
    { id: 3, name: 'Bio', type: 'EXPENSE', parentId: 2, hidden: true },
    { id: 4, name: 'Marché', type: 'EXPENSE', parentId: 2 }
  ],
  transactions: [{ id: 10, categoryId: 2 }, { id: 11, categoryId: 3 }, { id: 12, categoryId: 1 }],
  payees: [{ id: 20, name: 'Biocoop', pattern: 'BIOCOOP', amount: null, categoryId: 3 }]
})

// Après fusion de « Alimentation » (et de sa sous-catégorie Bio) dans « Courses » : Marché déplacée sous Courses.
const after = {
  categories: [
    { id: 1, name: 'Courses', type: 'EXPENSE', parentId: null },
    { id: 4, name: 'Marché', type: 'EXPENSE', parentId: 1 }
  ],
  transactions: [{ id: 10, categoryId: 1 }, { id: 11, categoryId: 1 }, { id: 12, categoryId: 1 }],
  payees: [{ id: 20, name: 'Biocoop', pattern: 'BIOCOOP', amount: null, categoryId: 1 }]
}

describe('planCategoryStateRestore', () => {
  it('liste les catégories à recréer (parents en premier), à remettre en place, et les réaffectations', () => {
    const plan = planCategoryStateRestore(before, after)
    expect(plan.toCreate.map(c => c.id)).toEqual([2, 3])
    expect(plan.toUpdate.map(c => c.id)).toEqual([4])
    expect(plan.transactionGroups).toEqual([[2, [10]], [3, [11]]])
    expect(plan.payeesToRestore.map(p => p.id)).toEqual([20])
  })

  it('un état inchangé ne produit aucune étape', () => {
    expect(isEmptyRestorePlan(planCategoryStateRestore(before, {
      categories: before.categories,
      transactions: [{ id: 10, categoryId: 2 }],
      payees: before.payees
    }))).toBe(true)
  })
})

describe('restoreCategoryState', () => {
  it('recrée avec les nouveaux ids et y rattache enfants, transactions et tiers', async () => {
    let nextId = 100
    const api = {
      categories: { create: vi.fn(async () => ({ id: nextId++ })), update: vi.fn(), toggleHidden: vi.fn(), toggleAnnualized: vi.fn() },
      transactions: { bulkUpdate: vi.fn() },
      payees: { update: vi.fn() }
    }
    await restoreCategoryState(planCategoryStateRestore(before, after), api)

    expect(api.categories.create.mock.calls.map(([p]) => [p.name, p.parentId])).toEqual([['Alimentation', null], ['Bio', 100]])
    expect(api.categories.toggleAnnualized).toHaveBeenCalledWith(100)
    expect(api.categories.toggleHidden).toHaveBeenCalledWith(101)
    expect(api.categories.update).toHaveBeenCalledWith(4, expect.objectContaining({ parentId: 100 }))
    expect(api.transactions.bulkUpdate).toHaveBeenCalledWith({ ids: [10], categoryIdProvided: true, categoryId: 100 })
    expect(api.transactions.bulkUpdate).toHaveBeenCalledWith({ ids: [11], categoryIdProvided: true, categoryId: 101 })
    expect(api.payees.update).toHaveBeenCalledWith(20, expect.objectContaining({ categoryId: 101 }))
  })
})

describe('suppression d’une catégorie avec sous-catégorie et tiers lié', () => {
  it('recrée la catégorie et sa sous-catégorie, et rattache le tiers', async () => {
    const avant = snapshotCategoryState({
      categories: [
        { id: 1, name: 'Loisirs', type: 'EXPENSE', parentId: null, icon: '🎮' },
        { id: 2, name: 'Cinéma', type: 'EXPENSE', parentId: 1 }
      ],
      transactions: [],
      payees: [{ id: 5, name: 'UGC', pattern: 'UGC', amount: null, categoryId: 2 }]
    })
    const apres = { categories: [], transactions: [], payees: [{ id: 5, name: 'UGC', pattern: 'UGC', amount: null, categoryId: null }] }
    let nextId = 50
    const api = {
      categories: { create: vi.fn(async () => ({ id: nextId++ })), update: vi.fn(), toggleHidden: vi.fn(), toggleAnnualized: vi.fn() },
      transactions: { bulkUpdate: vi.fn() },
      payees: { update: vi.fn() }
    }
    await restoreCategoryState(planCategoryStateRestore(avant, apres), api)
    expect(api.categories.create).toHaveBeenCalledWith({ name: 'Loisirs', icon: '🎮', type: 'EXPENSE', parentId: null })
    expect(api.categories.create).toHaveBeenCalledWith({ name: 'Cinéma', icon: undefined, type: 'EXPENSE', parentId: 50 })
    expect(api.payees.update).toHaveBeenCalledWith(5, { name: 'UGC', pattern: 'UGC', amount: null, categoryId: 51 })
    expect(api.transactions.bulkUpdate).not.toHaveBeenCalled()
  })
})

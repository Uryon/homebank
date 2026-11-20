import { describe, expect, it, vi } from 'vitest'
import { planPayeeMergeRestore, restorePayeeMerge } from './payeeMergeRestore'

const payees = [
  { id: 1, name: 'Carrefour', pattern: 'CARREFOUR', amount: null, categoryId: null },
  { id: 2, name: 'Carrefour Market', pattern: 'CARREFOUR MARKET', amount: null, categoryId: 7 },
  { id: 3, name: 'Lidl', pattern: 'LIDL', amount: null, categoryId: 7 }
]
const transactions = [
  { id: 10, payeeId: 1 }, { id: 11, payeeId: 2 }, { id: 12, payeeId: 2 }, { id: 13, payeeId: 3 }
]

function fakeApi(remainingIds) {
  let nextId = 100
  return {
    payees: {
      create: vi.fn(async () => ({ id: nextId++ })),
      update: vi.fn(),
      list: vi.fn(async () => remainingIds.map(id => ({ id })))
    },
    transactions: { bulkUpdate: vi.fn() }
  }
}

describe('planPayeeMergeRestore', () => {
  it('retient les tiers source avec leurs transactions et l’état du tiers conservé', () => {
    const plan = planPayeeMergeRestore({ payees, transactions }, [2], 1)
    expect(plan.target).toEqual({ id: 1, name: 'Carrefour', pattern: 'CARREFOUR', amount: null, categoryId: null })
    expect(plan.sources).toEqual([
      { fields: { name: 'Carrefour Market', pattern: 'CARREFOUR MARKET', amount: null, categoryId: 7 }, transactionIds: [11, 12] }
    ])
  })
})

describe('restorePayeeMerge', () => {
  it('remet le tiers conservé, recrée la source et lui rend ses transactions par id', async () => {
    const api = fakeApi([1, 100])
    await restorePayeeMerge(planPayeeMergeRestore({ payees, transactions }, [2], 1), api)
    expect(api.payees.update).toHaveBeenCalledWith(1, { name: 'Carrefour', pattern: 'CARREFOUR', amount: null, categoryId: null })
    expect(api.payees.create).toHaveBeenCalledTimes(1)
    expect(api.transactions.bulkUpdate).toHaveBeenCalledWith({ ids: [11, 12], payeeProvided: true, payeeId: 100 })
  })

  it('recrée le tiers conservé s’il a été nettoyé comme orphelin', async () => {
    const api = fakeApi([100])
    await restorePayeeMerge(planPayeeMergeRestore({ payees, transactions }, [2], 1), api)
    expect(api.payees.create).toHaveBeenLastCalledWith({ name: 'Carrefour', pattern: 'CARREFOUR', amount: null, categoryId: null })
  })
})

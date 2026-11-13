import { describe, expect, it } from 'vitest'
import { nextSort, sortPayees, usageByPayeeId } from './payeeUsage'

const transactions = [
  { payeeId: 1, date: '2026-01-05' },
  { payeeId: 1, date: '2026-03-10' },
  { payeeId: 2, date: '2026-02-01' },
  { payeeId: 3, date: '2026-04-01' },
  { payeeId: null, date: '2026-04-02' }
]
const payees = [
  { id: 2, name: 'Écureuil' },
  { id: 3, name: 'carrefour' },
  { id: 1, name: 'Boulangerie' },
  { id: 4, name: 'Zoo' }
]
const usage = usageByPayeeId(transactions)
const ids = (list) => list.map(p => p.id)

describe('usageByPayeeId', () => {
  it('compte les transactions et garde la date la plus récente, sans les transactions sans tiers', () => {
    expect(usage.get(1)).toEqual({ count: 2, lastDate: '2026-03-10' })
    expect(usage.get(2)).toEqual({ count: 1, lastDate: '2026-02-01' })
    expect(usage.has(null)).toBe(false)
  })
})

describe('sortPayees', () => {
  it('trie par nombre de transactions, du plus grand au plus petit puis l’inverse', () => {
    expect(ids(sortPayees(payees, { key: 'count', dir: 'desc' }, usage))).toEqual([1, 3, 2, 4])
    expect(ids(sortPayees(payees, { key: 'count', dir: 'asc' }, usage))).toEqual([4, 2, 3, 1])
  })

  it('trie par date de dernière transaction, la plus récente d’abord puis l’inverse', () => {
    expect(ids(sortPayees(payees, { key: 'lastDate', dir: 'desc' }, usage))).toEqual([3, 1, 2, 4])
    expect(ids(sortPayees(payees, { key: 'lastDate', dir: 'asc' }, usage))).toEqual([4, 2, 1, 3])
  })

  it('trie par nom à la française, sans tenir compte des accents ni de la casse', () => {
    expect(sortPayees(payees, { key: 'name', dir: 'asc' }, usage).map(p => p.name)).toEqual(['Boulangerie', 'carrefour', 'Écureuil', 'Zoo'])
    expect(sortPayees(payees, { key: 'name', dir: 'desc' }, usage).map(p => p.name)).toEqual(['Zoo', 'Écureuil', 'carrefour', 'Boulangerie'])
  })

  it('départage les égalités par le nom', () => {
    const tied = [{ id: 10, name: 'Zèbre' }, { id: 11, name: 'Abeille' }]
    expect(sortPayees(tied, { key: 'count', dir: 'desc' }, new Map()).map(p => p.name)).toEqual(['Abeille', 'Zèbre'])
  })

  it('conserve l’ordre d’origine sans tri, sans modifier la liste reçue', () => {
    expect(sortPayees(payees, null, usage)).toBe(payees)
    sortPayees(payees, { key: 'name', dir: 'asc' }, usage)
    expect(payees[0].id).toBe(2)
  })
})

describe('nextSort', () => {
  it('applique le sens par défaut de la colonne au premier clic', () => {
    expect(nextSort(null, 'count')).toEqual({ key: 'count', dir: 'desc' })
    expect(nextSort(null, 'lastDate')).toEqual({ key: 'lastDate', dir: 'desc' })
    expect(nextSort({ key: 'count', dir: 'desc' }, 'name')).toEqual({ key: 'name', dir: 'asc' })
  })

  it('inverse le sens en recliquant la même colonne, sans jamais annuler le tri', () => {
    expect(nextSort({ key: 'count', dir: 'desc' }, 'count')).toEqual({ key: 'count', dir: 'asc' })
    expect(nextSort({ key: 'count', dir: 'asc' }, 'count')).toEqual({ key: 'count', dir: 'desc' })
  })
})

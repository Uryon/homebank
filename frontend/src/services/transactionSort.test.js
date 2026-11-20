import { describe, expect, it } from 'vitest'
import { DEFAULT_TRANSACTION_SORT, isChronologicalSort, sortTransactions } from './transactionSort'

const list = [
  { id: 1, date: '2026-11-20', payee: 'Carrefour', amount: -50 },
  { id: 2, date: '2026-11-15', payee: '', amount: 2400 },
  { id: 3, date: '2026-11-10', payee: 'boulangerie', amount: -4.5 },
  { id: 4, date: '2026-10-02', payee: 'Bailleur', amount: -850 }
]
const ids = items => items.map(t => t.id)

describe('sortTransactions', () => {
  it('renvoie la liste telle quelle pour l\'ordre par défaut', () => {
    expect(sortTransactions(list, DEFAULT_TRANSACTION_SORT)).toBe(list)
    expect(sortTransactions(list, null)).toBe(list)
  })

  it('trie par date croissante', () => {
    expect(ids(sortTransactions(list, { key: 'date', dir: 'asc' }))).toEqual([4, 3, 2, 1])
  })

  it('trie par tiers sans tenir compte de la casse, les transactions sans tiers en dernier', () => {
    expect(ids(sortTransactions(list, { key: 'payee', dir: 'asc' }))).toEqual([4, 3, 1, 2])
    expect(ids(sortTransactions(list, { key: 'payee', dir: 'desc' }))).toEqual([1, 3, 4, 2])
  })

  it('met la plus grosse dépense ou le plus gros revenu en tête', () => {
    expect(ids(sortTransactions(list, { key: 'expense', dir: 'asc' }))).toEqual([4, 1, 3, 2])
    expect(ids(sortTransactions(list, { key: 'income', dir: 'desc' }))).toEqual([2, 3, 1, 4])
  })

  it('ne modifie pas la liste d\'origine', () => {
    const before = ids(list)
    sortTransactions(list, { key: 'payee', dir: 'asc' })
    expect(ids(list)).toEqual(before)
  })
})

describe('isChronologicalSort', () => {
  it('ne garde les intertitres de mois que pour le tri par date', () => {
    expect(isChronologicalSort({ key: 'date', dir: 'asc' })).toBe(true)
    expect(isChronologicalSort({ key: 'payee', dir: 'asc' })).toBe(false)
  })
})

import { describe, expect, it } from 'vitest'
import { cleanupOutcome, selectedTransactionCount } from './payeeCleanup'

describe('cleanupOutcome', () => {
  it('annonce la suppression du tiers devenu inutile', () => {
    expect(cleanupOutcome({ willBeDeleted: true, otherTransactions: 0 })).toBe('Le tiers sera supprimé.')
  })

  it('explique pourquoi un tiers est conservé', () => {
    expect(cleanupOutcome({ willBeDeleted: false, otherTransactions: 2 })).toContain('2 autres transactions')
    expect(cleanupOutcome({ willBeDeleted: false, otherTransactions: 0 })).toContain('personnalisé')
  })
})

describe('selectedTransactionCount', () => {
  it('ne compte que les tiers cochés', () => {
    const items = [{ payeeId: 1, transactionsToDetach: 3 }, { payeeId: 2, transactionsToDetach: 5 }]
    expect(selectedTransactionCount(items, [2])).toBe(5)
    expect(selectedTransactionCount(items, [])).toBe(0)
  })
})

import { describe, expect, it } from 'vitest'
import { findIdenticalTransaction } from './duplicateEntry'

const existante = { id: 7, accountId: 1, date: '2026-11-17', amount: -9.5, payee: 'Station Essence' }

describe('findIdenticalTransaction', () => {
  it('retrouve une transaction de même compte, date, montant et tiers (casse ignorée)', () => {
    expect(findIdenticalTransaction({ accountId: 1, date: '2026-11-17', amount: -9.50, payee: 'station essence ' }, [existante])).toBe(existante)
  })

  it('ignore une transaction qui diffère par le compte, la date, le montant ou le tiers', () => {
    const base = { accountId: 1, date: '2026-11-17', amount: -9.5, payee: 'Station Essence' }
    for (const change of [{ accountId: 2 }, { date: '2026-11-18' }, { amount: -9.51 }, { payee: 'Boulangerie' }]) {
      expect(findIdenticalTransaction({ ...base, ...change }, [existante])).toBeNull()
    }
  })

  it('compare aussi deux transactions sans tiers', () => {
    const sansTiers = { ...existante, payee: null }
    expect(findIdenticalTransaction({ accountId: 1, date: '2026-11-17', amount: -9.5, payee: '' }, [sansTiers])).toBe(sansTiers)
  })
})

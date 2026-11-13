import { describe, expect, it } from 'vitest'
import { formatMonthHeading, monthKey, startsNewMonth, summarizeByMonth } from './monthGroups'

const list = [
  { date: '2026-11-06', amount: -12.4 },
  { date: '2026-11-02', amount: 99.09 },
  { date: '2026-10-30', amount: -0.1 },
  { date: '2026-10-28', amount: -0.2 }
]

describe('monthGroups', () => {
  it('extrait la clé de mois', () => {
    expect(monthKey('2026-11-06')).toBe('2026-11')
    expect(monthKey(null)).toBe('')
  })

  it('compte et additionne par mois, arrondi au centime', () => {
    const summary = summarizeByMonth(list)
    expect(summary.get('2026-11')).toEqual({ count: 2, total: 86.69 })
    expect(summary.get('2026-10')).toEqual({ count: 2, total: -0.3 })
  })

  it('repère le début de chaque mois dans la liste', () => {
    expect(list.map((_, i) => startsNewMonth(list, i))).toEqual([true, false, true, false])
  })

  it('formate l’intertitre avec une majuscule', () => {
    expect(formatMonthHeading('2026-11-06')).toBe('Novembre 2026')
    expect(formatMonthHeading('2026-08-01')).toBe('Août 2026')
    expect(formatMonthHeading('')).toBe('')
  })
})

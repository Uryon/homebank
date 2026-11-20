import { describe, it, expect } from 'vitest'
import { popoverContent, sortRowsByMagnitude } from './cellDetail'

describe('sortRowsByMagnitude', () => {
  it('classe par montant décroissant en valeur absolue, sans modifier la liste reçue', () => {
    const rows = [{ amount: -1.15 }, { amount: -1.25 }, { amount: 30 }]
    expect(sortRowsByMagnitude(rows).map(r => r.amount)).toEqual([30, -1.25, -1.15])
    expect(rows[0].amount).toBe(-1.15)
  })
})

describe('popoverContent', () => {
  it('totalise toutes les transactions de la case', () => {
    const { total, rows } = popoverContent([
      { id: 1, date: '2026-01-02', payee: 'Carrefour', amount: '-10.00' },
      { id: 2, date: '2026-01-09', payee: 'EDF', amount: '-60.00' }
    ])
    expect(total).toBe(-70)
    expect(rows[0].payee).toBe('EDF')
  })
})

import { describe, expect, it } from 'vitest'
import { splitFirstAmountError } from './split'

describe('splitFirstAmountError', () => {
  it('accepte une 1ère partie de même signe et plus petite que le total', () => {
    expect(splitFirstAmountError(-100, -40)).toBe('')
    expect(splitFirstAmountError(250, 0.01)).toBe('')
  })

  it('refuse un montant vide ou nul', () => {
    for (const first of [null, undefined, '', 0, Number.NaN]) {
      expect(splitFirstAmountError(-100, first)).toMatch(/non nul/)
    }
  })

  it('refuse un signe opposé au total, en indiquant le signe attendu', () => {
    expect(splitFirstAmountError(-100, 40)).toMatch(/négative/)
    expect(splitFirstAmountError(100, -40)).toMatch(/positive/)
  })

  it('refuse une 1ère partie égale ou supérieure au total, montant total mis en forme', () => {
    const format = v => `${v} €`
    expect(splitFirstAmountError(-100, -100, format)).toBe(
      'La 1ère partie doit être inférieure au montant total (-100 €) pour laisser une 2e partie.'
    )
    expect(splitFirstAmountError(100, 150)).toMatch(/inférieure au montant total \(100\)/)
  })
})

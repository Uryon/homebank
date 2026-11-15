import { describe, expect, it } from 'vitest'
import { monthlyPaymentFor } from './loanMath'

describe('monthlyPaymentFor', () => {
  it('calcule la mensualité d\'un prêt à taux fixe', () => {
    // 200 000 € sur 20 ans à 3 % : 1 109,20 € (valeur de référence des simulateurs)
    expect(monthlyPaymentFor(200000, 3, 240)).toBe(1109.2)
  })

  it('divise simplement le capital à taux nul', () => {
    expect(monthlyPaymentFor(12000, 0, 24)).toBe(500)
    expect(monthlyPaymentFor(12000, null, 24)).toBe(500)
  })

  it('renvoie null tant que capital ou durée manquent', () => {
    expect(monthlyPaymentFor(0, 3, 240)).toBeNull()
    expect(monthlyPaymentFor(200000, 3, null)).toBeNull()
  })
})

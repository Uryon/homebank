import { describe, expect, it } from 'vitest'
import { parseAmountExpression, parseAmountText } from './amountText'

describe('parseAmountExpression', () => {
  it.each([
    ['-12,50', -12.5],
    ['12.5', 12.5],
    ['1 234,56', 1234.56],
    ['-1 234,56 €', -1234.56],
    ['1.234,56', 1234.56],
    ['1,234.56', 1234.56],
    ['1,234', 1234],
    ['1.234.567', 1234567],
    ['−45,00 EUR', -45],
    ['0,125', 0.13],
    ['.5', 0.5],
    ['21000-500+300', 20800],
    ['-500+300', -200],
    ['1 000,50 - 0,50', 1000]
  ])('lit « %s »', (text, value) => {
    expect(parseAmountExpression(text)).toEqual({ value, valid: true })
  })

  it('un champ vide ne contient aucun montant', () => {
    expect(parseAmountExpression('   ')).toEqual({ value: null, valid: true })
  })

  it.each(['abc', '€', '1.2.3', '12,5,3', '5*2', '1e5', '0x10', '--5'])('refuse « %s »', (text) => {
    expect(parseAmountExpression(text).valid).toBe(false)
  })
})

describe('parseAmountText', () => {
  it('rend un nombre en texte', () => {
    expect(parseAmountText('-1 000,50')).toBe('-1000.50')
    expect(parseAmountText('abc')).toBeNull()
  })
})

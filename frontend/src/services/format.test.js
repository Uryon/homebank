import { describe, it, expect } from 'vitest'
import { formatAmount, formatDate, displayMemo } from './format'

// Intl.NumberFormat separe le montant du symbole par une espace insecable
// (U+00A0), pas une espace normale : on l'ignore pour ne pas dependre d'un
// caractere exact qui varie selon la version d'ICU.
function withoutWhitespace(str) {
  return str.replace(/\s/g, '')
}

describe('formatAmount', () => {
  it('formate un montant positif en euros au format francais', () => {
    expect(withoutWhitespace(formatAmount(45.5))).toBe('45,50€')
  })

  it('formate un montant negatif avec le signe', () => {
    expect(withoutWhitespace(formatAmount(-12.9))).toBe('-12,90€')
  })

  it('traite null/undefined comme zero, plutot que d\'afficher "NaN"', () => {
    expect(withoutWhitespace(formatAmount(null))).toBe('0,00€')
    expect(withoutWhitespace(formatAmount(undefined))).toBe('0,00€')
  })
})

describe('formatDate', () => {
  it('convertit une date ISO en format jour/mois/annee', () => {
    expect(formatDate('2026-03-05')).toBe('05/03/2026')
  })

  it('renvoie une chaine vide pour une date absente', () => {
    expect(formatDate(null)).toBe('')
    expect(formatDate('')).toBe('')
  })
})

describe('displayMemo', () => {
  it('renvoie le memo tel quel quand il est renseigne', () => {
    expect(displayMemo('Courses de la semaine')).toBe('Courses de la semaine')
  })

  it('n\'affiche rien pour un memo vide, null ou "(NULL)" (valeur litterale de certains exports CSV/QIF)', () => {
    expect(displayMemo(null)).toBe('')
    expect(displayMemo('')).toBe('')
    expect(displayMemo('(NULL)')).toBe('')
    expect(displayMemo('(null)')).toBe('')
    expect(displayMemo('  (NULL)  ')).toBe('')
  })
})

import { describe, it, expect } from 'vitest'
import { formatAmount, formatDate, displayMemo, formatRate, toLocalIsoDate } from './format'

describe('toLocalIsoDate', () => {
  it('formate une date locale en AAAA-MM-JJ avec zeros de tete', () => {
    expect(toLocalIsoDate(new Date(2026, 0, 5))).toBe('2026-01-05')
  })

  it('garde le jour local a minuit, sans conversion UTC', () => {
    // Minuit local du 1er : toISOString() donnerait la veille en UTC+1/+2.
    expect(toLocalIsoDate(new Date(2026, 2, 1, 0, 0, 0))).toBe('2026-03-01')
    expect(toLocalIsoDate(new Date(2026, 2, 31, 23, 59, 59))).toBe('2026-03-31')
  })

  it('utilise la date du jour par defaut', () => {
    expect(toLocalIsoDate()).toBe(toLocalIsoDate(new Date()))
  })
})

describe('formatRate', () => {
  it('affiche un taux à la française avec au moins deux décimales', () => {
    expect(formatRate(1.52).replace(/\s/g, ' ')).toBe('1,52 %')
    expect(formatRate(3).replace(/\s/g, ' ')).toBe('3,00 %')
    expect(formatRate(2.125).replace(/\s/g, ' ')).toBe('2,125 %')
  })

  it('affiche 0 pour un taux absent', () => {
    expect(formatRate(null).replace(/\s/g, ' ')).toBe('0,00 %')
  })
})

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

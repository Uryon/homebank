import { describe, it, expect } from 'vitest'
import { formatAmount, formatDate, displayMemo, formatRate, toLocalIsoDate, formatFrequency, formatAmountForInput, pluralize } from './format'

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

  it('affiche la devise réelle du compte quand elle est fournie', () => {
    expect(withoutWhitespace(formatAmount(45.5, 'USD'))).toBe('45,50$US')
    expect(withoutWhitespace(formatAmount(45.5, 'chf'))).toBe('45,50CHF')
  })

  it("retombe sur l'euro si la devise est absente ou invalide", () => {
    expect(withoutWhitespace(formatAmount(1, null))).toBe('1,00€')
    expect(withoutWhitespace(formatAmount(1, 'E1'))).toBe('1,00€')
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

describe('formatFrequency', () => {
  it('reconnaît les rythmes usuels malgré de petits écarts', () => {
    expect(formatFrequency(31)).toBe('mensuel')
    expect(formatFrequency(29)).toBe('mensuel')
    expect(formatFrequency(7)).toBe('hebdomadaire')
    expect(formatFrequency(92)).toBe('trimestriel')
    expect(formatFrequency(366)).toBe('annuel')
  })

  it('garde la durée brute pour un rythme inhabituel', () => {
    expect(formatFrequency(45)).toBe('tous les ~45 jours')
    expect(formatFrequency(10.4)).toBe('tous les ~10 jours')
  })

  it('ne renvoie rien pour une valeur absente ou invalide', () => {
    expect(formatFrequency(null)).toBe('')
    expect(formatFrequency(0)).toBe('')
    expect(formatFrequency('abc')).toBe('')
  })
})

describe('formatAmountForInput', () => {
  it('affiche deux décimales avec une virgule', () => {
    expect(formatAmountForInput(-48.9)).toBe('-48,90')
    expect(formatAmountForInput(0.5)).toBe('0,50')
    expect(formatAmountForInput(1234.567)).toBe('1234,57')
  })

  it('garde un entier sans décimales', () => {
    expect(formatAmountForInput(2400)).toBe('2400')
    expect(formatAmountForInput(0)).toBe('0')
  })

  it('renvoie une chaîne vide sans montant', () => {
    expect(formatAmountForInput(null)).toBe('')
    expect(formatAmountForInput(undefined)).toBe('')
    expect(formatAmountForInput(Number.NaN)).toBe('')
  })
})

describe('pluralize', () => {
  it('garde le singulier pour 0 et 1, comme en français', () => {
    expect(pluralize(0, 'transaction validée', 'transactions validées')).toBe('0 transaction validée')
    expect(pluralize(1, 'transaction validée', 'transactions validées')).toBe('1 transaction validée')
  })

  it('accorde au pluriel à partir de 2', () => {
    expect(pluralize(2, 'doublon supprimé', 'doublons supprimés')).toBe('2 doublons supprimés')
  })
})

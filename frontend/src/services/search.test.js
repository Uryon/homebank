import { describe, it, expect } from 'vitest'
import { normalizeSearch, parseAmountQuery } from './search'

describe('normalizeSearch', () => {
  it('met en minuscules et retire les accents pour que les deux graphies se retrouvent', () => {
    expect(normalizeSearch('Électricité')).toBe('electricite')
    expect(normalizeSearch('electricite')).toBe('electricite')
  })

  it('trim les espaces autour', () => {
    expect(normalizeSearch('  Carrefour  ')).toBe('carrefour')
  })

  it('renvoie une chaine vide pour une entree vide ou absente', () => {
    expect(normalizeSearch('')).toBe('')
    expect(normalizeSearch(null)).toBe('')
    expect(normalizeSearch(undefined)).toBe('')
  })
})

describe('parseAmountQuery', () => {
  it('reconnait un montant avec operateur ">"', () => {
    expect(parseAmountQuery('> 100')).toEqual({ operator: '>', value: '100' })
  })

  it('reconnait un montant avec operateur "<" sans espace', () => {
    expect(parseAmountQuery('<50')).toEqual({ operator: '<', value: '50' })
  })

  it('accepte la virgule et le point comme separateur decimal', () => {
    expect(parseAmountQuery('= 42,50')).toEqual({ operator: '=', value: '42.50' })
    expect(parseAmountQuery('= 42.50')).toEqual({ operator: '=', value: '42.50' })
  })

  it('accepte un montant negatif', () => {
    expect(parseAmountQuery('< -20')).toEqual({ operator: '<', value: '-20' })
  })

  it('renvoie null pour un texte qui ne correspond pas au format', () => {
    expect(parseAmountQuery('Carrefour')).toBeNull()
    expect(parseAmountQuery('100')).toBeNull()
    expect(parseAmountQuery('> abc')).toBeNull()
    expect(parseAmountQuery('')).toBeNull()
    expect(parseAmountQuery(null)).toBeNull()
  })
})

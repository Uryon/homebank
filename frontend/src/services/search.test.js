import { describe, it, expect } from 'vitest'
import { normalizeSearch, parseAmountQuery, parseSearchTerms, matchesSearchTerm } from './search'

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

describe('parseSearchTerms', () => {
  it('normalise un critère simple en mode "contient" (non exact)', () => {
    expect(parseSearchTerms('CAF')).toEqual([{ term: 'caf', exact: false }])
  })

  it('détecte un critère entre guillemets comme "exact"', () => {
    expect(parseSearchTerms('"CAF"')).toEqual([{ term: 'caf', exact: true }])
  })

  it('découpe plusieurs critères séparés par ";"', () => {
    expect(parseSearchTerms('Bouygues;29,99')).toEqual([
      { term: 'bouygues', exact: false },
      { term: '29,99', exact: false }
    ])
  })

  it('mélange critères exacts et non exacts', () => {
    expect(parseSearchTerms('"CAF";29,99')).toEqual([
      { term: 'caf', exact: true },
      { term: '29,99', exact: false }
    ])
  })

  it('ignore les critères vides et renvoie un tableau vide pour une entrée vide', () => {
    expect(parseSearchTerms('')).toEqual([])
    expect(parseSearchTerms(';;')).toEqual([])
    expect(parseSearchTerms(null)).toEqual([])
  })
})

describe('matchesSearchTerm', () => {
  const idx = {
    payeeNormalized: 'caf',
    categoryNormalized: 'allocations familiales',
    categoryNameNormalized: 'allocations familiales',
    rawAmount: '99.09',
    absAmount: '99.09',
    amountFormattedNormalized: '99,09'
  }

  it('mode "contient" : matche aussi le memo', () => {
    expect(matchesSearchTerm({ ...idx, memoNormalized: 'cadeau elodie' }, { term: 'cadeau', exact: false })).toBe(true)
    expect(matchesSearchTerm(idx, { term: 'cadeau', exact: false })).toBe(false)
  })

  it('mode "exact" : ignore le memo (texte libre)', () => {
    expect(matchesSearchTerm({ ...idx, memoNormalized: 'cadeau' }, { term: 'cadeau', exact: true })).toBe(false)
  })

  it('mode "contient" : matche un tiers/categorie qui contient seulement le terme', () => {
    expect(matchesSearchTerm({ ...idx, payeeNormalized: 'decafeine' }, { term: 'caf', exact: false })).toBe(true)
  })

  it('mode "exact" : ne matche pas un tiers/categorie qui contient seulement le terme', () => {
    expect(matchesSearchTerm({ ...idx, payeeNormalized: 'decafeine' }, { term: 'caf', exact: true })).toBe(false)
  })

  it('mode "exact" : matche un tiers strictement egal au terme', () => {
    expect(matchesSearchTerm(idx, { term: 'caf', exact: true })).toBe(true)
  })

  it('mode "exact" : matche aussi un montant strictement egal (brut ou formate)', () => {
    expect(matchesSearchTerm(idx, { term: '99.09', exact: true })).toBe(true)
    expect(matchesSearchTerm(idx, { term: '99,09', exact: true })).toBe(true)
    expect(matchesSearchTerm(idx, { term: '99', exact: true })).toBe(false)
  })

  it('mode "exact" : matche le nom de categorie seul meme si le libelle complet inclut le parent', () => {
    const withParentLabel = {
      ...idx,
      categoryNormalized: 'traitements et salaires : salaire',
      categoryNameNormalized: 'salaire'
    }
    expect(matchesSearchTerm(withParentLabel, { term: 'salaire', exact: true })).toBe(true)
  })

  it('mode "exact" : matche aussi le libelle complet tape en entier', () => {
    const withParentLabel = {
      ...idx,
      categoryNormalized: 'traitements et salaires : salaire',
      categoryNameNormalized: 'salaire'
    }
    expect(matchesSearchTerm(withParentLabel, { term: 'traitements et salaires : salaire', exact: true })).toBe(true)
  })
})

import { describe, it, expect } from 'vitest'
import { normalizeSearch, parseAmountQuery, matchesAmountQuery, parseSearchTerms, matchesSearchTerm } from './search'

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

  it('accepte les milliers et les comparaisons larges', () => {
    expect(parseAmountQuery('= 1 042,50')).toEqual({ operator: '=', value: '1042.50' })
    expect(parseAmountQuery('>= 100')).toEqual({ operator: '>=', value: '100' })
    expect(parseAmountQuery('<=-20')).toEqual({ operator: '<=', value: '-20' })
    expect(matchesAmountQuery({ amount: 100 }, { operator: '>=', value: '100' })).toBe(true)
    expect(matchesAmountQuery({ amount: -21 }, { operator: '<=', value: '-20' })).toBe(true)
    expect(matchesAmountQuery({ amount: -19 }, { operator: '<=', value: '-20' })).toBe(false)
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

  it('mode "contient" : matche le libellé du relevé d’une transaction importée', () => {
    expect(matchesSearchTerm({ ...idx, bankLabelNormalized: 'prlv sepa caf ref 123' }, { term: 'prlv', exact: false })).toBe(true)
    expect(matchesSearchTerm({ ...idx, bankLabelNormalized: 'prlv' }, { term: 'prlv', exact: true })).toBe(false)
  })

  it('mode "exact" : une racine retrouve aussi ses sous-catégories, pas une catégorie au nom plus long', () => {
    const racine = { ...idx, categoryNormalized: 'alimentation', categoryNameNormalized: 'alimentation' }
    const enfant = { ...idx, categoryNormalized: 'alimentation : bio', categoryNameNormalized: 'bio' }
    const autre = { ...idx, categoryNormalized: 'alimentation animale', categoryNameNormalized: 'alimentation animale' }
    expect(matchesSearchTerm(racine, { term: 'alimentation', exact: true })).toBe(true)
    expect(matchesSearchTerm(enfant, { term: 'alimentation', exact: true })).toBe(true)
    expect(matchesSearchTerm(autre, { term: 'alimentation', exact: true })).toBe(false)
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

describe('matchesAmountQuery', () => {
  const tx = amount => ({ amount })

  it('compare le montant signé avec > et <', () => {
    expect(matchesAmountQuery(tx(2400), { operator: '>', value: '100' })).toBe(true)
    expect(matchesAmountQuery(tx(-850), { operator: '>', value: '100' })).toBe(false)
    expect(matchesAmountQuery(tx(-850), { operator: '<', value: '-20' })).toBe(true)
    expect(matchesAmountQuery(tx(-19.9), { operator: '<', value: '-20' })).toBe(false)
  })

  it('exclut la borne elle-même pour > et <', () => {
    expect(matchesAmountQuery(tx(100), { operator: '>', value: '100' })).toBe(false)
    expect(matchesAmountQuery(tx(100), { operator: '<', value: '100' })).toBe(false)
  })

  it('compare = au centime près, sans erreur d’arrondi flottant', () => {
    expect(matchesAmountQuery(tx(0.1 + 0.2), { operator: '=', value: '0.30' })).toBe(true)
    expect(matchesAmountQuery(tx('-42.50'), { operator: '=', value: '-42.5' })).toBe(true)
    expect(matchesAmountQuery(tx(42.5), { operator: '=', value: '-42.5' })).toBe(false)
  })

  it('ignore une transaction sans montant lisible', () => {
    expect(matchesAmountQuery(tx(undefined), { operator: '=', value: '0' })).toBe(false)
  })
})

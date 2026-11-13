import { describe, it, expect } from 'vitest'
import { findPayeeByName, matchesPatternField, splitPatternSegments } from './payee'

describe('splitPatternSegments', () => {
  it('découpe un motif multiple en segments', () => {
    expect(splitPatternSegments('MCX DISTRI;Carrefour')).toEqual(['MCX DISTRI', 'Carrefour'])
  })

  it('réduit les espaces multiples et ignore les segments vides', () => {
    expect(splitPatternSegments('  SumUp   Clavet ; ;KFC ')).toEqual(['SumUp Clavet', 'KFC'])
  })

  it('renvoie une liste vide pour un motif absent', () => {
    expect(splitPatternSegments(null)).toEqual([])
    expect(splitPatternSegments('')).toEqual([])
  })
})

/** Construit une Map nom-normalise -> tableau de tiers, comme payeeByNormalizedName/payeeIndex. */
function payeeIndex(payees) {
  const map = new Map()
  for (const p of payees) {
    const key = p.name.trim().toLowerCase()
    const list = map.get(key)
    if (list) list.push(p)
    else map.set(key, [p])
  }
  return map
}

describe('findPayeeByName', () => {
  it('renvoie null pour un nom vide, blanc ou absent', () => {
    const index = payeeIndex([{ id: 1, name: 'Carrefour', amount: null }])
    expect(findPayeeByName(index, '', 10)).toBeNull()
    expect(findPayeeByName(index, '   ', 10)).toBeNull()
    expect(findPayeeByName(index, null, 10)).toBeNull()
  })

  it('renvoie null si aucun tiers ne porte ce nom', () => {
    const index = payeeIndex([{ id: 1, name: 'Carrefour', amount: null }])
    expect(findPayeeByName(index, 'Monoprix', 10)).toBeNull()
  })

  it('renvoie directement le seul tiers portant ce nom, quel que soit le montant', () => {
    const carrefour = { id: 1, name: 'Carrefour', amount: null }
    const index = payeeIndex([carrefour])
    expect(findPayeeByName(index, 'Carrefour', 42)).toBe(carrefour)
  })

  it('recherche insensible a la casse et aux espaces autour du nom', () => {
    const carrefour = { id: 1, name: 'Carrefour', amount: null }
    const index = payeeIndex([carrefour])
    expect(findPayeeByName(index, '  CARREFOUR  ', null)).toBe(carrefour)
  })

  it('parmi plusieurs homonymes, prefere celui dont le montant de reference correspond exactement', () => {
    const bouyguesGenerique = { id: 1, name: 'Bouygues', amount: null }
    const bouyguesMel = { id: 2, name: 'Bouygues', amount: 10 }
    const index = payeeIndex([bouyguesGenerique, bouyguesMel])

    expect(findPayeeByName(index, 'Bouygues', 10)).toBe(bouyguesMel)
  })

  it('compare le montant en valeur absolue (signe depense/revenu indifferent)', () => {
    const bouyguesMel = { id: 2, name: 'Bouygues', amount: 10 }
    const index = payeeIndex([bouyguesMel])

    expect(findPayeeByName(index, 'Bouygues', -10)).toBe(bouyguesMel)
  })

  it('se replie sur le tiers generique (sans montant) si aucun montant exact ne correspond', () => {
    const bouyguesGenerique = { id: 1, name: 'Bouygues', amount: null }
    const bouyguesMel = { id: 2, name: 'Bouygues', amount: 10 }
    const index = payeeIndex([bouyguesGenerique, bouyguesMel])

    expect(findPayeeByName(index, 'Bouygues', 25)).toBe(bouyguesGenerique)
  })

  it('se replie sur le premier candidat si aucun generique ni montant exact ne correspond', () => {
    const bouyguesMel = { id: 2, name: 'Bouygues', amount: 10 }
    const bouyguesVal = { id: 3, name: 'Bouygues', amount: 20 }
    const index = payeeIndex([bouyguesMel, bouyguesVal])

    expect(findPayeeByName(index, 'Bouygues', 99)).toBe(bouyguesMel)
  })

  it('sans montant fourni, se replie sur le generique meme si des montants precis existent', () => {
    const generique = { id: 1, name: 'Bouygues', amount: null }
    const precis = { id: 2, name: 'Bouygues', amount: 10 }
    const index = payeeIndex([precis, generique])

    expect(findPayeeByName(index, 'Bouygues', null)).toBe(generique)
    expect(findPayeeByName(index, 'Bouygues', '')).toBe(generique)
  })
})

describe('matchesPatternField', () => {
  it('reconnaît un segment n’importe où dans le libellé, sans tenir compte de la casse', () => {
    expect(matchesPatternField('SOCARES', 'Prlv socares 10.06.2026')).toBe(true)
  })

  it('accepte plusieurs segments séparés par « ; » (OU)', () => {
    expect(matchesPatternField('FREE MOBILE;FREE TELECOM', 'PRLV FREE TELECOM 42')).toBe(true)
    expect(matchesPatternField('FREE MOBILE;FREE TELECOM', 'ORANGE')).toBe(false)
  })

  it('ignore les espaces multiples, dans le motif comme dans le libellé', () => {
    expect(matchesPatternField('SGC  TOURCOING', 'CB SGC   TOURCOING 12/03')).toBe(true)
  })

  it('traite l’étoile comme un joker, et seulement elle', () => {
    expect(matchesPatternField('Carrefour*Paris', 'CARREFOUR MARKET PARIS 15')).toBe(true)
    expect(matchesPatternField('Carrefour*Paris', 'CARREFOUR LILLE')).toBe(false)
    // Le point est un caractère ordinaire, pas « n’importe quel caractère ».
    expect(matchesPatternField('A.B', 'AXB')).toBe(false)
    expect(matchesPatternField('A.B (X)', 'paiement A.B (X)')).toBe(true)
  })

  it('ne reconnaît rien avec un motif ou un libellé vide', () => {
    expect(matchesPatternField('', 'Carrefour')).toBe(false)
    expect(matchesPatternField(' ; ', 'Carrefour')).toBe(false)
    expect(matchesPatternField(null, 'Carrefour')).toBe(false)
    expect(matchesPatternField('Carrefour', '  ')).toBe(false)
  })
})

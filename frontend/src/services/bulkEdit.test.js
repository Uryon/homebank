import { describe, it, expect } from 'vitest'
import { bulkTouchedFields, bulkUpdatePayload, countTouchedFields, emptyBulkForm } from './bulkEdit'

describe('bulkTouchedFields', () => {
  it('ne marque aucun champ sur un formulaire vierge', () => {
    const touched = bulkTouchedFields(emptyBulkForm())
    expect(countTouchedFields(touched)).toBe(0)
  })

  it('distingue une categorie videe (touchee) d une categorie non touchee', () => {
    const form = { ...emptyBulkForm(), categoryTouched: true, categoryId: null, validated: false }
    expect(bulkTouchedFields(form)).toMatchObject({ categoryId: true, payee: false, validated: true })
    expect(countTouchedFields(bulkTouchedFields(form))).toBe(2)
  })
})

describe('bulkUpdatePayload', () => {
  it('n envoie que les champs touches, avec leur drapeau Provided', () => {
    const form = { ...emptyBulkForm(), payeeTouched: true, payee: 'EDF', exceptional: true }
    expect(bulkUpdatePayload([1, 2], bulkTouchedFields(form), form)).toEqual({
      ids: [1, 2],
      payeeProvided: true,
      payee: 'EDF',
      exceptionalProvided: true,
      exceptional: true
    })
  })

  it('transmet une valeur null explicite (retirer la categorie)', () => {
    const touched = { categoryId: true }
    expect(bulkUpdatePayload([3], touched, { categoryId: null })).toEqual({
      ids: [3],
      categoryIdProvided: true,
      categoryId: null
    })
  })

  it('reutilise les memes champs pour restaurer l etat precedent', () => {
    const touched = { paymentMode: true, validated: true }
    const before = { paymentMode: 'CARD', validated: false, payee: 'ignore' }
    expect(bulkUpdatePayload([7], touched, before)).toEqual({
      ids: [7],
      paymentModeProvided: true,
      paymentMode: 'CARD',
      validatedProvided: true,
      validated: false
    })
  })
})

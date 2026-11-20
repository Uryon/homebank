import { describe, expect, it } from 'vitest'
import { idsBetween } from './selection'

const items = [{ id: 10 }, { id: 20 }, { id: 30 }, { id: 40 }]

describe('idsBetween', () => {
  it('donne les lignes entre les deux bornes comprises, dans les deux sens', () => {
    expect(idsBetween(items, 20, 40)).toEqual([20, 30, 40])
    expect(idsBetween(items, 40, 20)).toEqual([20, 30, 40])
  })

  it('donne la ligne seule quand les deux bornes sont identiques', () => {
    expect(idsBetween(items, 30, 30)).toEqual([30])
  })

  it('donne null si une borne n\'est pas affichée', () => {
    expect(idsBetween(items, 99, 20)).toBeNull()
    expect(idsBetween(items, 20, null)).toBeNull()
  })
})

import { describe, expect, it } from 'vitest'
import { nextGridPosition } from './gridNav'

// 3 lignes de 14 cellules (12 mois + moyenne + total), dont une plus courte.
const rowLengths = [14, 14, 3]

describe('nextGridPosition', () => {
  it('se déplace horizontalement sans sortir de la ligne', () => {
    expect(nextGridPosition('ArrowRight', { row: 0, col: 5 }, rowLengths)).toEqual({ row: 0, col: 6 })
    expect(nextGridPosition('ArrowRight', { row: 0, col: 13 }, rowLengths)).toEqual({ row: 0, col: 13 })
    expect(nextGridPosition('ArrowLeft', { row: 0, col: 0 }, rowLengths)).toEqual({ row: 0, col: 0 })
  })

  it('se déplace verticalement en restant dans le tableau', () => {
    expect(nextGridPosition('ArrowDown', { row: 0, col: 4 }, rowLengths)).toEqual({ row: 1, col: 4 })
    expect(nextGridPosition('ArrowUp', { row: 0, col: 4 }, rowLengths)).toEqual({ row: 0, col: 4 })
    expect(nextGridPosition('ArrowDown', { row: 2, col: 1 }, rowLengths)).toEqual({ row: 2, col: 1 })
  })

  it('ramène la colonne dans une ligne plus courte', () => {
    expect(nextGridPosition('ArrowDown', { row: 1, col: 10 }, rowLengths)).toEqual({ row: 2, col: 2 })
  })

  it('Début / Fin vont aux extrémités de la ligne', () => {
    expect(nextGridPosition('Home', { row: 1, col: 7 }, rowLengths)).toEqual({ row: 1, col: 0 })
    expect(nextGridPosition('End', { row: 1, col: 7 }, rowLengths)).toEqual({ row: 1, col: 13 })
  })

  it('ignore les autres touches', () => {
    expect(nextGridPosition('a', { row: 0, col: 0 }, rowLengths)).toBeNull()
  })
})

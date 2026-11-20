import { describe, expect, it } from 'vitest'
import { nextRowIndex } from './rowNav'

describe('nextRowIndex', () => {
  it('descend et monte d\'une ligne, sans dépasser les bords', () => {
    expect(nextRowIndex('ArrowDown', 0, 3)).toBe(1)
    expect(nextRowIndex('ArrowDown', 2, 3)).toBe(2)
    expect(nextRowIndex('ArrowUp', 2, 3)).toBe(1)
    expect(nextRowIndex('ArrowUp', 0, 3)).toBe(0)
  })

  it('Début et Fin vont à la première et à la dernière ligne', () => {
    expect(nextRowIndex('Home', 2, 5)).toBe(0)
    expect(nextRowIndex('End', 0, 5)).toBe(4)
  })

  it('ignore les autres touches et les listes vides', () => {
    expect(nextRowIndex('ArrowLeft', 1, 3)).toBeNull()
    expect(nextRowIndex('Enter', 1, 3)).toBeNull()
    expect(nextRowIndex('ArrowDown', 0, 0)).toBeNull()
  })
})

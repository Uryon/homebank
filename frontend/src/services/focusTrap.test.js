import { describe, expect, it } from 'vitest'
import { wrappedFocusIndex } from './focusTrap'

describe('wrappedFocusIndex', () => {
  it('reboucle du dernier au premier élément avec Tab', () => {
    expect(wrappedFocusIndex(4, 3, false)).toBe(0)
  })

  it('reboucle du premier au dernier élément avec Maj+Tab', () => {
    expect(wrappedFocusIndex(4, 0, true)).toBe(3)
  })

  it('laisse faire le navigateur au milieu de la liste', () => {
    expect(wrappedFocusIndex(4, 1, false)).toBe(-1)
    expect(wrappedFocusIndex(4, 2, true)).toBe(-1)
  })

  it('ramène dans la fenêtre un focus parti ailleurs', () => {
    expect(wrappedFocusIndex(4, -1, false)).toBe(0)
    expect(wrappedFocusIndex(4, -1, true)).toBe(3)
  })

  it('ne fait rien sans élément focusable', () => {
    expect(wrappedFocusIndex(0, -1, false)).toBe(-1)
  })
})

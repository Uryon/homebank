import { afterEach, describe, expect, it, vi } from 'vitest'
import { getFocusableElements, trapTabKey, wrappedFocusIndex } from './focusTrap'

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

/** Faux élément focusable : visible ou non (getClientRects vide si masqué), focus() mémorisé. */
function fakeFocusable(name, { visible = true } = {}) {
  const el = { name, getClientRects: () => (visible ? [{}] : []), focus: () => { globalThis.document.activeElement = el } }
  return el
}
const fakeContainer = (elements) => ({ querySelectorAll: () => elements })
const tabEvent = (shiftKey = false, key = 'Tab') => ({ key, shiftKey, preventDefault: vi.fn() })

describe('getFocusableElements', () => {
  it("ignore les éléments masqués (aucune boîte à l'écran)", () => {
    const visible = fakeFocusable('nom')
    const hidden = fakeFocusable('masqué', { visible: false })
    expect(getFocusableElements(fakeContainer([visible, hidden]))).toEqual([visible])
  })
})

describe('trapTabKey', () => {
  const first = fakeFocusable('premier')
  const middle = fakeFocusable('milieu')
  const last = fakeFocusable('dernier')
  const container = fakeContainer([first, middle, last])

  afterEach(() => { delete globalThis.document })

  it('ramène Tab du dernier élément au premier', () => {
    globalThis.document = { activeElement: last }
    const event = tabEvent()
    trapTabKey(event, container)
    expect(event.preventDefault).toHaveBeenCalled()
    expect(globalThis.document.activeElement).toBe(first)
  })

  it('ramène Maj+Tab du premier élément au dernier', () => {
    globalThis.document = { activeElement: first }
    trapTabKey(tabEvent(true), container)
    expect(globalThis.document.activeElement).toBe(last)
  })

  it('laisse le navigateur avancer normalement au milieu de la fenêtre', () => {
    globalThis.document = { activeElement: middle }
    const event = tabEvent()
    trapTabKey(event, container)
    expect(event.preventDefault).not.toHaveBeenCalled()
    expect(globalThis.document.activeElement).toBe(middle)
  })

  it('ignore les autres touches', () => {
    globalThis.document = { activeElement: last }
    const event = tabEvent(false, 'Enter')
    trapTabKey(event, container)
    expect(event.preventDefault).not.toHaveBeenCalled()
  })
})

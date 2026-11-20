import { describe, expect, it } from 'vitest'
import { SHORTCUT_GROUPS, displayKey, isApplePlatform, monthStepFromKey, pageShortcutIndex } from './shortcuts'

describe('shortcuts', () => {
  it('chaque groupe a un titre et des raccourcis complets', () => {
    expect(SHORTCUT_GROUPS.length).toBeGreaterThan(0)
    for (const group of SHORTCUT_GROUPS) {
      expect(group.title).toBeTruthy()
      expect(group.items.length).toBeGreaterThan(0)
      for (const item of group.items) {
        expect(item.label).toBeTruthy()
        expect(item.keys.length).toBeGreaterThan(0)
        for (const combo of item.keys) expect(combo.length).toBeGreaterThan(0)
      }
    }
  })

  it('documente les raccourcis globaux gérés par App.vue', () => {
    const global = SHORTCUT_GROUPS[0].items.map(item => item.keys[0].join('+'))
    expect(global).toEqual(expect.arrayContaining(['Ctrl+K', '/', 'N', '?']))
  })

  it('remplace Ctrl par ⌘ sur Mac uniquement', () => {
    expect(displayKey('Ctrl', true)).toBe('⌘')
    expect(displayKey('Ctrl', false)).toBe('Ctrl')
    expect(displayKey('K', true)).toBe('K')
  })

  it('reconnaît les plateformes Apple', () => {
    expect(isApplePlatform('MacIntel')).toBe(true)
    expect(isApplePlatform('iPhone')).toBe(true)
    expect(isApplePlatform('Win32')).toBe(false)
  })

  it("Alt+chiffre donne l'index de la page, quelle que soit la disposition du clavier", () => {
    expect(pageShortcutIndex({ altKey: true, code: 'Digit1', key: '&' }, 7)).toBe(0)
    expect(pageShortcutIndex({ altKey: true, code: 'Numpad7' }, 7)).toBe(6)
  })

  it('ignore les chiffres sans Alt, avec un autre modificateur ou au-delà du nombre de pages', () => {
    expect(pageShortcutIndex({ altKey: false, code: 'Digit1' }, 7)).toBeNull()
    expect(pageShortcutIndex({ altKey: true, ctrlKey: true, code: 'Digit1' }, 7)).toBeNull()
    expect(pageShortcutIndex({ altKey: true, shiftKey: true, code: 'Digit1' }, 7)).toBeNull()
    expect(pageShortcutIndex({ altKey: true, code: 'Digit8' }, 7)).toBeNull()
    expect(pageShortcutIndex({ altKey: true, code: 'KeyA' }, 7)).toBeNull()
  })

  it('← / → changent de mois hors des éléments qui utilisent déjà les flèches', () => {
    const page = { closest: () => null }
    const cell = { closest: selector => (selector.includes('table') ? {} : null) }
    expect(monthStepFromKey({ key: 'ArrowLeft', target: page })).toBe(-1)
    expect(monthStepFromKey({ key: 'ArrowRight', target: page })).toBe(1)
    expect(monthStepFromKey({ key: 'ArrowRight', target: cell })).toBeNull()
    expect(monthStepFromKey({ key: 'ArrowLeft', altKey: true, target: page })).toBeNull()
    expect(monthStepFromKey({ key: 'ArrowUp', target: page })).toBeNull()
  })
})

import { describe, expect, it } from 'vitest'
import { SHORTCUT_GROUPS, displayKey, isApplePlatform } from './shortcuts'

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
})

import { describe, expect, it } from 'vitest'
import {
  DEFAULT_THEME,
  THEME_STORAGE_KEY,
  applyThemePreference,
  normalizeThemePreference,
  readThemePreference,
  resolveTheme,
  saveThemePreference
} from './theme'

function memoryStorage(initial = {}) {
  const data = { ...initial }
  return {
    data,
    getItem: (key) => (key in data ? data[key] : null),
    setItem: (key, value) => { data[key] = String(value) },
    removeItem: (key) => { delete data[key] }
  }
}

describe('theme', () => {
  it('ramène toute valeur inconnue au thème clair, par défaut', () => {
    expect(DEFAULT_THEME).toBe('light')
    expect(normalizeThemePreference('dark')).toBe('dark')
    expect(normalizeThemePreference('system')).toBe('system')
    expect(normalizeThemePreference('violet')).toBe('light')
    expect(normalizeThemePreference(null)).toBe('light')
  })

  it('lit la préférence mémorisée, clair sans choix', () => {
    expect(readThemePreference(memoryStorage({ [THEME_STORAGE_KEY]: 'dark' }))).toBe('dark')
    expect(readThemePreference(memoryStorage({ [THEME_STORAGE_KEY]: 'system' }))).toBe('system')
    expect(readThemePreference(memoryStorage())).toBe('light')
  })

  it('reste en clair si le stockage est inaccessible', () => {
    const broken = { getItem: () => { throw new Error('bloqué') } }
    expect(readThemePreference(broken)).toBe('light')
  })

  it('mémorise « system » et « dark », pas le thème par défaut', () => {
    const storage = memoryStorage({ [THEME_STORAGE_KEY]: 'dark' })
    saveThemePreference('system', storage)
    expect(storage.data[THEME_STORAGE_KEY]).toBe('system')
    saveThemePreference('light', storage)
    expect(storage.data[THEME_STORAGE_KEY]).toBeUndefined()
  })

  it('pose ou retire data-theme sur la racine', () => {
    const root = { dataset: {} }
    applyThemePreference('dark', root)
    expect(root.dataset.theme).toBe('dark')
    applyThemePreference('system', root)
    expect(root.dataset.theme).toBeUndefined()
  })
})

describe('resolveTheme', () => {
  it('suit le système uniquement pour la préférence « system »', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
    expect(resolveTheme('violet', true)).toBe('light')
  })
})

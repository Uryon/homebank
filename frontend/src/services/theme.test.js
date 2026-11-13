import { describe, expect, it } from 'vitest'
import {
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
  it('ramène toute valeur inconnue à « system »', () => {
    expect(normalizeThemePreference('dark')).toBe('dark')
    expect(normalizeThemePreference('light')).toBe('light')
    expect(normalizeThemePreference('violet')).toBe('system')
    expect(normalizeThemePreference(null)).toBe('system')
  })

  it('lit la préférence mémorisée', () => {
    expect(readThemePreference(memoryStorage({ [THEME_STORAGE_KEY]: 'dark' }))).toBe('dark')
    expect(readThemePreference(memoryStorage())).toBe('system')
  })

  it('suit le système si le stockage est inaccessible', () => {
    const broken = { getItem: () => { throw new Error('bloqué') } }
    expect(readThemePreference(broken)).toBe('system')
  })

  it('ne mémorise rien pour « system » et efface un choix précédent', () => {
    const storage = memoryStorage({ [THEME_STORAGE_KEY]: 'dark' })
    saveThemePreference('system', storage)
    expect(storage.data[THEME_STORAGE_KEY]).toBeUndefined()
    saveThemePreference('light', storage)
    expect(storage.data[THEME_STORAGE_KEY]).toBe('light')
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
    expect(resolveTheme('violet', true)).toBe('dark')
  })
})

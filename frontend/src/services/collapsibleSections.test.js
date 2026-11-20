import { describe, expect, it } from 'vitest'
import { readCollapsed, saveCollapsed } from './collapsibleSections'

function memoryStorage(initial = {}) {
  const data = { ...initial }
  return { getItem: (k) => data[k] ?? null, setItem: (k, v) => { data[k] = v }, data }
}

describe('mémoire des sections repliées', () => {
  it('relit ce qui a été enregistré', () => {
    const storage = memoryStorage()
    saveCollapsed('k', ['solde', 'resume'], storage)
    expect(readCollapsed('k', storage)).toEqual(['solde', 'resume'])
  })

  it('ignore une valeur illisible ou un stockage indisponible', () => {
    expect(readCollapsed('k', memoryStorage({ k: '{pas du json' }))).toEqual([])
    expect(readCollapsed('k', memoryStorage({ k: '{"a":1}' }))).toEqual([])
    const throwing = { getItem: () => { throw new Error('bloqué') }, setItem: () => { throw new Error('bloqué') } }
    expect(readCollapsed('k', throwing)).toEqual([])
    expect(() => saveCollapsed('k', ['x'], throwing)).not.toThrow()
  })
})

describe('createCollapsibleSections', () => {
  it('replie puis déplie une section et mémorise l\'état', async () => {
    const { createCollapsibleSections } = await import('./collapsibleSections')
    const storage = memoryStorage({ k: '["resume"]' })
    const sections = createCollapsibleSections('k', storage)
    expect(sections.collapsed.value.has('resume')).toBe(true)
    sections.toggle('solde')
    sections.toggle('resume')
    expect([...sections.collapsed.value]).toEqual(['solde'])
    expect(readCollapsed('k', storage)).toEqual(['solde'])
  })
})

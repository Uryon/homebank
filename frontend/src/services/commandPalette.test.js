import { describe, expect, it } from 'vitest'
import {
  ACTION_COMMANDS,
  PAGE_COMMANDS,
  buildResults,
  filterIndex,
  indexCommands,
  indexPayees,
  searchTransactionsCommand
} from './commandPalette'

const staticIndex = indexCommands([...PAGE_COMMANDS, ...ACTION_COMMANDS])
const payeeIndex = indexPayees([
  { id: 1, name: 'Carrefour' },
  { id: 2, name: 'Mc Donald' },
  { id: 3, name: 'Le palais des thés' }
])

describe('commandPalette', () => {
  it('sans saisie, liste toutes les commandes fixes et aucun tiers', () => {
    const results = buildResults({ staticIndex, payeeIndex, query: '' })
    expect(results).toHaveLength(PAGE_COMMANDS.length + ACTION_COMMANDS.length)
    expect(results.some(r => r.group === 'Tiers')).toBe(false)
  })

  it('ignore accents et majuscules', () => {
    const labels = filterIndex(staticIndex, 'CATEGORIE', 5).map(c => c.label)
    expect(labels).toContain('Catégories')
    expect(filterIndex(payeeIndex, 'thes', 5).map(c => c.label)).toEqual(['Le palais des thés'])
  })

  it('exige tous les mots, dans n’importe quel ordre', () => {
    expect(filterIndex(payeeIndex, 'donald mc', 5).map(c => c.label)).toEqual(['Mc Donald'])
    expect(filterIndex(payeeIndex, 'mc carrefour', 5)).toEqual([])
  })

  it('place en tête les libellés qui commencent par la saisie', () => {
    const index = indexPayees([{ id: 1, name: 'Super Carrefour' }, { id: 2, name: 'Carrefour' }])
    expect(filterIndex(index, 'carre', 5).map(c => c.label)).toEqual(['Carrefour', 'Super Carrefour'])
  })

  it('cherche aussi dans les mots-clés', () => {
    expect(filterIndex(staticIndex, 'nuit', 5).map(c => c.id)).toEqual(['action-theme-dark'])
  })

  it('propose la recherche dans les transactions en dernier dès qu’un texte est saisi', () => {
    const results = buildResults({ staticIndex, payeeIndex, query: 'carrefour' })
    expect(results.at(-1).to).toEqual({ path: '/transactions', query: { search: 'carrefour' } })
    expect(results.some(r => r.id === 'payee-1')).toBe(true)
    expect(searchTransactionsCommand('   ')).toBeNull()
  })
})

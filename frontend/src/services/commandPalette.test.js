import { describe, expect, it } from 'vitest'
import {
  ACTION_COMMANDS,
  PAGE_COMMANDS,
  buildResults,
  highlightSegments,
  filterIndex,
  indexCategories,
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

  describe('catégories', () => {
    const categoryIndex = indexCategories([
      { id: 1, name: 'Alimentation', icon: '🍽', parentId: null },
      { id: 2, name: 'Restaurant', icon: '🍔', parentId: 1 },
      { id: 3, name: 'Divers', parentId: null },
      { id: 4, name: 'Ancienne', parentId: null, hidden: true }
    ])

    it('une catégorie assignable ouvre ses transactions par recherche exacte de son libellé', () => {
      const [restaurant] = filterIndex(categoryIndex, 'restaurant', 5)
      expect(restaurant.label).toBe('🍽 Alimentation : 🍔 Restaurant')
      expect(restaurant.to).toEqual({ path: '/transactions', query: { search: '"🍽 Alimentation : 🍔 Restaurant"' } })
      expect(filterIndex(categoryIndex, 'divers', 5)[0].to.query.search).toBe('"Divers"')
    })

    it('une racine à sous-catégories cherche le préfixe commun à ses enfants', () => {
      const [alimentation] = filterIndex(categoryIndex, 'alim', 1)
      expect(alimentation.to.query.search).toBe('🍽 Alimentation :')
    })

    it('trouve une sous-catégorie par le nom de son parent, ignore les catégories masquées', () => {
      expect(filterIndex(categoryIndex, 'alimentation restaurant', 5).map(c => c.id)).toEqual(['category-2'])
      expect(filterIndex(categoryIndex, 'ancienne', 5)).toEqual([])
    })

    it('apparaît dans les résultats seulement quand un texte est saisi', () => {
      expect(buildResults({ staticIndex, payeeIndex, categoryIndex, query: '' }).some(r => r.group === 'Catégories')).toBe(false)
      expect(buildResults({ staticIndex, payeeIndex, categoryIndex, query: 'divers' }).some(r => r.id === 'category-3')).toBe(true)
    })
  })
})

describe('highlightSegments', () => {
  const marked = segments => segments.filter(s => s.match).map(s => s.text)

  it('renvoie le libellé entier, non surligné, sans saisie', () => {
    expect(highlightSegments('Carrefour', '  ')).toEqual([{ text: 'Carrefour', match: false }])
  })

  it('surligne sans tenir compte de la casse ni des accents', () => {
    expect(highlightSegments('Logement : Énergie', 'energie')).toEqual([
      { text: 'Logement : ', match: false },
      { text: 'Énergie', match: true }
    ])
  })

  it('surligne chaque mot saisi, dans le désordre, et toutes ses occurrences', () => {
    expect(marked(highlightSegments('Alimentation : Supermarché', 'super ali'))).toEqual(['Ali', 'Super'])
    expect(marked(highlightSegments('Banana', 'an'))).toEqual(['anan'])
  })

  it('reconstitue exactement le libellé d\'origine', () => {
    const label = 'Rechercher « été » dans les transactions'
    expect(highlightSegments(label, 'ete').map(s => s.text).join('')).toBe(label)
    expect(marked(highlightSegments(label, 'ete'))).toEqual(['été'])
  })
})

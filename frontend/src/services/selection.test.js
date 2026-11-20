import { describe, expect, it } from 'vitest'
import { idsBetween, useIdSelection } from './selection'

const items = [{ id: 10 }, { id: 20 }, { id: 30 }, { id: 40 }]

describe('idsBetween', () => {
  it('donne les lignes entre les deux bornes comprises, dans les deux sens', () => {
    expect(idsBetween(items, 20, 40)).toEqual([20, 30, 40])
    expect(idsBetween(items, 40, 20)).toEqual([20, 30, 40])
  })

  it('donne la ligne seule quand les deux bornes sont identiques', () => {
    expect(idsBetween(items, 30, 30)).toEqual([30])
  })

  it('donne null si une borne n\'est pas affichée', () => {
    expect(idsBetween(items, 99, 20)).toBeNull()
    expect(idsBetween(items, 20, null)).toBeNull()
  })
})

describe('useIdSelection', () => {
  it('sélectionne puis désélectionne toutes les lignes affichées sans toucher aux autres', () => {
    const { selectedIds, allSelectedIn, toggleAllIn } = useIdSelection()
    selectedIds.value = [99]
    toggleAllIn([1, 2])
    expect(selectedIds.value).toEqual([99, 1, 2])
    expect(allSelectedIn([1, 2])).toBe(true)
    toggleAllIn([1, 2])
    expect(selectedIds.value).toEqual([99])
  })

  it('complète une sélection partielle au lieu de la retirer', () => {
    const { selectedIds, toggleAllIn } = useIdSelection()
    selectedIds.value = [1]
    toggleAllIn([1, 2])
    expect(selectedIds.value).toEqual([1, 2])
  })

  it('ne considère jamais une liste vide comme entièrement sélectionnée', () => {
    expect(useIdSelection().allSelectedIn([])).toBe(false)
  })

  it('retire les éléments disparus et vide la sélection', () => {
    const { selectedIds, keepExisting, clearSelection } = useIdSelection()
    selectedIds.value = [1, 2, 3]
    keepExisting([3, 1])
    expect(selectedIds.value).toEqual([1, 3])
    clearSelection()
    expect(selectedIds.value).toEqual([])
  })
})

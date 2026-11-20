import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { createRowSelection, shouldClearOnEscape } from './rowSelection'

const all = [
  { id: 1, amount: -10 }, { id: 2, amount: -20 }, { id: 3, amount: 100 }, { id: 4, amount: -5 }, { id: 9, amount: 7 }
]

function setup(visibleIds = [1, 2, 3, 4]) {
  const visible = ref(all.filter(r => visibleIds.includes(r.id)))
  return { visible, selection: createRowSelection(visible, () => all) }
}

describe('createRowSelection', () => {
  it('coche et décoche une ligne, en retenant la dernière cochée', () => {
    const { selection } = setup()
    selection.toggleSelect(all[1])
    expect([...selection.selectedIds.value]).toEqual([2])
    expect(selection.lastSelectedId.value).toBe(2)
    selection.toggleSelect(all[1])
    expect(selection.selectedIds.value.size).toBe(0)
    expect(selection.lastSelectedId.value).toBeNull()
  })

  it('coche une plage avec Maj, dans l\'ordre affiché', () => {
    const { selection } = setup()
    selection.toggleSelect(all[3])
    selection.toggleSelect(all[1], { range: true })
    expect([...selection.selectedIds.value].sort()).toEqual([2, 3, 4])
  })

  it('se rabat sur une sélection simple quand la dernière cochée n\'est plus affichée', () => {
    const { selection } = setup()
    selection.toggleSelect(all[4]) // id 9, hors des lignes affichées
    selection.toggleSelect(all[0], { range: true })
    expect([...selection.selectedIds.value].sort()).toEqual([1, 9])
  })

  it('coche puis décoche toutes les lignes affichées sans toucher aux autres', () => {
    const { selection } = setup([1, 2])
    selection.toggleSelect(all[4])
    selection.toggleSelectAllVisible()
    expect(selection.allVisibleSelected.value).toBe(true)
    expect([...selection.selectedIds.value].sort()).toEqual([1, 2, 9])
    selection.toggleSelectAllVisible()
    expect([...selection.selectedIds.value]).toEqual([9])
  })

  it('totalise et restitue les lignes cochées, même hors du filtre affiché', () => {
    const { selection } = setup([1])
    selection.toggleSelect(all[0])
    selection.toggleSelect(all[4])
    expect(selection.selectedTotal.value).toBe(-3)
    expect(selection.selectedRows().map(r => r.id)).toEqual([1, 9])
    selection.clearSelection()
    expect(selection.selectedTotal.value).toBe(0)
  })
})

describe('shouldClearOnEscape', () => {
  const noDialog = { querySelector: () => null }
  const escape = (target = { tagName: 'BODY' }) => ({ key: 'Escape', target })

  it('vide la sélection sur Échap', () => {
    expect(shouldClearOnEscape(escape(), true, () => false, noDialog)).toBe(true)
  })

  it('laisse Échap à la saisie, aux fenêtres et à l\'édition en cours', () => {
    expect(shouldClearOnEscape(escape({ tagName: 'INPUT', type: 'text' }), true, () => false, noDialog)).toBe(false)
    expect(shouldClearOnEscape(escape({ tagName: 'INPUT', type: 'checkbox' }), true, () => false, noDialog)).toBe(true)
    expect(shouldClearOnEscape(escape(), true, () => false, { querySelector: () => ({}) })).toBe(false)
    expect(shouldClearOnEscape(escape(), true, () => true, noDialog)).toBe(false)
    expect(shouldClearOnEscape(escape(), false, () => false, noDialog)).toBe(false)
    expect(shouldClearOnEscape({ key: 'Enter', target: {} }, true, () => false, noDialog)).toBe(false)
  })
})

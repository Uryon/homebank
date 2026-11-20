import { describe, expect, it, vi } from 'vitest'
import { handleRowEnter, neighborRowId, transactionRowAction } from './rowKeyboard'

function fakeEvent(onRowItself = true) {
  const row = {}
  return { target: onRowItself ? row : {}, currentTarget: row, preventDefault: vi.fn() }
}

describe('handleRowEnter', () => {
  it('ouvre l’édition quand la ligne elle-même a le focus', () => {
    const event = fakeEvent()
    const startEdit = vi.fn()
    handleRowEnter(event, { isEditing: false, confirm: vi.fn(), startEdit })
    expect(startEdit).toHaveBeenCalledOnce()
    expect(event.preventDefault).toHaveBeenCalled()
  })

  it('laisse une case à cocher ou un bouton de la ligne gérer Entrée lui-même', () => {
    const event = fakeEvent(false)
    const startEdit = vi.fn()
    handleRowEnter(event, { isEditing: false, confirm: vi.fn(), startEdit })
    expect(startEdit).not.toHaveBeenCalled()
    expect(event.preventDefault).not.toHaveBeenCalled()
  })

  it('enregistre quand la ligne est en édition, quel que soit le champ', () => {
    const event = fakeEvent(false)
    const confirm = vi.fn()
    handleRowEnter(event, { isEditing: true, confirm, startEdit: vi.fn() })
    expect(confirm).toHaveBeenCalledOnce()
  })
})

describe('transactionRowAction', () => {
  function keyOnRow(key, extra = {}) {
    const row = {}
    return { key, target: row, currentTarget: row, ...extra }
  }

  it('associe Espace, V, D et Suppr aux actions de la ligne', () => {
    expect(transactionRowAction(keyOnRow(' '), false)).toBe('toggle-select')
    expect(transactionRowAction(keyOnRow('v'), false)).toBe('toggle-validated')
    expect(transactionRowAction(keyOnRow('V'), false)).toBe('toggle-validated')
    expect(transactionRowAction(keyOnRow('Delete'), false)).toBe('remove')
    expect(transactionRowAction(keyOnRow('d'), false)).toBe('duplicate')
  })

  it('ne fait rien en édition, depuis un élément de la ligne, avec un modificateur ou une autre touche', () => {
    expect(transactionRowAction(keyOnRow('v'), true)).toBeNull()
    expect(transactionRowAction({ key: ' ', target: {}, currentTarget: {} }, false)).toBeNull()
    expect(transactionRowAction(keyOnRow('v', { ctrlKey: true }), false)).toBeNull()
    expect(transactionRowAction(keyOnRow('x'), false)).toBeNull()
  })
})

describe('neighborRowId', () => {
  const items = [{ id: 1 }, { id: 2 }, { id: 3 }]

  it('donne la ligne suivante, ou la précédente pour la dernière', () => {
    expect(neighborRowId(items, 1)).toBe(2)
    expect(neighborRowId(items, 3)).toBe(2)
  })

  it('donne null pour une ligne seule ou absente', () => {
    expect(neighborRowId([{ id: 1 }], 1)).toBeNull()
    expect(neighborRowId(items, 9)).toBeNull()
  })
})

import { describe, expect, it, vi } from 'vitest'
import { handleRowEnter } from './rowKeyboard'

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

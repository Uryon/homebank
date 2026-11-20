import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

vi.mock('./api', () => ({
  CategoriesAPI: { merge: vi.fn(), update: vi.fn() },
  PayeesAPI: {},
  TransactionsAPI: {}
}))
vi.mock('./feedback', () => ({ notify: vi.fn() }))
vi.mock('./errors', () => ({ alertApiError: vi.fn() }))
vi.mock('./dataStore', () => ({ dataStore: { state: {}, refreshTransactions: vi.fn(), refreshPayees: vi.fn() } }))
vi.mock('./categoryRestore', () => ({
  snapshotCategoryState: vi.fn(() => 'photo'),
  planCategoryStateRestore: vi.fn(() => 'plan'),
  isEmptyRestorePlan: vi.fn(() => false),
  restoreCategoryState: vi.fn()
}))

const { CategoriesAPI } = await import('./api')
const { alertApiError } = await import('./errors')
const { useCategoryStructureActions } = await import('./categoryStructureActions')

const categories = [
  { id: 1, name: 'Alimentation', type: 'EXPENSE', parentId: null, icon: null },
  { id: 2, name: 'Courses', type: 'EXPENSE', parentId: 1, icon: '🛒' },
  { id: 3, name: 'Café', type: 'EXPENSE', parentId: 1, icon: null },
  { id: 7, name: 'Loisirs', type: 'EXPENSE', parentId: null, icon: null }
]

function setup(selected = []) {
  const byId = new Map(categories.map(c => [c.id, c]))
  const load = vi.fn().mockResolvedValue()
  const setLastAction = vi.fn()
  const clearSelection = vi.fn()
  const actions = useCategoryStructureActions({
    roots: ref(categories.filter(c => !c.parentId)),
    categoryById: ref(byId),
    children: id => categories.filter(c => c.parentId === id),
    selection: { selectedIds: ref(selected), selectedIdSet: ref(new Set(selected)), clearSelection },
    load,
    setLastAction
  })
  return { actions, load, setLastAction, clearSelection }
}

const dragEvent = () => ({ preventDefault: vi.fn(), dataTransfer: { setData: vi.fn() } })

describe('useCategoryStructureActions', () => {
  beforeEach(() => vi.clearAllMocks())

  it('fusion : cible pré-choisie, sources sans la cible, annulation proposée', async () => {
    const { actions, setLastAction, clearSelection } = setup([2, 3])
    actions.openMergeModal()
    expect(actions.mergeTargetId.value).toBe(2)

    await actions.confirmMerge()

    expect(CategoriesAPI.merge).toHaveBeenCalledWith([3], 2)
    expect(actions.showMergeModal.value).toBe(false)
    expect(clearSelection).toHaveBeenCalled()
    expect(setLastAction.mock.calls[0][0]).toBe('Fusion de catégories')
  })

  it('changement de parente : seules les sous-catégories cochées sont déplacées, icône conservée', async () => {
    const { actions } = setup([1, 2])
    actions.openReparentModal()
    actions.reparentTargetId.value = 7

    await actions.confirmReparent()

    expect(actions.reparentableIds.value).toEqual([2])
    expect(CategoriesAPI.update).toHaveBeenCalledTimes(1)
    expect(CategoriesAPI.update).toHaveBeenCalledWith(2, { name: 'Courses', type: 'EXPENSE', parentId: 7, icon: '🛒' })
  })

  it('glisser-déposer vers une autre racine : déplace et propose l’annulation', async () => {
    const { actions, setLastAction } = setup()
    actions.onDragStart(dragEvent(), categories[2])
    actions.onRootDragOver({ ...dragEvent(), dataTransfer: {} }, categories[3])
    expect(actions.dragOverRootId.value).toBe(7)

    await actions.onRootDrop(dragEvent(), categories[3])

    expect(CategoriesAPI.update).toHaveBeenCalledWith(3, { name: 'Café', type: 'EXPENSE', parentId: 7, icon: null })
    expect(actions.draggedCategoryId.value).toBeNull()
    expect(setLastAction.mock.calls[0][0]).toBe('Sous-catégorie déplacée')
  })

  it('dépôt sur sa propre racine, ou sans glissement en cours : rien n’est envoyé', async () => {
    const { actions } = setup()
    await actions.onRootDrop(dragEvent(), categories[0])
    actions.onDragStart(dragEvent(), categories[2])
    await actions.onRootDrop(dragEvent(), categories[0])

    expect(CategoriesAPI.update).not.toHaveBeenCalled()
  })

  it('échec du serveur : erreur affichée, aucune annulation proposée', async () => {
    CategoriesAPI.merge.mockRejectedValueOnce(new Error('cycle'))
    const { actions, setLastAction } = setup([2, 3])
    actions.openMergeModal()

    await actions.confirmMerge()

    expect(alertApiError).toHaveBeenCalled()
    expect(actions.merging.value).toBe(false)
    expect(setLastAction).not.toHaveBeenCalled()
  })
})

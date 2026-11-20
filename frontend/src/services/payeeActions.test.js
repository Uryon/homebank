import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

vi.mock('./api', () => ({
  PayeesAPI: {
    autoCategorize: vi.fn(), update: vi.fn(), remove: vi.fn(), create: vi.fn(), merge: vi.fn(),
    previewCleanup: vi.fn(), cleanup: vi.fn(), undoCleanup: vi.fn()
  },
  TransactionsAPI: { bulkUpdate: vi.fn() }
}))
vi.mock('./feedback', () => ({ notify: vi.fn(), askConfirm: vi.fn() }))
vi.mock('./errors', () => ({ alertApiError: vi.fn() }))
vi.mock('./dataStore', () => ({
  dataStore: { state: { payees: [], transactions: [] }, refreshPayees: vi.fn(), refreshTransactions: vi.fn() }
}))

const { PayeesAPI, TransactionsAPI } = await import('./api')
const { askConfirm } = await import('./feedback')
const { alertApiError } = await import('./errors')
const { usePayeeAutoCategorize } = await import('./payeeAutoCategorize')
const { usePayeeSelectionActions } = await import('./payeeSelectionActions')
const { usePayeeCleanup } = await import('./payeeCleanupActions')

const carrefour = { id: 1, name: 'Carrefour', pattern: 'CARREFOUR', amount: null, categoryId: null }
const vinted = { id: 2, name: 'Vinted', pattern: 'VINTED', amount: null, categoryId: 7 }

let refreshAfterMutation
let setLastAction
beforeEach(() => {
  vi.clearAllMocks()
  askConfirm.mockResolvedValue(true)
  refreshAfterMutation = vi.fn().mockResolvedValue()
  setLastAction = vi.fn()
})

describe('usePayeeAutoCategorize', () => {
  it('l’annulation retire la catégorie des tiers et des transactions concernés', async () => {
    PayeesAPI.autoCategorize.mockResolvedValue({ payeeIds: [1], transactionIds: [10, 11] })
    const auto = usePayeeAutoCategorize({ payees: ref([carrefour]), refreshAfterMutation, setLastAction })

    await auto.runAutoCategorize()

    expect(auto.autoCategorizeResult.value).toEqual({ payeeIds: [1], transactionIds: [10, 11] })
    expect(auto.autoCategorizing.value).toBe(false)
    await setLastAction.mock.calls[0][1]()
    expect(PayeesAPI.update).toHaveBeenCalledWith(1, { name: 'Carrefour', pattern: 'CARREFOUR', amount: null, categoryId: null })
    expect(TransactionsAPI.bulkUpdate).toHaveBeenCalledWith({ ids: [10, 11], categoryIdProvided: true, categoryId: null })
    expect(auto.autoCategorizeResult.value).toBeNull()
  })

  it('rien de catégorisé : aucune annulation proposée', async () => {
    PayeesAPI.autoCategorize.mockResolvedValue({ payeeIds: [], transactionIds: [] })
    const auto = usePayeeAutoCategorize({ payees: ref([]), refreshAfterMutation, setLastAction })

    await auto.runAutoCategorize()

    expect(setLastAction).not.toHaveBeenCalled()
  })
})

describe('usePayeeSelectionActions', () => {
  function setup(selected) {
    const selectedIds = ref(selected)
    const clearSelection = vi.fn()
    return {
      clearSelection,
      actions: usePayeeSelectionActions({
        payees: ref([carrefour, vinted]),
        selection: { selectedIds, selectedIdSet: ref(new Set(selected)), clearSelection },
        isUnused: p => p.id === 1,
        refreshAfterMutation,
        setLastAction
      })
    }
  }

  it('la suppression groupée n’est proposée que si aucun tiers coché n’a de transaction', () => {
    expect(setup([1]).actions.selectionIsUnused.value).toBe(true)
    expect(setup([1, 2]).actions.selectionIsUnused.value).toBe(false)
  })

  it('supprime ce qui peut l’être, signale l’échec, et l’annulation recrée les tiers supprimés', async () => {
    PayeesAPI.remove.mockImplementation(id => (id === 2 ? Promise.reject(new Error('utilisé')) : Promise.resolve()))
    const { actions } = setup([1, 2])

    await actions.removeSelected()

    expect(alertApiError).toHaveBeenCalledTimes(1)
    expect(setLastAction.mock.calls[0][0]).toBe('Tiers supprimé')
    await setLastAction.mock.calls[0][1]()
    expect(PayeesAPI.create).toHaveBeenCalledTimes(1)
    expect(PayeesAPI.create).toHaveBeenCalledWith({ name: 'Carrefour', pattern: 'CARREFOUR', amount: null, categoryId: null })
  })

  it('fusion : envoie les tiers source sans le tiers conservé, puis vide la sélection', async () => {
    const { actions, clearSelection } = setup([1, 2])
    actions.openMergeModal()

    await actions.confirmMerge({ targetId: 2, pattern: 'VINTED;CARREFOUR' })

    expect(PayeesAPI.merge).toHaveBeenCalledWith([1], 2, 'VINTED;CARREFOUR')
    expect(actions.showMergeModal.value).toBe(false)
    expect(clearSelection).toHaveBeenCalled()
    expect(actions.merging.value).toBe(false)
    expect(setLastAction.mock.calls[0][0]).toBe('Fusion de tiers')
  })
})

describe('usePayeeCleanup', () => {
  it('un aperçu en échec n’empêche pas la page de fonctionner : aucun bandeau', async () => {
    PayeesAPI.previewCleanup.mockRejectedValue(new Error('réseau'))
    const cleanup = usePayeeCleanup({ refreshAfterMutation, setLastAction })

    await cleanup.loadCleanupItems()

    expect(cleanup.cleanupItems.value).toEqual([])
  })

  it('annonce le nombre de faux tiers au singulier comme au pluriel', async () => {
    const cleanup = usePayeeCleanup({ refreshAfterMutation, setLastAction })
    PayeesAPI.previewCleanup.mockResolvedValue([{ payeeId: 1 }])
    await cleanup.loadCleanupItems()
    expect(cleanup.cleanupNotice.value).toMatch(/^1 tiers a été créé/)
    PayeesAPI.previewCleanup.mockResolvedValue([{ payeeId: 1 }, { payeeId: 2 }])
    await cleanup.loadCleanupItems()
    expect(cleanup.cleanupNotice.value).toMatch(/^2 tiers ont été créés/)
  })

  it('nettoie, ferme la fenêtre et propose une annulation rejouable', async () => {
    PayeesAPI.cleanup.mockResolvedValue({ detachedTransactions: 3, deletedPayees: 1, payees: [{ id: 9 }] })
    PayeesAPI.previewCleanup.mockResolvedValue([])
    const cleanup = usePayeeCleanup({ refreshAfterMutation, setLastAction })
    cleanup.showCleanupModal.value = true

    await cleanup.confirmCleanup([9])

    expect(cleanup.showCleanupModal.value).toBe(false)
    const [label, undo, options] = setLastAction.mock.calls[0]
    expect(label).toBe('Faux tiers nettoyés')
    expect(options).toEqual({ retryable: true })
    await undo()
    expect(PayeesAPI.undoCleanup).toHaveBeenCalledWith([{ id: 9 }])
  })
})

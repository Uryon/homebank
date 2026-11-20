import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

vi.mock('./api', () => ({
  TransactionsAPI: { validateAll: vi.fn(), removePending: vi.fn(), removeBulk: vi.fn(), bulkUpdate: vi.fn() }
}))
vi.mock('./feedback', () => ({ notify: vi.fn(), askConfirm: vi.fn() }))
vi.mock('./errors', () => ({ alertApiError: vi.fn() }))
vi.mock('./dataStore', () => ({ dataStore: { hideDeletedTransactions: vi.fn() } }))

const { TransactionsAPI } = await import('./api')
const { askConfirm } = await import('./feedback')
const { dataStore } = await import('./dataStore')
const { useTransactionBulkActions } = await import('./transactionBulkActions')

const tx = (id, validated) => ({ id, accountId: 1, date: '2026-11-16', amount: -10, validated })

function setup({ rows = [tx(1, true), tx(2, false), tx(3, false)], filtered = 1, selected = [] } = {}) {
  const transactions = ref(rows)
  const selectedIds = ref(new Set(selected))
  const clearSelection = vi.fn(() => { selectedIds.value = new Set() })
  const ctx = {
    transactions,
    load: vi.fn().mockResolvedValue(),
    setLastAction: vi.fn(),
    offerRestore: vi.fn(),
    snapshotsBeforeDeletion: vi.fn(list => list.map(t => ({ ...t }))),
    clearSelection
  }
  const actions = useTransactionBulkActions({
    transactions,
    filterAccount: ref(filtered),
    accountsById: ref(new Map([[1, { id: 1, name: 'Courant' }]])),
    selection: { selectedIds, clearSelection, selectedRows: () => rows.filter(t => selectedIds.value.has(t.id)) },
    load: ctx.load,
    setLastAction: ctx.setLastAction,
    offerRestore: ctx.offerRestore,
    snapshotsBeforeDeletion: ctx.snapshotsBeforeDeletion
  })
  return { actions, ctx }
}

describe('useTransactionBulkActions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    askConfirm.mockResolvedValue(true)
  })

  it('compte les transactions en attente de la liste courante', () => {
    expect(setup().actions.pendingCount.value).toBe(2)
  })

  it('valide sur le compte filtré, et l’annulation remet exactement ces transactions en attente', async () => {
    const { actions, ctx } = setup()

    await actions.validateAll()

    expect(askConfirm.mock.calls[0][0]).toBe('Valider les 2 transactions en attente du compte « Courant » ?')
    expect(TransactionsAPI.validateAll).toHaveBeenCalledWith(1)
    expect(actions.validating.value).toBe(false)
    await ctx.setLastAction.mock.calls[0][1]()
    expect(TransactionsAPI.bulkUpdate).toHaveBeenCalledWith({ ids: [2, 3], validatedProvided: true, validated: false })
  })

  it('sans transaction en attente, ne demande rien', async () => {
    const { actions } = setup({ rows: [tx(1, true)] })

    await actions.validateAll()
    await actions.deletePending()

    expect(askConfirm).not.toHaveBeenCalled()
  })

  it('suppression des en attente : ne propose de restaurer que ce que le serveur a vraiment supprimé', async () => {
    const { actions, ctx } = setup()
    // Le serveur garde la transaction 3 (cote de virement dont la jumelle est validee).
    ctx.load.mockImplementation(async () => { ctx.transactions.value = [tx(1, true), tx(3, false)] })

    await actions.deletePending()

    expect(TransactionsAPI.removePending).toHaveBeenCalledWith(1)
    const [label, snapshots] = ctx.offerRestore.mock.calls[0]
    expect(label).toBe('Suppression de 1 transaction')
    expect(snapshots.map(t => t.id)).toEqual([2])
  })

  it('supprime la sélection, la vide et propose de la restaurer', async () => {
    const { actions, ctx } = setup({ selected: [1, 2] })

    await actions.deleteSelected()

    expect(TransactionsAPI.removeBulk).toHaveBeenCalledWith([1, 2])
    expect(ctx.clearSelection).toHaveBeenCalled()
    expect(dataStore.hideDeletedTransactions).toHaveBeenCalledWith([1, 2])
    expect(ctx.offerRestore.mock.calls[0][0]).toBe('Suppression de 2 transactions')
    expect(actions.deletingSelection.value).toBe(false)
  })

  it('modification en masse sans sélection : aucun appel', async () => {
    const { actions } = setup()

    await actions.saveBulkEdit({})

    expect(TransactionsAPI.bulkUpdate).not.toHaveBeenCalled()
  })

  it('modification en masse : ferme la fenêtre, vide la sélection et propose une annulation', async () => {
    const { actions, ctx } = setup({ selected: [2] })
    actions.openBulkEdit()

    await actions.saveBulkEdit({ validated: 'true' })

    expect(TransactionsAPI.bulkUpdate).toHaveBeenCalledTimes(1)
    expect(actions.showBulkEditModal.value).toBe(false)
    expect(ctx.clearSelection).toHaveBeenCalled()
    expect(ctx.setLastAction.mock.calls[0][0]).toBe('Modification de 1 transaction')
  })
})

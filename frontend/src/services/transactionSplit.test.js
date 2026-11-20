import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

vi.mock('./api', () => ({ TransactionsAPI: { split: vi.fn(), undoSplit: vi.fn() } }))
vi.mock('./feedback', () => ({ notify: vi.fn() }))

const { TransactionsAPI } = await import('./api')
const { notify } = await import('./feedback')
const { useTransactionSplit } = await import('./transactionSplit')

const courses = { id: 7, accountId: 1, date: '2026-11-16', amount: -130.26, payee: 'Intermarché', categoryId: 3, splitFromAmount: null }

function setup() {
  const load = vi.fn().mockResolvedValue()
  const setLastAction = vi.fn()
  const accountsById = ref(new Map([[1, { id: 1, currency: 'EUR' }]]))
  return { load, setLastAction, split: useTransactionSplit({ accountsById, load, setLastAction }) }
}

describe('useTransactionSplit', () => {
  beforeEach(() => vi.clearAllMocks())

  it('refuse un virement interne ou un ajustement de solde sans ouvrir la fenêtre', () => {
    const { split } = setup()

    split.openSplit({ ...courses, transferTransactionId: 9 })
    split.openSplit({ ...courses, balanceAdjustment: true })

    expect(split.splitTransaction.value).toBeNull()
    expect(notify.mock.calls.map(c => c[0])).toEqual([
      'Un virement entre comptes ne peut pas être divisé.',
      'Un ajustement de solde ne peut pas être divisé.'
    ])
  })

  it('ouvre la fenêtre avec la devise du compte de la transaction', () => {
    const { split } = setup()

    split.openSplit(courses)

    expect(split.splitTransaction.value).toEqual(courses)
    expect(split.splitCurrency.value).toBe('EUR')
  })

  it('divise, ferme la fenêtre et propose une annulation qui rend l’état d’origine', async () => {
    const { split, load, setLastAction } = setup()
    TransactionsAPI.split.mockResolvedValue([{ id: 7 }, { id: 8 }])
    split.openSplit(courses)

    await split.saveSplit([{ amount: -100 }, { amount: -30.26 }])

    expect(TransactionsAPI.split).toHaveBeenCalledWith(7, [{ amount: -100 }, { amount: -30.26 }])
    expect(split.splitTransaction.value).toBeNull()
    expect(split.splitSaving.value).toBe(false)
    expect(load).toHaveBeenCalledTimes(1)
    const [label, undo] = setLastAction.mock.calls[0]
    expect(label).toBe('Transaction divisée')
    await undo()
    const [id, request] = TransactionsAPI.undoSplit.mock.calls[0]
    expect(id).toBe(7)
    expect(request.secondPartId).toBe(8)
    expect(request.original).toMatchObject({ amount: -130.26, splitFromAmount: null })
  })

  it('garde la fenêtre ouverte avec le message du serveur en cas d’échec', async () => {
    const { split, setLastAction } = setup()
    TransactionsAPI.split.mockRejectedValue({ response: { data: { error: 'Montant invalide' } } })
    split.openSplit(courses)

    await split.saveSplit([])

    expect(split.splitTransaction.value).toEqual(courses)
    expect(split.splitError.value).toContain('Montant invalide')
    expect(split.splitSaving.value).toBe(false)
    expect(setLastAction).not.toHaveBeenCalled()
  })
})

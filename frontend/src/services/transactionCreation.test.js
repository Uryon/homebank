import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

vi.mock('./api', () => ({ TransactionsAPI: { create: vi.fn(), undoCreation: vi.fn() } }))
vi.mock('./feedback', () => ({ notify: vi.fn(), askConfirm: vi.fn() }))
vi.mock('./errors', () => ({ alertApiError: vi.fn() }))
vi.mock('./dataStore', () => ({ dataStore: { state: { transactions: [] }, showSavedTransaction: vi.fn() } }))

const { TransactionsAPI } = await import('./api')
const { askConfirm, notify } = await import('./feedback')
const { dataStore } = await import('./dataStore')
const { toLocalIsoDate } = await import('./format')
const { useTransactionCreation } = await import('./transactionCreation')

const courant = { id: 1, name: 'Courant' }
const livret = { id: 2, name: 'Livret A' }

function setup(filtered = null) {
  const load = vi.fn().mockResolvedValue()
  const setLastAction = vi.fn()
  const creation = useTransactionCreation({
    entryAccounts: ref([courant, livret]), filterAccount: ref(filtered), load, setLastAction
  })
  return { creation, load, setLastAction }
}

const form = { accountId: 1, date: '2026-11-20', payee: 'Carrefour', amount: -10.69, categoryId: 4, paymentMode: 'NONE', memo: '' }

describe('useTransactionCreation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    dataStore.state.transactions = []
  })

  it('propose le compte filtré, sinon le premier compte ouvert, daté d’aujourd’hui', () => {
    const filtre = setup(2)
    filtre.creation.openCreate()
    expect(filtre.creation.newTransactionDraft.value).toMatchObject({ accountId: 2, date: toLocalIsoDate(), amount: null })

    const tous = setup(99)
    tous.creation.openCreate()
    expect(tous.creation.newTransactionDraft.value.accountId).toBe(1)
  })

  it('duplique tiers, montant, catégorie et marqueurs à la date du jour', () => {
    const { creation } = setup()

    creation.duplicateTransaction({ accountId: 2, date: '2026-01-03', payee: 'EDF', amount: -224.05, categoryId: 9, periodicExpense: true })

    expect(creation.newTransactionDraft.value).toMatchObject({
      accountId: 2, date: toLocalIsoDate(), payee: 'EDF', amount: -224.05, categoryId: 9, periodicExpense: true, exceptional: false
    })
  })

  it('ne duplique pas un virement ni un ajustement de solde', () => {
    const { creation } = setup()

    creation.duplicateTransaction({ accountId: 1, transferTransactionId: 5 })
    creation.duplicateTransaction({ accountId: 1, balanceAdjustment: true })

    expect(creation.newTransactionDraft.value).toBeNull()
    expect(notify.mock.calls.map(c => c[0])).toEqual([
      'Un virement entre comptes se crée depuis la page Comptes.',
      'Un ajustement de solde se crée depuis la page Comptes.'
    ])
  })

  it('double saisie probable refusée à la confirmation : rien n’est créé', async () => {
    const { creation } = setup()
    dataStore.state.transactions = [{ id: 3, ...form }]
    askConfirm.mockResolvedValue(false)

    await creation.save(form)

    expect(askConfirm).toHaveBeenCalledTimes(1)
    expect(TransactionsAPI.create).not.toHaveBeenCalled()
  })

  it('crée, ferme la fenêtre et propose d’annuler la création', async () => {
    const { creation, load, setLastAction } = setup()
    TransactionsAPI.create.mockResolvedValue({ id: 42 })
    creation.openCreate()

    await creation.save(form)

    expect(creation.newTransactionDraft.value).toBeNull()
    expect(dataStore.showSavedTransaction).toHaveBeenCalledWith({ id: 42 })
    expect(load).toHaveBeenCalledTimes(1)
    const [label, undo] = setLastAction.mock.calls[0]
    expect(label).toBe('Transaction créée')
    await undo()
    expect(TransactionsAPI.undoCreation).toHaveBeenCalledWith([42])
  })
})

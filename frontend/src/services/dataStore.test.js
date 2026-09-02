import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * dataStore garde son etat (et la promesse de prefetch memoisee) au niveau
 * du module, comme un vrai singleton partage entre toutes les pages. Pour
 * isoler chaque test, on reinitialise le registre de modules et on
 * reimporte dataStore (et son mock de ./api) a chaque fois, plutot que de
 * reutiliser une seule instance entre les tests.
 */
vi.mock('./api', () => ({
  AccountsAPI: { list: vi.fn(), balances: vi.fn() },
  CategoriesAPI: { list: vi.fn() },
  PayeesAPI: { list: vi.fn() },
  TransactionsAPI: { list: vi.fn() }
}))

beforeEach(() => {
  vi.resetModules()
})

async function freshMocks() {
  const { AccountsAPI, CategoriesAPI, PayeesAPI, TransactionsAPI } = await import('./api')
  // resetModules() ne reinitialise QUE le registre de modules (donc dataStore.js
  // et son etat/prefetchPromise), pas l'historique des mocks vi.mock, qui reste
  // partage entre tests : on le vide explicitement ici a chaque test.
  vi.clearAllMocks()
  AccountsAPI.list.mockResolvedValue([{ id: 1, name: 'Compte courant' }])
  AccountsAPI.balances.mockResolvedValue({ 1: 100 })
  CategoriesAPI.list.mockResolvedValue([{ id: 1, name: 'Courses' }])
  PayeesAPI.list.mockResolvedValue([{ id: 1, name: 'Carrefour' }])
  TransactionsAPI.list.mockResolvedValue([{ id: 1, amount: -10 }])
  return { AccountsAPI, CategoriesAPI, PayeesAPI, TransactionsAPI }
}

describe('dataStore.prefetch', () => {
  it('charge les quatre ressources et met a jour l\'etat et les flags "loaded"', async () => {
    const { AccountsAPI, CategoriesAPI, PayeesAPI, TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.prefetch()

    expect(dataStore.state.accounts).toEqual([{ id: 1, name: 'Compte courant' }])
    expect(dataStore.state.balances).toEqual({ 1: 100 })
    expect(dataStore.state.categories).toEqual([{ id: 1, name: 'Courses' }])
    expect(dataStore.state.payees).toEqual([{ id: 1, name: 'Carrefour' }])
    expect(dataStore.state.transactions).toEqual([{ id: 1, amount: -10 }])
    expect(dataStore.state.loaded).toEqual({ accounts: true, categories: true, payees: true, transactions: true })
    expect(AccountsAPI.list).toHaveBeenCalledTimes(1)
    expect(CategoriesAPI.list).toHaveBeenCalledTimes(1)
    expect(PayeesAPI.list).toHaveBeenCalledTimes(1)
    expect(TransactionsAPI.list).toHaveBeenCalledTimes(1)
  })

  it('ne refait jamais les appels une deuxieme fois tant qu\'aucun refresh n\'est demande', async () => {
    const { AccountsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.prefetch()
    await dataStore.prefetch()

    expect(AccountsAPI.list).toHaveBeenCalledTimes(1)
  })

  it('ensureLoaded declenche un prefetch s\'il n\'y en a pas encore eu, puis ne le refait plus', async () => {
    const { TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.ensureLoaded()
    await dataStore.ensureLoaded()

    expect(TransactionsAPI.list).toHaveBeenCalledTimes(1)
  })

  it('deux appels concurrents a prefetch avant resolution partagent la meme promesse (pas de double appel)', async () => {
    const { AccountsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await Promise.all([dataStore.prefetch(), dataStore.prefetch()])

    expect(AccountsAPI.list).toHaveBeenCalledTimes(1)
  })
})

describe('dataStore.refreshAll', () => {
  it('recharge tout meme apres un prefetch deja fait, sans etre bloque par la memoisation', async () => {
    const { AccountsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.prefetch()
    await dataStore.refreshAll()

    expect(AccountsAPI.list).toHaveBeenCalledTimes(2)
  })
})

describe('rechargements cibles', () => {
  it('refreshAccounts ne recharge que les comptes et les soldes', async () => {
    const { AccountsAPI, CategoriesAPI, PayeesAPI, TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.refreshAccounts()

    expect(AccountsAPI.list).toHaveBeenCalledTimes(1)
    expect(AccountsAPI.balances).toHaveBeenCalledTimes(1)
    expect(CategoriesAPI.list).not.toHaveBeenCalled()
    expect(PayeesAPI.list).not.toHaveBeenCalled()
    expect(TransactionsAPI.list).not.toHaveBeenCalled()
    expect(dataStore.state.loaded.accounts).toBe(true)
  })

  it('refreshCategories ne recharge que les categories', async () => {
    const { AccountsAPI, CategoriesAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.refreshCategories()

    expect(CategoriesAPI.list).toHaveBeenCalledTimes(1)
    expect(AccountsAPI.list).not.toHaveBeenCalled()
  })

  it('refreshPayees ne recharge que les tiers', async () => {
    const { PayeesAPI, TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.refreshPayees()

    expect(PayeesAPI.list).toHaveBeenCalledTimes(1)
    expect(TransactionsAPI.list).not.toHaveBeenCalled()
  })

  it('refreshTransactions recharge les transactions ET les soldes des comptes ensemble', async () => {
    const { AccountsAPI, CategoriesAPI, PayeesAPI, TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.refreshTransactions()

    expect(TransactionsAPI.list).toHaveBeenCalledTimes(1)
    expect(AccountsAPI.list).toHaveBeenCalledTimes(1)
    expect(AccountsAPI.balances).toHaveBeenCalledTimes(1)
    expect(CategoriesAPI.list).not.toHaveBeenCalled()
    expect(PayeesAPI.list).not.toHaveBeenCalled()
    expect(dataStore.state.loaded.transactions).toBe(true)
    expect(dataStore.state.loaded.accounts).toBe(true)
  })
})

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

describe('affichage immédiat des changements confirmés', () => {
  it('remplace une transaction connue et ajoute une nouvelle, sans requête', async () => {
    const { TransactionsAPI } = await freshMocks()
    TransactionsAPI.list.mockResolvedValue([{ id: 1, memo: 'a' }, { id: 2, memo: 'b' }])
    const { dataStore } = await import('./dataStore')
    await dataStore.prefetch()
    TransactionsAPI.list.mockClear()

    dataStore.showSavedTransaction({ id: 2, memo: 'modifié' })
    dataStore.showSavedTransaction({ id: 3, memo: 'nouvelle' })

    expect(dataStore.state.transactions.map(t => [t.id, t.memo])).toEqual([[3, 'nouvelle'], [1, 'a'], [2, 'modifié']])
    expect(TransactionsAPI.list).not.toHaveBeenCalled()
  })

  it('retire les transactions supprimées et la jumelle de leur virement', async () => {
    const { TransactionsAPI } = await freshMocks()
    TransactionsAPI.list.mockResolvedValue([
      { id: 1, transferTransactionId: 2 },
      { id: 2, transferTransactionId: 1 },
      { id: 3 },
      { id: 4 }
    ])
    const { dataStore } = await import('./dataStore')
    await dataStore.prefetch()

    dataStore.hideDeletedTransactions([1, 3])

    expect(dataStore.state.transactions.map(t => t.id)).toEqual([4])
  })
})

describe('dataStore.applyTransactionUpdate', () => {
  it('garde la liste hors du suivi réactif, sans perdre le signal de changement', async () => {
    const { TransactionsAPI } = await freshMocks()
    const { isReactive, computed } = await import('vue')
    const { dataStore } = await import('./dataStore')
    await dataStore.prefetch()
    const count = computed(() => dataStore.state.transactions.length)
    expect(count.value).toBe(1)

    TransactionsAPI.list.mockResolvedValue([{ id: 1 }, { id: 2 }])
    await dataStore.refreshTransactions()

    expect(isReactive(dataStore.state.transactions)).toBe(false)
    expect(count.value).toBe(2)
  })

  it('remplace la transaction, aligne la jumelle du virement et ne recharge rien', async () => {
    const { TransactionsAPI } = await freshMocks()
    TransactionsAPI.list.mockResolvedValue([
      { id: 1, validated: false, transferTransactionId: 2, memo: 'a' },
      { id: 2, validated: false, transferTransactionId: 1, memo: 'b' },
      { id: 3, validated: false }
    ])
    const { dataStore } = await import('./dataStore')
    await dataStore.prefetch()
    const untouched = dataStore.state.transactions[2]

    dataStore.applyTransactionUpdate({ id: 1, validated: true, transferTransactionId: 2, memo: 'a' })

    expect(dataStore.state.transactions[0].validated).toBe(true)
    expect(dataStore.state.transactions[1]).toMatchObject({ validated: true, memo: 'b' })
    expect(dataStore.state.transactions[2]).toBe(untouched)
    expect(TransactionsAPI.list).toHaveBeenCalledTimes(1)
  })

  it('remplace un chargement en cours, qui a pu lire l\'ancienne valeur', async () => {
    const { TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')
    await dataStore.prefetch()
    let resolveOld
    TransactionsAPI.list.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
    const refresh = dataStore.refreshTransactions()
    TransactionsAPI.list.mockResolvedValue([{ id: 1, validated: true }])

    dataStore.applyTransactionUpdate({ id: 1, validated: true })
    resolveOld([{ id: 1, validated: false }])
    await refresh

    expect(dataStore.state.transactions).toEqual([{ id: 1, validated: true }])
  })
})

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

  it('apres un echec, un nouvel appel retente le chargement au lieu de renvoyer la promesse rejetee memoisee', async () => {
    const { AccountsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')
    AccountsAPI.list.mockRejectedValueOnce(new Error('reseau coupe'))

    await expect(dataStore.prefetch()).rejects.toThrow('reseau coupe')
    await dataStore.prefetch()

    expect(AccountsAPI.list).toHaveBeenCalledTimes(2)
    expect(dataStore.state.loaded.accounts).toBe(true)
  })
})

describe('dataStore.state.loadFailed', () => {
  it('passe a vrai quand le chargement echoue, puis a faux quand un nouvel essai reussit', async () => {
    const { AccountsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')
    AccountsAPI.list.mockRejectedValueOnce(new Error('serveur arrete'))

    expect(dataStore.state.loadFailed).toBe(false)
    await expect(dataStore.prefetch()).rejects.toThrow('serveur arrete')
    expect(dataStore.state.loadFailed).toBe(true)

    await dataStore.refreshAll()
    expect(dataStore.state.loadFailed).toBe(false)
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

/** Promesse controlable a la main, pour choisir l'ordre d'arrivee des reponses. */
function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

describe('chargements concurrents d\'une meme ressource', () => {
  it('une reponse ancienne arrivee en dernier n\'ecrase pas l\'etat plus recent', async () => {
    const { TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')
    const ancienne = deferred()
    const recente = deferred()
    TransactionsAPI.list.mockReturnValueOnce(ancienne.promise).mockReturnValueOnce(recente.promise)

    const premier = dataStore.refreshTransactions()
    const second = dataStore.refreshTransactions()
    recente.resolve([{ id: 2, amount: -5 }])
    await second
    ancienne.resolve([{ id: 1, amount: -10 }, { id: 2, amount: -5 }])
    await premier

    expect(dataStore.state.transactions).toEqual([{ id: 2, amount: -5 }])
  })

  it('l\'appelant d\'une requete depassee attend la plus recente avant de se resoudre', async () => {
    const { CategoriesAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')
    const ancienne = deferred()
    const recente = deferred()
    CategoriesAPI.list.mockReturnValueOnce(ancienne.promise).mockReturnValueOnce(recente.promise)

    const premier = dataStore.refreshCategories()
    const second = dataStore.refreshCategories()
    ancienne.resolve([{ id: 1, name: 'Ancienne' }])
    let premierResolu = false
    premier.then(() => { premierResolu = true })
    await Promise.resolve()
    await Promise.resolve()
    expect(premierResolu).toBe(false)

    recente.resolve([{ id: 1, name: 'Recente' }])
    await premier
    await second
    expect(dataStore.state.categories).toEqual([{ id: 1, name: 'Recente' }])
  })

  it('l\'echec d\'une requete depassee ne fait pas echouer son appelant si la plus recente reussit', async () => {
    const { PayeesAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')
    const ancienne = deferred()
    PayeesAPI.list.mockReturnValueOnce(ancienne.promise).mockResolvedValueOnce([{ id: 3, name: 'EDF' }])

    const premier = dataStore.refreshPayees()
    await dataStore.refreshPayees()
    ancienne.reject(new Error('reseau'))

    await expect(premier).resolves.toBeUndefined()
    expect(dataStore.state.payees).toEqual([{ id: 3, name: 'EDF' }])
  })
})

describe('dataStore.revalidate', () => {
  it('ne redemande pas des données chargées il y a moins de FRESH_FOR_MS', async () => {
    const { TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.prefetch()
    await dataStore.revalidate()

    expect(TransactionsAPI.list).toHaveBeenCalledTimes(1)
  })

  it('recharge seulement les ressources devenues anciennes', async () => {
    const { CategoriesAPI, TransactionsAPI } = await freshMocks()
    const { dataStore, FRESH_FOR_MS } = await import('./dataStore')

    await dataStore.prefetch()
    // Seules les transactions ont été rechargées depuis : elles restent fraîches.
    const later = Date.now() + FRESH_FOR_MS + 1
    vi.spyOn(Date, 'now').mockReturnValue(later)
    await dataStore.refreshTransactions()
    await dataStore.revalidate(later)
    vi.restoreAllMocks()

    expect(CategoriesAPI.list).toHaveBeenCalledTimes(2)
    expect(TransactionsAPI.list).toHaveBeenCalledTimes(2) // prefetch + refreshTransactions, pas revalidate
  })

  it('refreshAll recharge tout, même des données fraîches', async () => {
    const { TransactionsAPI } = await freshMocks()
    const { dataStore } = await import('./dataStore')

    await dataStore.prefetch()
    await dataStore.refreshAll()

    expect(TransactionsAPI.list).toHaveBeenCalledTimes(2)
  })
})

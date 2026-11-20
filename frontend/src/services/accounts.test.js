import { describe, expect, it } from 'vitest'
import { accountBalanceRows, accountDeletionBlocker, accountNameError, pendingIdsUpTo, runningBalanceById, accountPayloadFrom, accountCountLabel, accountOptionLabel, accountTypeLabel, activeAccounts, amountToneClass, computeAllocation, computeDebts, computeNetWorth, sortAccountsForDisplay } from './accounts'
import { formatAmount } from './format'

const accounts = [
  { id: 1, name: 'Livret A', type: 'SAVINGS' },
  { id: 2, name: 'Courant', type: 'CHECKING' },
  { id: 3, name: 'Visa', type: 'CREDIT_CARD' }
]

describe('accounts', () => {
  it('met les comptes courants en tête sans changer l’ordre des autres', () => {
    expect(sortAccountsForDisplay(accounts).map(a => a.id)).toEqual([2, 1, 3])
  })

  it('place les comptes archivés en fin de liste', () => {
    const withArchived = [{ id: 4, type: 'CHECKING', archived: true }, ...accounts]
    expect(sortAccountsForDisplay(withArchived).map(a => a.id)).toEqual([2, 1, 3, 4])
  })

  it('ne garde que les comptes actifs pour la saisie', () => {
    const withArchived = [...accounts, { id: 4, name: 'Ancien', type: 'CHECKING', archived: true }]
    expect(activeAccounts(withArchived).map(a => a.id)).toEqual([1, 2, 3])
  })

  it('marque un compte archivé dans son libellé d’option', () => {
    expect(accountOptionLabel({ name: 'Ancien', archived: true })).toBe('Ancien (archivé)')
    expect(accountOptionLabel({ name: 'Courant', archived: false })).toBe('Courant')
  })

  it('calcule le patrimoine net, solde absent compté à 0', () => {
    expect(computeNetWorth(accounts, { 1: 1000, 2: 250.5, 3: -50 })).toBeCloseTo(1200.5)
    expect(computeNetWorth(accounts, {})).toBe(0)
  })

  it('répartit seulement les soldes positifs, du plus gros au plus petit', () => {
    const allocation = computeAllocation(accounts, { 1: 750, 2: 250, 3: -50 })
    expect(allocation.map(a => a.id)).toEqual([1, 2])
    expect(allocation[0].share).toBeCloseTo(0.75)
    expect(allocation[1].share).toBeCloseTo(0.25)
  })

  it('liste les soldes négatifs à part, du plus endetté au moins endetté', () => {
    const withOverdraft = [...accounts, { id: 4, name: 'Découvert', type: 'CHECKING' }]
    const debts = computeDebts(withOverdraft, { 1: 750, 2: 250, 3: -50, 4: -120 })
    expect(debts.map(d => [d.id, d.balance])).toEqual([[4, -120], [3, -50]])
  })

  it('renvoie une répartition vide sans solde positif', () => {
    expect(computeAllocation(accounts, { 3: -50 })).toEqual([])
  })

  it('traduit les types connus et laisse passer les autres', () => {
    expect(accountTypeLabel('SAVINGS')).toBe('Épargne')
    expect(accountTypeLabel('AUTRE')).toBe('AUTRE')
  })
})

describe('amountToneClass', () => {
  it('classe zéro et les montants positifs en positive, le reste en negative', () => {
    expect(amountToneClass(0)).toBe('positive')
    expect(amountToneClass('12.5')).toBe('positive')
    expect(amountToneClass(-0.01)).toBe('negative')
  })
})

describe('accountBalanceRows', () => {
  it('prépare solde formaté, classe de signe et classe de type, avec un solde absent compté à zéro', () => {
    const rows = accountBalanceRows(
      [{ id: 1, name: 'Courant', type: 'CHECKING' }, { id: 2, name: 'Carte', type: 'CREDIT_CARD' }, { id: 3, name: 'Neuf', type: 'SAVINGS' }],
      { 1: '1234.5', 2: -80 }
    )
    expect(rows.map(r => [r.account.id, r.typeClass, r.balanceClass])).toEqual([
      [1, 'checking', 'positive'],
      [2, 'credit_card', 'negative'],
      [3, 'savings', 'positive']
    ])
    expect(rows[0].formattedBalance).toBe(formatAmount(1234.5))
    expect(rows[2].formattedBalance).toBe(formatAmount(0))
  })
})

describe('accountCountLabel', () => {
  it('accorde « compte » au nombre', () => {
    expect(accountCountLabel(1)).toBe('1 compte')
    expect(accountCountLabel(3)).toBe('3 comptes')
  })
})

describe('accountDeletionBlocker', () => {
  const compte = { id: 1, name: 'Livret' }
  it('autorise la suppression d’un compte sans transaction', () => {
    expect(accountDeletionBlocker(compte, [{ accountId: 2 }])).toBeNull()
  })
  it('explique le refus et propose l’archivage sinon', () => {
    expect(accountDeletionBlocker(compte, [{ accountId: 1 }, { accountId: 1 }, { accountId: 2 }]))
      .toBe('Le compte « Livret » contient 2 transactions : archivez-le plutôt que de le supprimer, pour conserver son historique.')
  })
})

describe('accountPayloadFrom', () => {
  it('ne garde que les champs saisis (ni id ni solde courant)', () => {
    expect(accountPayloadFrom({ id: 3, name: 'A', type: 'SAVINGS', initialBalance: 10, currency: 'EUR', notes: 'n', archived: true, balance: 99 }))
      .toEqual({ name: 'A', type: 'SAVINGS', initialBalance: 10, currency: 'EUR', notes: 'n', archived: true })
  })
})

describe('accountNameError', () => {
  const comptes = [{ id: 1, name: 'Livret A' }, { id: 2, name: 'Courant' }]
  it('exige un nom unique, casse ignorée', () => {
    expect(accountNameError('  ', comptes)).toBe('Saisissez le nom du compte.')
    expect(accountNameError('livret a ', comptes)).toBe('Un compte « livret a » existe déjà.')
    expect(accountNameError('Livret B', comptes)).toBe('')
  })
  it('laisse un compte garder son nom ou changer sa casse', () => {
    expect(accountNameError('LIVRET A', comptes, comptes[0])).toBe('')
    expect(accountNameError('Courant', comptes, comptes[0])).toBe('Un compte « Courant » existe déjà.')
  })
})

describe('pendingIdsUpTo', () => {
  it('ne garde que les transactions en attente du compte jusqu’à la date incluse', () => {
    const transactions = [
      { id: 1, accountId: 1, validated: false, date: '2026-11-10' },
      { id: 2, accountId: 1, validated: false, date: '2026-11-17' },
      { id: 3, accountId: 1, validated: false, date: '2026-11-18' },
      { id: 4, accountId: 1, validated: true, date: '2026-11-01' },
      { id: 5, accountId: 2, validated: false, date: '2026-11-01' }
    ]
    expect(pendingIdsUpTo(transactions, 1, '2026-11-17')).toEqual([1, 2])
  })
})

describe('runningBalanceById', () => {
  it('cumule depuis le solde initial dans l’ordre chronologique, sans erreur d’arrondi', () => {
    const transactions = [
      { id: 3, accountId: 1, date: '2026-01-02', amount: -0.1 },
      { id: 1, accountId: 1, date: '2026-01-01', amount: 0.2 },
      { id: 2, accountId: 1, date: '2026-01-02', amount: -0.1 },
      { id: 9, accountId: 2, date: '2026-01-01', amount: 500 }
    ]
    const balances = runningBalanceById(transactions, { id: 1, initialBalance: 100 })
    expect([...balances.entries()]).toEqual([[1, 100.2], [2, 100.1], [3, 100]])
  })
})

import { describe, expect, it } from 'vitest'
import { accountBalanceRows, accountCountLabel, accountOptionLabel, accountTypeLabel, activeAccounts, amountToneClass, computeAllocation, computeNetWorth, sortAccountsForDisplay } from './accounts'
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

import { describe, expect, it } from 'vitest'
import { duplicateBlockedReason, splitBlockedReason } from './transactionRules'

const depense = { id: 1, amount: -20 }
const virement = { id: 2, amount: -50, transferTransactionId: 3 }
const ajustement = { id: 4, amount: 12, balanceAdjustment: true }
// Paiement par virement ordinaire (loyer…) : un mode de paiement, pas un virement interne.
const loyer = { id: 5, amount: -800, paymentMode: 'TRANSFER' }

describe('règles des actions sur une transaction', () => {
  it('une dépense, même payée par virement, se divise et se duplique', () => {
    for (const t of [depense, loyer]) {
      expect(splitBlockedReason(t)).toBe('')
      expect(duplicateBlockedReason(t)).toBe('')
    }
  })

  it('un virement interne ne se divise pas et se crée depuis la page Comptes', () => {
    expect(splitBlockedReason(virement)).toBe('Un virement entre comptes ne peut pas être divisé')
    expect(duplicateBlockedReason(virement)).toBe('Un virement entre comptes se crée depuis la page Comptes')
  })

  it('un ajustement de solde non plus', () => {
    expect(splitBlockedReason(ajustement)).toBe('Un ajustement de solde ne peut pas être divisé')
    expect(duplicateBlockedReason(ajustement)).toBe('Un ajustement de solde se crée depuis la page Comptes')
  })
})

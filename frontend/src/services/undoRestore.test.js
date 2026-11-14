import { describe, expect, it } from 'vitest'
import { planRestore, transactionPayloadFrom } from './undoRestore'

const normale = {
  id: 1, accountId: 10, date: '2026-03-01', payee: 'Boulangerie', amount: -4.5,
  categoryId: 7, paymentMode: 'CARD', memo: 'pain',
  transferTransactionId: null, periodicExpense: false, exceptional: false, validated: true
}
const debit = { id: 2, accountId: 10, date: '2026-03-05', amount: -200, memo: 'épargne', paymentMode: 'TRANSFER', transferTransactionId: 3 }
const credit = { id: 3, accountId: 20, date: '2026-03-05', amount: 200, memo: 'épargne', paymentMode: 'TRANSFER', transferTransactionId: 2 }
const store = [normale, debit, credit]

describe('transactionPayloadFrom', () => {
  it('reprend tous les champs du TransactionDTO, sans l’id', () => {
    const payload = transactionPayloadFrom(normale)
    expect(payload).not.toHaveProperty('id')
    expect(payload).toMatchObject({ accountId: 10, payee: 'Boulangerie', amount: -4.5, categoryId: 7, validated: true })
  })

  it("conserve le marqueur d'ajustement de solde, pour le recréer tel quel", () => {
    expect(transactionPayloadFrom({ ...normale, balanceAdjustment: true }).balanceAdjustment).toBe(true)
    expect(transactionPayloadFrom(normale).balanceAdjustment).toBe(false)
    expect(transactionPayloadFrom({ ...normale, bankLabel: 'ACHAT CB ALCEL 08.11.26' }).bankLabel).toBe('ACHAT CB ALCEL 08.11.26')
    expect(transactionPayloadFrom(normale).bankLabel).toBeNull()
  })
})

describe('planRestore', () => {
  it('recrée une transaction normale via create', () => {
    const { steps, orphanTransferIds } = planRestore([normale], store)
    expect(steps).toEqual([{ kind: 'create', ids: [1], payload: transactionPayloadFrom(normale) }])
    expect(orphanTransferIds).toEqual([])
  })

  it('recrée un virement en paire, du compte débité vers le compte crédité, même si un seul côté est supprimé', () => {
    const { steps } = planRestore([credit], store)
    expect(steps).toEqual([{
      kind: 'transfer',
      ids: [3, 2],
      payload: { fromAccountId: 10, toAccountId: 20, amount: 200, date: '2026-03-05', memo: 'épargne' }
    }])
  })

  it('ne recrée qu’une seule fois un virement dont les deux côtés sont dans le lot', () => {
    const { steps } = planRestore([debit, credit, normale], store)
    expect(steps.map(s => s.kind)).toEqual(['transfer', 'create'])
    expect(steps[0].ids).toEqual([2, 3])
  })

  it('recrée en transaction simple un côté de virement dont la jumelle est introuvable, et le signale', () => {
    const { steps, orphanTransferIds } = planRestore([debit], [normale, debit])
    expect(steps).toEqual([{ kind: 'create', ids: [2], payload: transactionPayloadFrom(debit) }])
    expect(orphanTransferIds).toEqual([2])
  })

  it('recrée simplement un paiement par virement non lié (ex : loyer), sans le signaler comme orphelin', () => {
    const loyer = { id: 7, accountId: 10, date: '2026-03-01', amount: -800, paymentMode: 'TRANSFER', transferTransactionId: null }
    const { steps, orphanTransferIds } = planRestore([loyer], [loyer])
    expect(steps).toEqual([{ kind: 'create', ids: [7], payload: transactionPayloadFrom(loyer) }])
    expect(orphanTransferIds).toEqual([])
  })

  it('ignore un id présent deux fois dans le lot', () => {
    expect(planRestore([normale, normale], store).steps).toHaveLength(1)
  })
})

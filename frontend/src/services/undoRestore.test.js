import { afterEach, describe, expect, it, vi } from 'vitest'
import { previousTransactionPayload, restorationSnapshots, runOnePerPayee, transactionPayloadFrom } from './undoRestore'

describe('runOnePerPayee', () => {
  it('enchaîne les opérations d’un même tiers et laisse les autres en parallèle', async () => {
    const enCours = new Set()
    const chevauchements = []
    const demarres = []
    const run = async ({ id, payee }) => {
      demarres.push(id)
      if (enCours.has(payee.toLowerCase())) chevauchements.push(id)
      enCours.add(payee.toLowerCase())
      await new Promise(resolve => setTimeout(resolve, 1))
      enCours.delete(payee.toLowerCase())
    }
    const fin = runOnePerPayee(
      [{ id: 1, payee: 'Café' }, { id: 2, payee: 'CAFÉ' }, { id: 3, payee: 'Boulangerie' }],
      item => item.payee,
      run
    )
    expect(demarres).toEqual([1, 3])
    await fin
    expect(demarres).toEqual([1, 3, 2])
    expect(chevauchements).toEqual([])
  })

  it('continue après l’échec d’une opération du même tiers, puis le signale', async () => {
    const run = vi.fn().mockRejectedValueOnce(new Error('refus')).mockResolvedValue({})
    await expect(runOnePerPayee([{ p: 'Café' }, { p: 'Café' }], i => i.p, run)).rejects.toThrow('refus')
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(run).toHaveBeenCalledTimes(2)
  })
})

const normale = {
  id: 1, accountId: 10, date: '2026-03-01', payee: 'Boulangerie', payeeId: 4, amount: -4.5,
  categoryId: 7, paymentMode: 'CARD', memo: 'pain', bankLabel: 'CB BOULANGERIE', splitFromAmount: -9,
  transferTransactionId: null, periodicExpense: false, exceptional: false, validated: true, duplicateIgnored: true
}
const debit = { id: 2, accountId: 10, date: '2026-03-05', payee: 'Virement vers Livret', payeeId: null, amount: -200, memo: 'épargne', paymentMode: 'TRANSFER', transferTransactionId: 3 }
const credit = { id: 3, accountId: 20, date: '2026-03-05', payee: 'Virement depuis Courant', payeeId: null, amount: 200, memo: 'épargne', paymentMode: 'TRANSFER', transferTransactionId: 2 }
const store = [normale, debit, credit]

describe('transactionPayloadFrom', () => {
  it('reprend les champs saisissables, sans l’id', () => {
    const payload = transactionPayloadFrom(normale)
    expect(payload).not.toHaveProperty('id')
    expect(payload).toMatchObject({ accountId: 10, payee: 'Boulangerie', amount: -4.5, categoryId: 7, validated: true })
  })
})

describe('previousTransactionPayload', () => {
  it('garde le nom d’un vrai tiers', () => {
    expect(previousTransactionPayload(normale).payee).toBe('Boulangerie')
  })

  it('ne prend pas le libellé calculé d’un virement pour un tiers', () => {
    expect(previousTransactionPayload(debit).payee).toBeNull()
  })
})

describe('restorationSnapshots', () => {
  afterEach(() => vi.restoreAllMocks())

  it('conserve tous les champs, y compris ceux qui ne se saisissent pas', () => {
    expect(restorationSnapshots([normale], store)).toEqual([normale])
  })

  it('prend des copies : une modification ultérieure du cache ne les change pas', () => {
    const cache = { ...normale }
    const [copie] = restorationSnapshots([cache], [cache])
    cache.memo = 'modifié'
    expect(copie.memo).toBe('pain')
  })

  it('ajoute la jumelle d’un virement, même si un seul côté est supprimé', () => {
    expect(restorationSnapshots([credit], store).map(t => t.id)).toEqual([3, 2])
  })

  it('ne reprend qu’une fois un virement dont les deux côtés sont dans le lot', () => {
    expect(restorationSnapshots([debit, credit, normale, normale], store).map(t => t.id)).toEqual([2, 3, 1])
  })

  it('signale une jumelle introuvable et garde le côté connu', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(restorationSnapshots([debit], [debit]).map(t => t.id)).toEqual([2])
    expect(warn).toHaveBeenCalledOnce()
  })

  it('ne cherche pas de jumelle pour un paiement par virement non lié (ex : loyer)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const loyer = { id: 7, accountId: 10, amount: -800, paymentMode: 'TRANSFER', transferTransactionId: null }
    expect(restorationSnapshots([loyer], [loyer])).toEqual([loyer])
    expect(warn).not.toHaveBeenCalled()
  })
})

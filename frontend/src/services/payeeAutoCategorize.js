import { ref } from 'vue'
import { PayeesAPI, TransactionsAPI } from './api'
import { alertApiError } from './errors'
import { dataStore } from './dataStore'

/**
 * Catégorisation automatique en masse (voir ServiceCategorisationAuto#autoCategorize
 * côté backend) : catégorise en une fois tous les tiers éligibles, et propage
 * la catégorie ainsi déterminée aux transactions de ces tiers encore sans
 * catégorie. Annulable.
 *
 * - `payees` : tiers de la page (ref), photographiés avant l'opération ;
 * - `refreshAfterMutation` : revalidation des tiers de la page ;
 * - `setLastAction` : enregistrement de l'annulation (voir lastAction.js).
 */
export function usePayeeAutoCategorize({ payees, refreshAfterMutation, setLastAction }) {
  const autoCategorizing = ref(false)
  const autoCategorizeResult = ref(null)

  /**
   * Annule une catégorisation automatique : tiers et transactions concernés
   * étaient tous sans catégorie avant, ils le redeviennent.
   */
  async function undoAutoCategorize({ payeeIds = [], transactionIds = [] }, payeesBefore) {
    const payeeUpdates = payeeIds
      .map(id => payeesBefore.get(id))
      .filter(Boolean)
      .map(p => PayeesAPI.update(p.id, { name: p.name, pattern: p.pattern, amount: p.amount, categoryId: null }))
    const transactionUpdate = transactionIds.length
      ? TransactionsAPI.bulkUpdate({ ids: transactionIds, categoryIdProvided: true, categoryId: null })
      : null
    await Promise.all([...payeeUpdates, transactionUpdate])
    autoCategorizeResult.value = null
    await Promise.all([refreshAfterMutation(), dataStore.refreshTransactions()])
  }

  /** Revalide aussi les transactions, pas seulement les tiers : des transactions peuvent avoir changé. */
  async function runAutoCategorize() {
    autoCategorizing.value = true
    autoCategorizeResult.value = null
    // Tiers tels qu'avant l'opération, pour pouvoir leur retirer la catégorie.
    const payeesBefore = new Map(payees.value.map(p => [p.id, p]))
    try {
      const result = await PayeesAPI.autoCategorize()
      autoCategorizeResult.value = result
      await Promise.all([dataStore.refreshPayees(), dataStore.refreshTransactions()])
      if (result.payeeIds?.length || result.transactionIds?.length) {
        setLastAction('Catégorisation automatique', () => undoAutoCategorize(result, payeesBefore))
      }
    } catch (e) {
      alertApiError('Erreur lors de la catégorisation automatique', e)
    } finally {
      autoCategorizing.value = false
    }
  }

  return { autoCategorizing, autoCategorizeResult, runAutoCategorize }
}

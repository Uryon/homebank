import { computed, ref } from 'vue'
import { TransactionsAPI } from './api'
import { notify } from './feedback'
import { apiErrorMessage } from './errors'
import { previousTransactionPayload } from './undoRestore'
import { splitBlockedReason } from './transactionRules'

/**
 * Division d'une transaction en deux (saisie : TransactionSplitModal.vue),
 * annulable : « Annuler » supprime la seconde partie et rend à la première
 * son état d'origine.
 *
 * - `accountsById` : Map id → compte (ref), pour la devise des montants ;
 * - `load` : rechargement des données après la division ;
 * - `setLastAction` : enregistrement de l'annulation (voir lastAction.js).
 */
export function useTransactionSplit({ accountsById, load, setLastAction }) {
  /** Transaction en cours de division, null modale fermée. */
  const splitTransaction = ref(null)
  const splitSaving = ref(false)
  /** Erreur renvoyée par le serveur, affichée dans la modale. */
  const splitError = ref('')

  /** Devise du compte de la transaction divisée, pour afficher ses montants dans la bonne monnaie. */
  const splitCurrency = computed(() => accountsById.value.get(splitTransaction.value?.accountId)?.currency)

  function openSplit(transaction) {
    const blocked = splitBlockedReason(transaction)
    if (blocked) {
      notify(`${blocked}.`, 'warning')
      return
    }
    splitError.value = ''
    splitTransaction.value = transaction
  }

  function closeSplitModal() {
    splitTransaction.value = null
  }

  async function saveSplit(parts) {
    splitError.value = ''
    splitSaving.value = true
    // Transaction d'origine telle qu'avant la division, pour « Annuler ».
    const original = splitTransaction.value
    const originalState = { ...previousTransactionPayload(original), splitFromAmount: original.splitFromAmount ?? null }
    try {
      const [, second] = await TransactionsAPI.split(original.id, parts)
      closeSplitModal()
      await load()
      setLastAction('Transaction divisée', async () => {
        await TransactionsAPI.undoSplit(original.id, { secondPartId: second.id, original: originalState })
        await load()
      }, { retryable: true })
    } catch (e) {
      splitError.value = apiErrorMessage(e)
    } finally {
      splitSaving.value = false
    }
  }

  return { splitTransaction, splitSaving, splitError, splitCurrency, openSplit, closeSplitModal, saveSplit }
}

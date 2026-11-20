import { computed, ref } from 'vue'
import { PayeesAPI } from './api'
import { notify } from './feedback'
import { alertApiError } from './errors'
import { dataStore } from './dataStore'
import { pluralize } from './format'

/**
 * Nettoyage des faux tiers créés à partir de libellés automatiques de
 * virement ou d'ajustement (voir ServiceNettoyageTiers), annulable.
 *
 * - `refreshAfterMutation` : revalidation des tiers de la page ;
 * - `setLastAction` : enregistrement de l'annulation (voir lastAction.js).
 */
export function usePayeeCleanup({ refreshAfterMutation, setLastAction }) {
  const cleanupItems = ref([])
  const showCleanupModal = ref(false)
  const cleaning = ref(false)

  const cleanupNotice = computed(() => {
    const count = cleanupItems.value.length
    return count === 1
      ? '1 tiers a été créé à partir d’un libellé automatique de virement ou d’ajustement.'
      : `${count} tiers ont été créés à partir de libellés automatiques de virement ou d’ajustement.`
  })

  /** Contrôle secondaire : s'il échoue, la page reste utilisable et le bandeau n'apparaît pas. */
  async function loadCleanupItems() {
    try {
      cleanupItems.value = await PayeesAPI.previewCleanup()
    } catch {
      cleanupItems.value = []
    }
  }

  async function refreshAfterCleanup() {
    await Promise.all([refreshAfterMutation(), dataStore.refreshTransactions(), loadCleanupItems()])
  }

  async function confirmCleanup(payeeIds) {
    cleaning.value = true
    try {
      const result = await PayeesAPI.cleanup(payeeIds)
      showCleanupModal.value = false
      await refreshAfterCleanup()
      notify(`${pluralize(result.detachedTransactions, 'transaction corrigée', 'transactions corrigées')}, ${pluralize(result.deletedPayees, 'tiers supprimé', 'tiers supprimés')}.`, 'success')
      setLastAction('Faux tiers nettoyés', async () => {
        await PayeesAPI.undoCleanup(result.payees)
        await refreshAfterCleanup()
      }, { retryable: true })
    } catch (e) {
      alertApiError('Erreur lors du nettoyage des tiers', e)
    } finally {
      cleaning.value = false
    }
  }

  return { cleanupItems, showCleanupModal, cleaning, cleanupNotice, loadCleanupItems, confirmCleanup }
}

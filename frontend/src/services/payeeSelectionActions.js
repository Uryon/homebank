import { computed, ref } from 'vue'
import { PayeesAPI, TransactionsAPI } from './api'
import { askConfirm, notify } from './feedback'
import { alertApiError } from './errors'
import { dataStore } from './dataStore'
import { pluralize } from './format'
import { planPayeeMergeRestore, restorePayeeMerge } from './payeeMergeRestore'

/**
 * Actions sur les tiers cochés de la page Tiers, annulables : fusion (saisie :
 * PayeeMergeModal.vue) et suppression groupée des tiers sans transaction.
 *
 * - `payees` : tiers de la page (ref) ;
 * - `selection` : { selectedIds, selectedIdSet, clearSelection } (voir selection.js) ;
 * - `isUnused(payee)` : vrai si le tiers n'a aucune transaction ;
 * - `refreshAfterMutation`, `setLastAction` : revalidation et annulation.
 */
export function usePayeeSelectionActions({ payees, selection, isUnused, refreshAfterMutation, setLastAction }) {
  const { selectedIds, selectedIdSet, clearSelection } = selection

  const selectedPayeeObjects = computed(() => payees.value.filter(p => selectedIdSet.value.has(p.id)))

  /** Suppression groupée proposée seulement si aucun tiers sélectionné n'a de transaction (sinon : fusion). */
  const selectionIsUnused = computed(() => selectedPayeeObjects.value.every(isUnused))
  const removingSelection = ref(false)

  /** Supprime les tiers sélectionnés (tous inutilisés) ; « Annuler » les recrée à l'identique. */
  async function removeSelected() {
    const toRemove = selectedPayeeObjects.value
    if (!toRemove.length) return
    const question = toRemove.length === 1
      ? `Supprimer le tiers « ${toRemove[0].name} » ? Il n'a aucune transaction.`
      : `Supprimer les ${toRemove.length} tiers sélectionnés ? Ils n'ont aucune transaction.`
    if (!(await askConfirm(question))) return
    removingSelection.value = true
    const results = await Promise.allSettled(toRemove.map(p => PayeesAPI.remove(p.id)))
    removingSelection.value = false
    const removed = toRemove.filter((_, i) => results[i].status === 'fulfilled')
    const failure = results.find(r => r.status === 'rejected')
    if (failure) alertApiError("Certains tiers n'ont pas pu être supprimés", failure.reason)
    await refreshAfterMutation()
    if (!removed.length) return
    notify(`${pluralize(removed.length, 'tiers supprimé', 'tiers supprimés')}.`, 'success')
    setLastAction(removed.length === 1 ? 'Tiers supprimé' : `${removed.length} tiers supprimés`, async () => {
      await Promise.all(removed.map(({ name, pattern, amount, categoryId }) => PayeesAPI.create({ name, pattern, amount, categoryId })))
      await refreshAfterMutation()
    })
  }

  const showMergeModal = ref(false)
  const merging = ref(false)

  function openMergeModal() {
    showMergeModal.value = true
  }

  /** Fusion choisie dans PayeeMergeModal.vue : tiers conservé et motif final. */
  async function confirmMerge({ targetId, pattern }) {
    merging.value = true
    const sourceIds = selectedIds.value.filter(id => id !== targetId)
    // Établi avant la fusion : les tiers source n'existent plus après.
    const restorePlan = planPayeeMergeRestore(dataStore.state, sourceIds, targetId)
    try {
      await PayeesAPI.merge(sourceIds, targetId, pattern)
      showMergeModal.value = false
      clearSelection()
      // La fusion reaffecte aussi le tiers de chaque transaction des tiers
      // source (voir ServiceTiers#merge cote backend) : revalider aussi le
      // cache des transactions, pas seulement celui des tiers.
      await Promise.all([dataStore.refreshPayees(), dataStore.refreshTransactions()])
      notify(`${pluralize(sourceIds.length, 'tiers fusionné', 'tiers fusionnés')} dans « ${restorePlan.target?.name ?? ''} ».`, 'success')
      setLastAction('Fusion de tiers', async () => {
        await restorePayeeMerge(restorePlan, { payees: PayeesAPI, transactions: TransactionsAPI })
        await Promise.all([refreshAfterMutation(), dataStore.refreshTransactions()])
      })
    } catch (e) {
      alertApiError('Erreur lors de la fusion', e)
    } finally {
      merging.value = false
    }
  }

  return {
    selectedPayeeObjects, selectionIsUnused, removingSelection, removeSelected,
    showMergeModal, merging, openMergeModal, confirmMerge
  }
}

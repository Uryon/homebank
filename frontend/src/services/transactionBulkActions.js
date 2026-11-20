import { computed, ref } from 'vue'
import { TransactionsAPI } from './api'
import { askConfirm, notify } from './feedback'
import { alertApiError } from './errors'
import { dataStore } from './dataStore'
import { pluralize } from './format'
import { bulkTouchedFields, bulkUpdatePayload } from './bulkEdit'
import { previousTransactionPayload, runOnePerPayee } from './undoRestore'

/**
 * Actions de masse de la page Transactions, toutes annulables : validation
 * et suppression des transactions en attente de la liste courante,
 * modification et suppression des transactions cochées.
 *
 * - `transactions` : liste courante (ref), celle du compte filtré ou de tous ;
 * - `filterAccount` / `accountsById` : compte filtré (ref) et Map id → compte (ref) ;
 * - `selection` : { selectedIds, clearSelection, selectedRows } (voir rowSelection.js) ;
 * - `load`, `setLastAction`, `offerRestore`, `snapshotsBeforeDeletion` :
 *   rechargement et annulation, partagés avec le reste de la page.
 */
export function useTransactionBulkActions({
  transactions, filterAccount, accountsById, selection, load, setLastAction, offerRestore, snapshotsBeforeDeletion
}) {
  const { selectedIds, clearSelection, selectedRows } = selection

  /**
   * Transactions en attente de la liste courante : celles du compte filtré, ou
   * de tous les comptes. « Tout valider » et « Supprimer les non validées »
   * portent sur ce même périmètre, pour que le nombre annoncé soit le bon.
   */
  const pendingCount = computed(() =>
    transactions.value.filter(t => !t.validated).length
  )

  /** Précision ajoutée aux confirmations quand la liste est filtrée sur un compte. */
  const pendingScopeLabel = computed(() => {
    const account = filterAccount.value !== null ? accountsById.value.get(filterAccount.value) : null
    return account ? ` du compte « ${account.name} »` : ''
  })

  /* --- Édition en masse des transactions cochées (saisie : TransactionBulkEditModal.vue) --- */

  const showBulkEditModal = ref(false)
  const bulkSaving = ref(false)

  function openBulkEdit() {
    showBulkEditModal.value = true
  }

  async function saveBulkEdit(bulkForm) {
    if (!selectedIds.value.size) return

    // Champs effectivement touches par cette modification en masse : reutilises
    // pour ne restaurer que ceux-la lors de l'annulation (voir plus bas), via
    // bulkUpdate. TransactionsAPI.update() reenverrait TOUS les champs (compte,
    // date, montant...) et, sur un cote de virement, les repercuterait sur sa
    // jumelle (voir ServiceTransaction#syncTransferTwin) : l'annulation doit
    // defaire la modification en masse, rien de plus.
    const touchedFields = bulkTouchedFields(bulkForm)
    const payload = bulkUpdatePayload(Array.from(selectedIds.value), touchedFields, bulkForm)

    const previousSnapshots = selectedRows()
      .map(t => ({ id: t.id, before: previousTransactionPayload(t) }))

    bulkSaving.value = true
    try {
      await TransactionsAPI.bulkUpdate(payload)
      showBulkEditModal.value = false
      clearSelection()
      await load()
      setLastAction(`Modification de ${pluralize(previousSnapshots.length, 'transaction', 'transactions')}`, async () => {
        await runOnePerPayee(
          previousSnapshots,
          ({ before }) => (touchedFields.payee ? before.payee : null),
          ({ id, before }) => TransactionsAPI.bulkUpdate(bulkUpdatePayload([id], touchedFields, before))
        )
        await load()
      })
    } catch (e) {
      alertApiError('Erreur lors de la modification en masse', e)
    } finally {
      bulkSaving.value = false
    }
  }

  /* --- Validation / rapprochement (façon "reconcile" YNAB) --- */

  const validating = ref(false)

  /**
   * Valide en une fois toutes les transactions en attente : à utiliser une fois
   * que le solde du compte dans l'app correspond au solde réel de la banque,
   * comme le "reconcile" de YNAB. Permet ensuite de repérer d'un coup d'œil
   * les transactions ajoutées depuis la dernière validation (pastille orange).
   */
  async function validateAll() {
    if (pendingCount.value === 0) return
    if (!(await askConfirm(`Valider ${pendingCount.value === 1 ? 'la transaction' : `les ${pendingCount.value} transactions`} en attente${pendingScopeLabel.value} ?`))) return
    validating.value = true
    // Même périmètre que pendingCount (compte filtré ou tous les comptes, voir
    // ServiceTransaction#validateAllPending). L'annulation remet ces ids en
    // attente ; bulkUpdate y joint la jumelle d'un virement sur un autre compte.
    const pendingIds = transactions.value.filter(t => !t.validated).map(t => t.id)
    try {
      await TransactionsAPI.validateAll(filterAccount.value ?? undefined)
      await load()
      notify(`${pluralize(pendingIds.length, 'transaction validée', 'transactions validées')}.`, 'success')
      setLastAction(`Validation de ${pluralize(pendingIds.length, 'transaction', 'transactions')}`, async () => {
        // Remise explicite « en attente » (et non une bascule par transaction) :
        // basculer un côté de virement bascule aussi sa jumelle, si bien que
        // deux bascules successives (une par côté, tous deux en attente)
        // laissaient le virement validé après l'annulation.
        await TransactionsAPI.bulkUpdate({ ids: pendingIds, validatedProvided: true, validated: false })
        await load()
      })
    } catch (e) {
      alertApiError('Erreur lors de la validation des transactions', e)
    } finally {
      validating.value = false
    }
  }

  const deletingPending = ref(false)

  /** Supprime d'un coup toutes les transactions en attente de validation, sans passer par la selection manuelle. */
  async function deletePending() {
    if (pendingCount.value === 0) return
    if (!(await askConfirm(`Supprimer ${pendingCount.value === 1 ? 'la transaction non validée' : `les ${pendingCount.value} transactions non validées`}${pendingScopeLabel.value} ?`))) return
    deletingPending.value = true
    // Même périmètre que pendingCount et validateAll ci-dessus (voir
    // ServiceSuppressionTransaction#deleteAllPending) ; snapshotsBeforeDeletion retrouve la
    // jumelle d'un virement, supprimée avec lui, dans la liste globale.
    const pendingTransactions = transactions.value.filter(t => !t.validated)
    const candidateSnapshots = snapshotsBeforeDeletion(pendingTransactions)
    try {
      await TransactionsAPI.removePending(filterAccount.value ?? undefined)
      await load()
      notify('Transactions en attente supprimées.', 'success')
      // Un cote de virement en attente dont la jumelle est deja validee est
      // ignore par le backend (voir ServiceSuppressionTransaction#deleteAllPending) : il
      // est donc toujours present apres le rechargement, et ne doit pas etre
      // "restaure" (il n'a jamais ete supprime, ca creerait un doublon). Lu
      // dans `transactions`, rechargee par load() que le compte soit filtre
      // ou non.
      const stillPresentIds = new Set(transactions.value.map(t => t.id))
      const snapshots = candidateSnapshots.filter(t => !stillPresentIds.has(t.id))
      const actuallyDeletedCount = pendingTransactions.filter(t => !stillPresentIds.has(t.id)).length
      offerRestore(`Suppression de ${pluralize(actuallyDeletedCount, 'transaction', 'transactions')}`, snapshots)
    } catch (e) {
      alertApiError('Erreur lors de la suppression des transactions en attente', e)
    } finally {
      deletingPending.value = false
    }
  }

  const deletingSelection = ref(false)

  /** Supprime d'un coup toutes les transactions actuellement cochees (voir selectedIds). */
  async function deleteSelected() {
    if (!selectedIds.value.size) return
    if (!(await askConfirm(`Supprimer ${selectedIds.value.size === 1 ? 'la transaction sélectionnée' : `les ${selectedIds.value.size} transactions sélectionnées`} ?`))) return
    deletingSelection.value = true
    const ids = Array.from(selectedIds.value)
    const selectedTransactions = selectedRows()
    const snapshots = snapshotsBeforeDeletion(selectedTransactions)
    try {
      await TransactionsAPI.removeBulk(ids)
      notify(`${pluralize(selectedTransactions.length, 'transaction supprimée', 'transactions supprimées')}.`, 'success')
      clearSelection()
      dataStore.hideDeletedTransactions(ids)
      await load()
      offerRestore(`Suppression de ${pluralize(selectedTransactions.length, 'transaction', 'transactions')}`, snapshots)
    } catch (e) {
      alertApiError('Erreur lors de la suppression des transactions sélectionnées', e)
    } finally {
      deletingSelection.value = false
    }
  }

  return {
    pendingCount,
    showBulkEditModal, bulkSaving, openBulkEdit, saveBulkEdit,
    validating, validateAll,
    deletingPending, deletePending,
    deletingSelection, deleteSelected
  }
}

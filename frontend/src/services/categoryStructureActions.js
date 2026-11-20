import { computed, ref } from 'vue'
import { CategoriesAPI, PayeesAPI, TransactionsAPI } from './api'
import { notify } from './feedback'
import { alertApiError } from './errors'
import { dataStore } from './dataStore'
import { pluralize } from './format'
import { isEmptyRestorePlan, planCategoryStateRestore, restoreCategoryState, snapshotCategoryState } from './categoryRestore'
import { mergeableTargets as mergeableTargetsAmong } from './categoryFilters'

/**
 * Opérations qui réorganisent l'arbre des catégories, toutes annulables en
 * revenant à une photographie prise juste avant (voir categoryRestore.js) :
 * fusion des catégories cochées, changement groupé de catégorie parente,
 * glisser-déposer d'une sous-catégorie vers une autre racine.
 *
 * - `roots` : catégories racines (ref) ; `categoryById` : Map id -> catégorie (ref) ;
 * - `children(rootId)` : sous-catégories d'une racine ;
 * - `selection` : { selectedIds, selectedIdSet, clearSelection } (voir selection.js) ;
 * - `load(...also)` : rechargement des catégories (et des ressources `also`) ;
 * - `setLastAction` : enregistrement de l'annulation (voir lastAction.js).
 */
export function useCategoryStructureActions({ roots, categoryById, children, selection, load, setLastAction }) {
  const { selectedIds, selectedIdSet, clearSelection } = selection

  const selectedCategoryObjects = computed(() => {
    const ids = selectedIdSet.value
    return [...categoryById.value.values()].filter(c => ids.has(c.id))
  })

  /**
   * Propose d'annuler une opération de masse en revenant à la photographie
   * `before`. À appeler une fois le cache rechargé.
   */
  function offerStateRestore(label, before) {
    const plan = planCategoryStateRestore(before, dataStore.state)
    if (isEmptyRestorePlan(plan)) return
    setLastAction(label, async () => {
      await restoreCategoryState(plan, { categories: CategoriesAPI, transactions: TransactionsAPI, payees: PayeesAPI })
      await load(dataStore.refreshTransactions, dataStore.refreshPayees)
    })
  }

  /* --- Fusion --- */

  const showMergeModal = ref(false)
  const mergeTargetId = ref(null)
  const merging = ref(false)

  const mergeableTargets = computed(() =>
    mergeableTargetsAmong(selectedCategoryObjects.value, selectedIdSet.value, categoryById.value, children))

  function openMergeModal() {
    mergeTargetId.value = mergeableTargets.value[0]?.id ?? null
    showMergeModal.value = true
  }

  async function confirmMerge() {
    if (!mergeTargetId.value) return
    merging.value = true
    const before = snapshotCategoryState(dataStore.state)
    try {
      const sourceIds = selectedIds.value.filter(id => id !== mergeTargetId.value)
      await CategoriesAPI.merge(sourceIds, mergeTargetId.value)
      showMergeModal.value = false
      clearSelection()
      // Transactions et tiers des catégories sources passent sur la cible
      // (voir ServiceCategorie#merge).
      await load(dataStore.refreshTransactions, dataStore.refreshPayees)
      const targetName = categoryById.value.get(mergeTargetId.value)?.name ?? ''
      notify(`${pluralize(sourceIds.length, 'catégorie fusionnée', 'catégories fusionnées')} dans « ${targetName} ».`, 'success')
      offerStateRestore('Fusion de catégories', before)
    } catch (e) {
      alertApiError('Erreur lors de la fusion', e)
    } finally {
      merging.value = false
    }
  }

  /* --- Changement groupé de catégorie parente ---
   * Comme pour l'édition inline d'une sous-catégorie (seule ligne qui expose
   * déjà un sélecteur de parent), seules les sous-catégories sélectionnées
   * sont concernées : une catégorie racine n'a pas de parent à changer ici
   * (elle a ses propres sous-catégories, la reparenter les laisserait
   * orphelines de niveau). Les racines éventuellement cochées en même temps
   * (ex. via "sélectionner tout") sont simplement ignorées par cette action.
   */

  const showReparentModal = ref(false)
  const reparentTargetId = ref(null)
  const reparenting = ref(false)

  const reparentableIds = computed(() =>
    selectedCategoryObjects.value.filter(c => c.parentId != null).map(c => c.id)
  )

  function openReparentModal() {
    reparentTargetId.value = roots.value[0]?.id ?? null
    showReparentModal.value = true
  }

  async function confirmReparent() {
    if (!reparentTargetId.value) return
    reparenting.value = true
    const before = snapshotCategoryState(dataStore.state)
    try {
      await Promise.all(reparentableIds.value.map(id => {
        const category = categoryById.value.get(id)
        return CategoriesAPI.update(id, { name: category.name, type: category.type, parentId: reparentTargetId.value, icon: category.icon })
      }))
      showReparentModal.value = false
      clearSelection()
      await load()
      offerStateRestore('Catégorie parente changée', before)
    } catch (e) {
      alertApiError('Erreur lors du changement de catégorie parente', e)
    } finally {
      reparenting.value = false
    }
  }

  /* --- Glisser-déposer une sous-catégorie vers une autre catégorie racine ---
   * Seules les sous-catégories sont glissables (même limitation que le
   * changement groupé ci-dessus). Les racines sont uniquement des cibles de dépôt.
   */

  /** Sous-catégorie en cours de glissement, et racine survolée (surlignage, voir .cat-row-dropzone). */
  const draggedCategoryId = ref(null)
  const dragOverRootId = ref(null)

  function onDragStart(event, child) {
    draggedCategoryId.value = child.id
    event.dataTransfer.effectAllowed = 'move'
    // Certains navigateurs exigent des données pour autoriser le drop ; la
    // valeur elle-meme n'est pas relue, draggedCategoryId suffit cote appli.
    event.dataTransfer.setData('text/plain', String(child.id))
  }

  function onDragEnd() {
    draggedCategoryId.value = null
    dragOverRootId.value = null
  }

  function onRootDragOver(event, root) {
    if (draggedCategoryId.value == null) return
    event.preventDefault() // necessaire pour autoriser le drop (comportement par defaut = refus)
    event.dataTransfer.dropEffect = 'move'
    dragOverRootId.value = root.id
  }

  function onRootDragLeave(root) {
    if (dragOverRootId.value === root.id) dragOverRootId.value = null
  }

  async function onRootDrop(event, root) {
    event.preventDefault()
    const id = draggedCategoryId.value
    dragOverRootId.value = null
    draggedCategoryId.value = null
    if (id == null) return

    const category = categoryById.value.get(id)
    if (!category || category.parentId === root.id) return // pas de sous-categorie, ou deja sous cette racine

    const before = snapshotCategoryState(dataStore.state)
    try {
      await CategoriesAPI.update(id, { name: category.name, type: category.type, parentId: root.id, icon: category.icon })
      await load()
      offerStateRestore('Sous-catégorie déplacée', before)
    } catch (e) {
      alertApiError('Erreur lors du déplacement', e)
    }
  }

  return {
    selectedCategoryObjects, offerStateRestore,
    showMergeModal, mergeTargetId, merging, mergeableTargets, openMergeModal, confirmMerge,
    showReparentModal, reparentTargetId, reparenting, reparentableIds, openReparentModal, confirmReparent,
    draggedCategoryId, dragOverRootId, onDragStart, onDragEnd, onRootDragOver, onRootDragLeave, onRootDrop
  }
}

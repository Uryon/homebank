import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { idsBetween } from './selection'

/**
 * Sélection multiple de lignes de la page Transactions (cases à cocher),
 * pour l'édition et la suppression en masse.
 *
 * - `visibleRows` : lignes affichées (ref), pour la plage Maj+clic et la case
 *   « tout cocher » ;
 * - `allRows` : toutes les lignes connues (ref ou getter), pour le total et
 *   l'instantané : la sélection persiste à travers les filtres, et une action
 *   de masse porte sur TOUS les ids cochés, même hors du filtre affiché.
 *
 * `selectedIds` est un Set réactif : `has(id)` en O(1) par ligne affichée,
 * sans scan à chaque rendu, même sur un grand historique.
 */
export function createRowSelection(visibleRows, allRows) {
  const rows = () => (typeof allRows === 'function' ? allRows() : allRows.value)
  const selectedIds = ref(new Set())
  /** Dernière ligne cochée : départ d'une plage, et ligne retrouvée quand la recherche se ferme. */
  const lastSelectedId = ref(null)

  /**
   * Coche / décoche une ligne. Avec `range` (Maj+clic, Maj+Espace), coche
   * toutes les lignes affichées entre la dernière cochée et celle-ci ; sans
   * dernière cochée encore affichée, sélection simple.
   */
  function toggleSelect(row, { range = false } = {}) {
    const rangeIds = range && lastSelectedId.value != null
      ? idsBetween(visibleRows.value, lastSelectedId.value, row.id)
      : null
    if (rangeIds) {
      for (const id of rangeIds) selectedIds.value.add(id)
      lastSelectedId.value = row.id
      return
    }
    if (selectedIds.value.has(row.id)) {
      selectedIds.value.delete(row.id)
      if (lastSelectedId.value === row.id) lastSelectedId.value = null
    } else {
      selectedIds.value.add(row.id)
      lastSelectedId.value = row.id
    }
  }

  function clearSelection() {
    selectedIds.value.clear()
    lastSelectedId.value = null
  }

  const allVisibleSelected = computed(() =>
    visibleRows.value.length > 0 && visibleRows.value.every(row => selectedIds.value.has(row.id))
  )

  /** Case d'en-tête : coche ou décoche les lignes affichées, sans toucher aux autres. */
  function toggleSelectAllVisible() {
    const select = !allVisibleSelected.value
    for (const row of visibleRows.value) {
      if (select) selectedIds.value.add(row.id)
      else selectedIds.value.delete(row.id)
    }
  }

  /** Montant par id sur toutes les lignes, recalculé seulement quand la liste change. */
  const amountById = computed(() => new Map(rows().map(row => [row.id, Number(row.amount || 0)])))

  /** Somme des lignes cochées (barre de sélection). */
  const selectedTotal = computed(() => {
    const amounts = amountById.value
    let sum = 0
    for (const id of selectedIds.value) sum += amounts.get(id) || 0
    return sum
  })

  /** Lignes cochées, lues dans toutes les lignes (hors filtre compris), pour l'action de masse et son annulation. */
  function selectedRows() {
    const byId = new Map(rows().map(row => [row.id, row]))
    return Array.from(selectedIds.value, id => byId.get(id)).filter(Boolean)
  }

  return { selectedIds, lastSelectedId, toggleSelect, clearSelection, allVisibleSelected, toggleSelectAllVisible, selectedTotal, selectedRows }
}

/**
 * Échap vide la sélection, sauf s'il sert déjà ailleurs : saisie en cours,
 * fenêtre ouverte, ou `escapeBlocked()` (ex : ligne en édition, qu'Échap annule).
 */
export function shouldClearOnEscape(event, hasSelection, escapeBlocked, doc = globalThis.document) {
  if (event.key !== 'Escape' || !hasSelection || escapeBlocked()) return false
  const target = event.target
  const typing = !!target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) && target.type !== 'checkbox'
  return !typing && !doc?.querySelector('.modal-overlay, .feedback-overlay, .palette-overlay')
}

/** createRowSelection, plus la touche Échap pendant la vie de la page. */
export function useRowSelection(visibleRows, allRows, { escapeBlocked = () => false } = {}) {
  const selection = createRowSelection(visibleRows, allRows)
  function onKeydown(event) {
    if (shouldClearOnEscape(event, selection.selectedIds.value.size > 0, escapeBlocked)) selection.clearSelection()
  }
  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
  return selection
}

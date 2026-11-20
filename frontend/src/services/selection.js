import { computed, ref } from 'vue'

/**
 * Sélection d'une plage de lignes (Maj+clic, Maj+Espace), comme dans un
 * explorateur de fichiers : ids des lignes entre `fromId` (dernière ligne
 * cochée) et `toId` (ligne visée), bornes comprises, dans l'ordre affiché
 * de `items`. null si l'une des deux n'est pas affichée (filtre changé
 * depuis) : l'appelant se rabat alors sur une sélection simple.
 */
export function idsBetween(items, fromId, toId) {
  const from = items.findIndex(item => item.id === fromId)
  const to = items.findIndex(item => item.id === toId)
  if (from === -1 || to === -1) return null
  const [start, end] = from <= to ? [from, to] : [to, from]
  return items.slice(start, end + 1).map(item => item.id)
}

/**
 * Sélection par cases à cocher d'une liste d'éléments identifiés par leur id
 * (pages Tiers et Catégories). selectedIds reste un tableau, lié tel quel aux
 * cases (v-model) ; selectedIdSet sert aux tests d'appartenance, en O(1)
 * même après « tout sélectionner » sur des milliers de lignes.
 */
export function useIdSelection() {
  const selectedIds = ref([])
  const selectedIdSet = computed(() => new Set(selectedIds.value))

  /** Vrai si tous les ids donnés (lignes affichées) sont sélectionnés. */
  function allSelectedIn(ids) {
    return ids.length > 0 && ids.every(id => selectedIdSet.value.has(id))
  }

  /**
   * Case d'en-tête : sélectionne, ou désélectionne, toutes les lignes
   * affichées, sans toucher à la sélection faite hors de ces lignes (autre
   * colonne, filtre précédent).
   */
  function toggleAllIn(ids) {
    const idSet = new Set(ids)
    selectedIds.value = allSelectedIn(ids)
      ? selectedIds.value.filter(id => !idSet.has(id))
      : [...new Set([...selectedIds.value, ...idSet])]
  }

  /** Après un rechargement : retire de la sélection les éléments qui n'existent plus. */
  function keepExisting(existingIds) {
    const existing = new Set(existingIds)
    selectedIds.value = selectedIds.value.filter(id => existing.has(id))
  }

  function clearSelection() {
    selectedIds.value = []
  }

  return { selectedIds, selectedIdSet, allSelectedIn, toggleAllIn, keepExisting, clearSelection }
}

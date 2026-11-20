/**
 * En-têtes de colonnes triables, partagés par les listes Tiers et
 * Transactions : sort = { key, dir: 'asc' | 'desc' }.
 */

/**
 * Nouveau tri après un clic sur l'en-tête `key` : même colonne → sens inversé ;
 * autre colonne → son sens par défaut (`defaultDirections[key]`, sinon 'asc').
 */
export function nextSort(current, key, defaultDirections = {}) {
  if (current?.key === key) return { key, dir: current.dir === 'asc' ? 'desc' : 'asc' }
  return { key, dir: defaultDirections[key] || 'asc' }
}

/** Valeur de l'attribut aria-sort de l'en-tête `key`. */
export function ariaSortOf(sort, key) {
  if (sort?.key !== key) return 'none'
  return sort.dir === 'asc' ? 'ascending' : 'descending'
}

/** Flèche du sens actif sur la colonne triée, double flèche « triable » sur les autres. */
export function sortIconOf(sort, key) {
  if (sort?.key !== key) return 'sortable'
  return sort.dir === 'asc' ? 'arrowUp' : 'arrowDown'
}

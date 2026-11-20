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

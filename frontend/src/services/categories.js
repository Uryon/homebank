/**
 * Aplati l'arbre de categories (racines + enfants directs) en une liste
 * plate { id, label, type }, utilisee par les autocompletes. Partagee entre
 * Transactions.vue et Payees.vue pour eviter que les deux implementations
 * divergent au prochain changement.
 *
 * Les enfants sont indexes par parentId en un seul passage (Map), plutot
 * que de refaire un `.filter(c => c.parentId === root.id)` pour chaque
 * racine (scan lineaire repete inutile si le nombre de categories croit).
 */
export function flattenCategories(categories) {
  const childrenByParentId = new Map()
  for (const c of categories) {
    if (!c.parentId) continue
    if (!childrenByParentId.has(c.parentId)) childrenByParentId.set(c.parentId, [])
    childrenByParentId.get(c.parentId).push(c)
  }

  const result = []
  for (const root of categories) {
    if (root.parentId) continue
    result.push({ id: root.id, label: root.name, type: root.type })
    for (const child of childrenByParentId.get(root.id) || []) {
      result.push({ id: child.id, label: `${root.name} : ${child.name}`, type: child.type })
    }
  }
  return result
}

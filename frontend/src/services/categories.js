/**
 * Aplati l'arbre de categories (racines + enfants directs) en une liste
 * plate { id, label, type, selectable }, utilisee par les autocompletes.
 * Partagee entre Transactions.vue et Payees.vue pour eviter que les deux
 * implementations divergent au prochain changement.
 *
 * `selectable` distingue les categories "feuille" (assignables a une
 * transaction) des categories racine qui ont des sous-categories : une
 * racine avec enfants n'est affichee que pour donner le contexte de la
 * hierarchie, jamais assignable directement (voir CategoryAutocomplete).
 * Une racine SANS enfant reste selectable (sinon elle deviendrait
 * inutilisable) : c'est le seul cas ou une categorie "racine" est assignee
 * directement a une transaction.
 *
 * Les enfants sont indexes par parentId en un seul passage (Map), plutot
 * que de refaire un `.filter(c => c.parentId === root.id)` pour chaque
 * racine (scan lineaire repete inutile si le nombre de categories croit).
 *
 * `hidden` reprend le flag Category#hidden (categorie masquee car plus
 * utilisee) : conserve sur chaque entree pour que CategoryAutocomplete
 * puisse toujours resoudre le libelle d'une categorie deja affectee (meme
 * masquee), tout en l'excluant des suggestions proposees pour un nouveau
 * choix.
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
    const children = childrenByParentId.get(root.id) || []
    result.push({ id: root.id, label: root.name, type: root.type, selectable: children.length === 0, hidden: !!root.hidden })
    for (const child of children) {
      result.push({ id: child.id, label: `${root.name} : ${child.name}`, type: child.type, selectable: true, hidden: !!child.hidden })
    }
  }
  return result
}

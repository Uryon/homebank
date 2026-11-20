import { categoryIconPrefix } from './categories'
import { normalizeSearch } from './search'

/**
 * Un critère matche un nom de catégorie : en mode "contient" (défaut) il
 * suffit que le terme soit inclus dans le nom normalisé ; en mode "exact"
 * (terme entre guillemets, ex : "CAF") il faut une égalité stricte, pour
 * retrouver "CAF" sans remonter aussi une catégorie contenant "caf".
 */
export function nameMatchesTerm(name, { term, exact }) {
  const normalized = normalizeSearch(name)
  return exact ? normalized === term : normalized.includes(term)
}

const matchesAll = (name, terms) => terms.every(criterion => nameMatchesTerm(name, criterion))

/**
 * Racines retenues par la recherche : chaque critère (tous exigés, voir
 * parseSearchTerms) doit se trouver dans le nom de la racine ou d'une de ses
 * sous-catégories. `children(rootId)` : sous-catégories d'une racine.
 */
export function filterCategoryRoots(roots, terms, children) {
  if (!terms.length) return roots
  return roots.filter(root =>
    terms.every(criterion =>
      nameMatchesTerm(root.name, criterion) ||
      children(root.id).some(child => nameMatchesTerm(child.name, criterion))
    )
  )
}

/**
 * Sous-catégories visibles de chaque racine (id racine -> enfants), pour les
 * colonnes Dépenses/Revenus : les masquées en sont exclues (elles apparaissent
 * dans l'encart dédié). Une racine qui répond à la recherche garde tous ses
 * enfants ; sinon seuls ceux qui y répondent restent.
 */
export function visibleChildrenByRoot(roots, terms, children) {
  const map = new Map()
  for (const root of roots) {
    const kids = children(root.id).filter(child => !child.hidden)
    const keepAllKids = !terms.length || matchesAll(root.name, terms)
    map.set(root.id, keepAllKids ? kids : kids.filter(child => matchesAll(child.name, terms)))
  }
  return map
}

/** Libellé complet « Parent : Enfant », icônes comprises. `byId` : Map id -> catégorie. */
export function categoryFullLabel(category, byId) {
  const ownLabel = `${categoryIconPrefix(category.icon)}${category.name}`
  if (!category.parentId) return ownLabel
  const parent = byId.get(category.parentId)
  return parent ? `${categoryIconPrefix(parent.icon)}${parent.name} : ${ownLabel}` : ownLabel
}

/**
 * Catégories masquées (racines et sous-catégories) retenues par la recherche,
 * triées par libellé complet. Le libellé est calculé une seule fois par
 * catégorie, jamais dans le comparateur du tri (appelé O(n log n) fois).
 */
export function hiddenCategoriesMatching(categories, terms, byId) {
  return categories
    .filter(c => c.hidden)
    .map(category => ({ category, label: categoryFullLabel(category, byId) }))
    .filter(({ label }) => !terms.length || matchesAll(label, terms))
    .sort((a, b) => a.label.localeCompare(b.label, 'fr'))
    .map(({ category }) => category)
}

/**
 * Une catégorie sélectionnée ne peut pas être choisie comme cible de fusion
 * si l'une de ses catégories ancêtres est elle-même sélectionnée : elle
 * deviendrait alors source ET descendante de la cible, ce que le backend
 * rejette (cycle parent/enfant, voir ServiceCategorie#merge).
 */
function isDescendantOfSelected(category, selectedIdSet, byId) {
  let current = byId.get(category.parentId)
  while (current) {
    if (selectedIdSet.has(current.id)) return true
    current = byId.get(current.parentId)
  }
  return false
}

/**
 * Cibles possibles d'une fusion parmi la sélection. Une catégorie qui a des
 * sous-catégories ne se fusionne que dans une racine (deux niveaux au plus).
 */
export function mergeableTargets(selection, selectedIdSet, byId, children) {
  const rootOnly = selection.some(c => !c.parentId && children(c.id).length > 0)
  return selection.filter(c => !isDescendantOfSelected(c, selectedIdSet, byId) && (!rootOnly || !c.parentId))
}

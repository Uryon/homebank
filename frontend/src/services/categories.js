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
/**
 * Vrai si la transaction attend encore une catégorie : sans catégorie, et
 * ni virement interne (lié à sa jumelle) ni ajustement de solde, qui ne
 * sont ni des dépenses ni des revenus (voir Transaction#isInternalTransfer
 * et Transaction#balanceAdjustment côté backend). Source unique pour la
 * pastille « sans catégorie » et le filtre du même nom.
 */
export function needsCategory(t) {
  return !t.categoryId && !t.transferTransactionId && !t.balanceAdjustment
}

/**
 * Sous-catégories regroupées par catégorie racine. L'application gère deux
 * niveaux, mais une ancienne fusion a pu créer un niveau de plus : ces
 * catégories sont rattachées à leur racine pour rester visibles et réparables.
 */
export function childrenByRootId(categories) {
  const byId = new Map(categories.map(c => [c.id, c]))
  const result = new Map()
  for (const c of categories) {
    if (!c.parentId) continue
    const seen = new Set([c.id])
    let root = byId.get(c.parentId)
    while (root?.parentId && !seen.has(root.id)) {
      seen.add(root.id)
      root = byId.get(root.parentId)
    }
    if (!root || root.parentId) continue
    if (!result.has(root.id)) result.set(root.id, [])
    result.get(root.id).push(c)
  }
  return result
}

export function flattenCategories(categories) {
  const childrenByParentId = childrenByRootId(categories)

  const withIcon = (c) => (c.icon ? `${c.icon} ${c.name}` : c.name)

  const result = []
  for (const root of categories) {
    if (root.parentId) continue
    const children = childrenByParentId.get(root.id) || []
    result.push({ id: root.id, label: withIcon(root), type: root.type, selectable: children.length === 0, hidden: !!root.hidden })
    for (const child of children) {
      result.push({ id: child.id, label: `${withIcon(root)} : ${withIcon(child)}`, type: child.type, selectable: true, hidden: !!child.hidden })
    }
  }
  return result
}

/**
 * Données d'affichage de la pastille de catégorie (CategoryChip). La teinte
 * (0-360) est dérivée de la catégorie RACINE : une catégorie et ses
 * sous-catégories partagent la même couleur, stable d'une session à l'autre
 * sans rien stocker en base. Renvoie null si la catégorie est inconnue.
 */
/** Teinte (0-359) d'une catégorie racine, répartie sur le cercle chromatique : puces et graphiques (voir categoryColors.js). */
export function categoryHue(rootId) {
  return (rootId * 47) % 360
}

export function categoryChipInfo(categoryId, categoriesById) {
  const category = categoryId ? categoriesById.get(categoryId) : null
  if (!category) return null
  const parent = category.parentId ? categoriesById.get(category.parentId) : null
  const root = parent || category
  return {
    name: category.name,
    parent: parent ? parent.name : '',
    icon: root.icon || category.icon || '',
    hue: categoryHue(root.id),
    path: parent ? `${parent.name} › ${category.name}` : category.name
  }
}

/**
 * Recherche de la page Transactions qui affiche les transactions d'une
 * catégorie (entrée de flattenCategories) : son libellé « Parent : Enfant »
 * à l'identique, entre guillemets. Pour une racine, la recherche exacte
 * retrouve aussi ses sous-catégories (voir matchesSearchTerm), y compris
 * quand des transactions sont rattachées directement à la racine.
 */
export function transactionSearchForCategory(entry) {
  return `"${entry.label}"`
}

/** Recherche de la page Transactions qui affiche les transactions d'un tiers (nom exact, entre guillemets). */
export function transactionSearchForPayee(payee) {
  return `"${payee.name}"`
}

/** Types d'une catégorie (énumération TypeCategorie du backend), dans l'ordre des listes de choix. */
export const CATEGORY_TYPE_OPTIONS = Object.freeze([
  { value: 'EXPENSE', label: 'Dépense' },
  { value: 'INCOME', label: 'Revenu' },
  { value: 'BOTH', label: 'Les deux' }
])

const CATEGORY_TYPE_LABELS = Object.fromEntries(CATEGORY_TYPE_OPTIONS.map(o => [o.value, o.label]))

/** Libellé d'un type de catégorie (« Dépense », « Revenu », « Les deux ») ; type inconnu rendu tel quel. */
export function categoryTypeLabel(type) {
  return CATEGORY_TYPE_LABELS[type] || type
}

/** Préfixe un nom de catégorie avec son icône (emoji) suivie d'un espace ; chaîne vide sans icône. */
export function categoryIconPrefix(icon) {
  return icon ? `${icon} ` : ''
}

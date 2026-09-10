/**
 * Normalise une chaîne pour la recherche : minuscules + suppression des
 * accents/diacritiques, afin que "electricite" retrouve "Électricité" et
 * inversement.
 */
export function normalizeSearch(str) {
  if (!str) return ''
  return str
    .toString()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim()
}

/**
 * Détecte une recherche de transactions par montant avec opérateur de
 * comparaison : "> 100", "< 50", "= 42.50" (espace optionnel, virgule ou
 * point comme séparateur décimal, signe négatif accepté pour rechercher
 * spécifiquement un montant négatif, ex : "< -20").
 * Renvoie { operator, value } (value en chaîne, format API "12.34") ou null
 * si le texte ne correspond pas à ce format.
 */
export function parseAmountQuery(str) {
  if (!str) return null
  const match = str.trim().match(/^([<>=])\s*(-?\d+(?:[.,]\d{1,2})?)$/)
  if (!match) return null
  const [, operator, rawValue] = match
  return { operator, value: rawValue.replace(',', '.') }
}

/**
 * Découpe le texte de recherche en critères (séparés par ";"), normalisés.
 * Un critère entre guillemets ("CAF") est marqué "exact" : il ne doit
 * correspondre qu'à un tiers/catégorie/montant strictement égal, pas
 * seulement contenant le texte — pour retrouver "CAF" sans remonter aussi
 * "Décaféiné" ou tout autre tiers/catégorie contenant "caf".
 */
export function parseSearchTerms(query) {
  if (!query) return []
  return query
    .split(';')
    .map(raw => {
      const trimmed = raw.trim()
      const exact = trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
      const term = normalizeSearch(exact ? trimmed.slice(1, -1) : trimmed)
      return term ? { term, exact } : null
    })
    .filter(Boolean)
}

/**
 * Un critère matche l'index précalculé d'une transaction (voir
 * searchIndexById dans Transactions.vue) : en mode "contient" (défaut) il
 * suffit que le terme soit inclus dans le tiers, la catégorie ou le montant ;
 * en mode "exact" (terme entre guillemets) il faut une égalité stricte —
 * contre le nom de la catégorie seul (categoryNameNormalized, ex : "Salaire")
 * ou son libellé complet avec le parent (categoryNormalized, ex :
 * "Traitements et salaires : Salaire"), pour matcher aussi bien "Salaire"
 * que le libellé affiché en entier.
 */
export function matchesSearchTerm(idx, { term, exact }) {
  const termAsAmount = term.replace(',', '.')
  if (exact) {
    return (
      idx.payeeNormalized === term ||
      idx.categoryNormalized === term ||
      idx.categoryNameNormalized === term ||
      idx.rawAmount === termAsAmount ||
      idx.absAmount === termAsAmount ||
      idx.amountFormattedNormalized === termAsAmount
    )
  }
  return (
    idx.payeeNormalized.includes(term) ||
    idx.categoryNormalized.includes(term) ||
    idx.rawAmount.includes(termAsAmount) ||
    idx.absAmount.includes(termAsAmount) ||
    idx.amountFormattedNormalized.includes(termAsAmount)
  )
}

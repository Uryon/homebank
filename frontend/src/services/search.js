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

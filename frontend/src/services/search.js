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

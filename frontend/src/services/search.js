import { parseAmountText } from './amountText'

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
 * comparaison : "> 100", ">= 100", "< 50", "<= 50", "= 1 042,50" (montant lu
 * comme à la saisie, voir parseAmountText ; signe négatif accepté pour
 * rechercher spécifiquement un montant négatif, ex : "< -20").
 * Renvoie { operator, value } (value en chaîne, format API "12.34") ou null
 * si le texte ne correspond pas à ce format.
 */
export function parseAmountQuery(str) {
  if (!str) return null
  const match = str.trim().match(/^(>=|<=|[<>=])\s*(.+)$/)
  if (!match) return null
  const value = parseAmountText(match[2])
  return value === null ? null : { operator: match[1], value }
}

/**
 * Vrai si le montant (signé) de la transaction satisfait la recherche
 * { operator, value } renvoyée par parseAmountQuery. Comparaison en
 * centimes entiers : évite qu'un montant flottant (ex : 0.1 + 0.2) rate un
 * "= 0.30".
 */
export function matchesAmountQuery(transaction, { operator, value }) {
  const cents = Math.round(Number(transaction.amount) * 100)
  const target = Math.round(Number(value) * 100)
  if (Number.isNaN(cents)) return false
  if (operator === '>') return cents > target
  if (operator === '>=') return cents >= target
  if (operator === '<') return cents < target
  if (operator === '<=') return cents <= target
  return cents === target
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
 * suffit que le terme soit inclus dans le tiers, la catégorie, le mémo, le
 * libellé du relevé bancaire (transaction importée, utile quand le tiers a
 * été renommé depuis) ou le montant (mémo et libellé, textes libres, ne sont
 * pas concernés par le mode "exact") ;
 * en mode "exact" (terme entre guillemets) il faut une égalité stricte —
 * contre le nom de la catégorie seul (categoryNameNormalized, ex : "Salaire")
 * ou son libellé complet avec le parent (categoryNormalized, ex :
 * "Traitements et salaires : Salaire"), pour matcher aussi bien "Salaire"
 * que le libellé affiché en entier. Une catégorie racine cherchée à
 * l'identique ("Alimentation") retrouve aussi les transactions de ses
 * sous-catégories (libellé « Alimentation : … ») : c'est le lien « Voir les
 * transactions » des pages Catégories et palette, qui sinon oubliait soit la
 * racine, soit ses enfants.
 */
export function matchesSearchTerm(idx, { term, exact }) {
  const termAsAmount = term.replace(',', '.')
  if (exact) {
    return (
      idx.payeeNormalized === term ||
      idx.categoryNormalized === term ||
      idx.categoryNormalized.startsWith(term + ' : ') ||
      idx.categoryNameNormalized === term ||
      idx.rawAmount === termAsAmount ||
      idx.absAmount === termAsAmount ||
      idx.amountFormattedNormalized === termAsAmount
    )
  }
  return (
    idx.payeeNormalized.includes(term) ||
    idx.categoryNormalized.includes(term) ||
    (idx.memoNormalized ?? '').includes(term) ||
    (idx.bankLabelNormalized ?? '').includes(term) ||
    idx.rawAmount.includes(termAsAmount) ||
    idx.absAmount.includes(termAsAmount) ||
    idx.amountFormattedNormalized.includes(termAsAmount)
  )
}

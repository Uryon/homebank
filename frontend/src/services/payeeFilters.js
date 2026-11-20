import { formatAmount } from './format'
import { normalizeSearch, parseSearchTerms } from './search'

/**
 * Champs de recherche normalisés (nom, motif, catégorie, montant) par tiers,
 * à précalculer une seule fois quand les tiers ou les catégories changent
 * plutôt qu'à chaque frappe — même principe que buildSearchIndex pour les
 * transactions (voir transactionFilters.js).
 *
 * `categoryLabel(categoryId)` : libellé « Parent : Enfant » de la catégorie.
 */
export function buildPayeeSearchIndex(payees, categoryLabel) {
  const index = new Map()
  for (const p of payees) {
    index.set(p.id, {
      nameNormalized: normalizeSearch(p.name),
      patternNormalized: normalizeSearch(p.pattern),
      categoryNormalized: normalizeSearch(categoryLabel(p.categoryId)),
      rawAmount: p.amount != null ? String(p.amount).toLowerCase() : '',
      amountFormattedNormalized: p.amount != null ? normalizeSearch(formatAmount(p.amount)) : ''
    })
  }
  return index
}

/**
 * Un critère matche ce tiers : en mode "contient" (défaut) il suffit que le
 * terme soit inclus dans le nom, le motif, la catégorie associée, ou le
 * montant (comparé à la fois brut et formaté, virgule ou point acceptés —
 * même logique que la recherche par montant des transactions) ; en mode
 * "exact" (terme entre guillemets, ex : "CAF") il faut une égalité stricte
 * avec le nom, le motif ou la catégorie, pour retrouver "CAF" sans remonter
 * aussi un tiers comme "Décaféiné".
 */
export function payeeMatchesTerm(idx, { term, exact }) {
  const termAsAmount = term.replace(',', '.')
  if (exact) {
    return (
      idx.nameNormalized === term ||
      idx.patternNormalized === term ||
      idx.categoryNormalized === term ||
      idx.rawAmount === termAsAmount ||
      idx.amountFormattedNormalized === termAsAmount
    )
  }
  if (idx.nameNormalized.includes(term)) return true
  if (idx.patternNormalized.includes(term)) return true
  if (idx.categoryNormalized.includes(term)) return true
  return idx.rawAmount.includes(termAsAmount) || idx.amountFormattedNormalized.includes(term)
}

/**
 * Tiers affichés par la page Tiers : filtres « Sans catégorie » et
 * « Inutilisés », puis recherche. Plusieurs critères séparés par ";" sont
 * tous exigés à la fois (ET), ex : "Bouygues;29,99" pour retrouver le tiers
 * Bouygues associé à ce montant précis — chaque critère pris seul reste un
 * simple "contient" (OU entre les champs), sauf s'il est entre guillemets
 * ("CAF"), auquel cas seule une correspondance exacte est retenue — voir
 * parseSearchTerms dans search.js.
 */
export function filterPayees(payees, { query, withoutCategoryOnly, unusedOnly, isUnused, searchIndex }) {
  const terms = parseSearchTerms(query)
  const base = payees.filter(p =>
    (!withoutCategoryOnly || !p.categoryId) && (!unusedOnly || isUnused(p)))
  if (!terms.length) return base
  return base.filter(p => {
    const idx = searchIndex.get(p.id)
    return idx && terms.every(criterion => payeeMatchesTerm(idx, criterion))
  })
}

/**
 * Catégorie(s) distincte(s) utilisée(s) par les transactions DÉJÀ
 * catégorisées de chaque tiers : sert à suggérer une catégorie pour un tiers
 * qui n'en a pas encore. Les transactions non catégorisées n'entrent pas en
 * compte (ni pour, ni contre) : seul un signal consistant compte.
 */
export function categoryIdsByPayeeId(transactions) {
  const map = new Map()
  for (const t of transactions) {
    if (!t.payeeId || t.categoryId == null) continue
    let set = map.get(t.payeeId)
    if (!set) {
      set = new Set()
      map.set(t.payeeId, set)
    }
    set.add(t.categoryId)
  }
  return map
}

/**
 * Catégorie suggérée pour un tiers sans catégorie, ou null si aucun signal
 * exploitable (aucune transaction catégorisée, ou catégories divergentes) ou
 * si la seule catégorie trouvée est masquée (plus utilisée : la saisie ne la
 * propose plus, voir CategoryAutocomplete — même règle que
 * ServiceCategorisationAuto#autoCategorize).
 */
export function suggestedCategoryIdFor(payee, idsByPayeeId, categoriesById) {
  if (payee.categoryId) return null
  const ids = idsByPayeeId.get(payee.id)
  if (!ids || ids.size !== 1) return null
  const categoryId = ids.values().next().value
  return categoriesById.get(categoryId)?.hidden ? null : categoryId
}

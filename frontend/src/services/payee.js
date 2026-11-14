/**
 * Desambiguisation d'un tiers par nom, extraite pour eviter de la dupliquer
 * entre Transactions.vue et TransactionRow.vue.
 */

/**
 * Parmi les tiers partageant ce nom (indexes dans payeeIndex, une Map de nom
 * normalise vers un tableau de tiers — un nom peut correspondre a PLUSIEURS
 * tiers, voir Tiers#amount : "Bouygues" a 10€ et a 20€ sont deux tiers
 * distincts avec le meme nom affiche), celui dont le montant de reference
 * correspond exactement (en valeur absolue) au montant de la transaction —
 * meme logique que ServiceTiers#matchPayee cote backend — sinon un tiers
 * "generique" (sans montant de reference) s'il en existe un, sinon le
 * premier. Sans cette distinction par montant, la categorisation automatique
 * pouvait proposer le mauvais tiers (et donc la mauvaise categorie) parmi
 * plusieurs homonymes (ex : plusieurs "Bouygues" a des montants differents).
 */
export function findPayeeByName(payeeIndex, name, amount) {
  if (!name?.trim()) return null
  const candidates = payeeIndex.get(name.trim().toLowerCase())
  if (!candidates?.length) return null
  if (candidates.length === 1) return candidates[0]

  if (amount != null && amount !== '') {
    const absAmount = Math.abs(Number(amount))
    const exactAmountMatch = candidates.find(p => p.amount != null && Math.abs(Number(p.amount)) === absAmount)
    if (exactAmountMatch) return exactAmountMatch
  }
  return candidates.find(p => p.amount == null) || candidates[0]
}

/**
 * Découpe le motif de reconnaissance d'un tiers en ses segments (séparés par
 * ";", voir ServiceTiers#matchesPatternField côté backend), espaces multiples
 * réduits comme au moment de la comparaison, segments vides ignorés.
 */
export function splitPatternSegments(pattern) {
  if (!pattern) return []
  return pattern
    .split(';')
    .map(segment => segment.trim().replace(/\s+/g, ' '))
    .filter(Boolean)
}

/** Échappe les caractères spéciaux d'une expression régulière, sauf l'étoile (gardée comme joker). */
function escapeRegexChars(text) {
  return text.replace(/[.+?^${}()|[\]\\]/g, String.raw`\$&`)
}

/** Motif « joker » (avec *) converti en expression régulière (miroir de ServiceTiers#wildcardToRegex). */
function wildcardToRegex(wildcardPattern) {
  return wildcardPattern
    .split('*')
    .map(part => (part ? escapeRegexChars(part) : ''))
    .join('.*')
}

/**
 * Le motif d'un tiers (un ou plusieurs segments séparés par « ; ») reconnaît-il
 * ce libellé ? Miroir de ServiceTiers#matchesPatternField côté backend : un
 * segment sans étoile est cherché n'importe où dans le libellé (« contient »),
 * sans tenir compte de la casse ni des espaces multiples ; un segment avec
 * étoile est un motif avancé (ex : « Carrefour*Paris »).
 */
export function matchesPatternField(patternField, text) {
  if (!text || !text.trim()) return false
  const normalizedText = text.trim().replace(/\s+/g, ' ')
  const lowerText = normalizedText.toLowerCase()
  return splitPatternSegments(patternField).some(segment => {
    if (!segment.includes('*')) return lowerText.includes(segment.toLowerCase())
    // Étoiles de début/fin inutiles (la recherche porte déjà sur tout le libellé).
    const core = segment.replace(/^\*+|\*+$/g, '')
    if (!core) return true // motif « * » : correspond à tout
    try {
      return new RegExp(wildcardToRegex(core), 'i').test(normalizedText)
    } catch {
      return false // motif invalide : ignoré, comme côté backend
    }
  })
}

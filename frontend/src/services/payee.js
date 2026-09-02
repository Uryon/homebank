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
  if (!name || !name.trim()) return null
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

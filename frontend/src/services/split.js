/**
 * Validation du montant de la 1ère partie lors de la division d'une
 * transaction (modale « Diviser » de Transactions.vue). La 2e partie reçoit
 * le reste : la 1ère doit donc être non nulle, du même signe que le total et
 * strictement plus petite que lui en valeur absolue (sinon la 2e partie
 * serait nulle ou de signe opposé). Le backend applique la même règle
 * (ServiceTransaction#splitTransaction) ; ce contrôle sert à indiquer
 * précisément quoi corriger avant l'envoi.
 *
 * Retourne le message d'erreur à afficher sous le champ, ou '' si valide.
 * `formatTotal` met en forme le montant total dans le message (devise du compte).
 */
export function splitFirstAmountError(total, first, formatTotal = String) {
  const totalAmount = Number(total)
  const firstAmount = Number(first)
  if (first == null || first === '' || !Number.isFinite(firstAmount) || firstAmount === 0) {
    return 'Saisissez un montant non nul pour la 1ère partie.'
  }
  if ((totalAmount < 0) !== (firstAmount < 0)) {
    return totalAmount < 0
      ? 'La 1ère partie doit être négative, comme le montant total (dépense).'
      : 'La 1ère partie doit être positive, comme le montant total (revenu).'
  }
  if (Math.abs(firstAmount) >= Math.abs(totalAmount)) {
    return `La 1ère partie doit être inférieure au montant total (${formatTotal(totalAmount)}) pour laisser une 2e partie.`
  }
  return ''
}

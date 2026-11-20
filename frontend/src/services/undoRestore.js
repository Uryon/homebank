/**
 * Données nécessaires à l'action « Annuler » de la page Transactions.
 * Fonctions pures : l'appel API correspondant est fait par le composant.
 */

/** Champs saisissables d'une transaction (même forme que TransactionDTO côté backend). */
export function transactionPayloadFrom(t) {
  return {
    accountId: t.accountId,
    date: t.date,
    payee: t.payee,
    amount: t.amount,
    categoryId: t.categoryId,
    paymentMode: t.paymentMode,
    memo: t.memo,
    transferTransactionId: t.transferTransactionId,
    periodicExpense: t.periodicExpense,
    exceptional: t.exceptional,
    validated: t.validated
  }
}

/**
 * État d'une transaction chargée, à rétablir après une modification. Sans
 * tiers lié, `payee` n'est qu'un libellé calculé (« Virement vers… ») : le
 * renvoyer le ferait prendre pour un vrai nom de tiers.
 */
export function previousTransactionPayload(t) {
  return { ...transactionPayloadFrom(t), payee: t.payeeId != null ? t.payee : null }
}

/**
 * Copies des transactions à restaurer après leur suppression (voir
 * ServiceRestaurationTransaction côté backend), à prendre AVANT de supprimer.
 * Le backend supprime toujours les deux côtés d'un virement ensemble : la
 * jumelle, même absente du lot, est donc retrouvée dans `allTransactions`.
 */
export function restorationSnapshots(transactionsToDelete, allTransactions) {
  const byId = new Map(allTransactions.map(t => [t.id, t]))
  const snapshots = new Map()
  for (const t of transactionsToDelete) {
    snapshots.set(t.id, { ...t })
    if (!t.transferTransactionId) continue
    const twin = byId.get(t.transferTransactionId)
    if (twin) snapshots.set(twin.id, { ...twin })
    else console.warn(`Annulation : jumelle de virement introuvable pour ${t.id}, restauration sans appariement.`)
  }
  return Array.from(snapshots.values())
}

/**
 * Lance les opérations en parallèle, sauf celles d'un même tiers, enchaînées :
 * ce tiers a pu disparaître avec ses transactions, et plusieurs requêtes
 * simultanées le recréaient chacune de leur côté, en double.
 */
export async function runOnePerPayee(items, payeeOf, run) {
  const lastByPayee = new Map()
  await Promise.all(items.map(item => {
    const payee = payeeOf(item)?.trim().toLowerCase()
    if (!payee) return run(item)
    const previous = lastByPayee.get(payee)
    const current = previous ? previous.catch(() => {}).then(() => run(item)) : run(item)
    lastByPayee.set(payee, current)
    return current
  }))
}

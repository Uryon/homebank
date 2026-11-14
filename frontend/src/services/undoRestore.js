/**
 * Plan de restauration des transactions supprimees, pour l'action « Annuler »
 * de la page Transactions. Fonctions pures : l'appel API correspondant a
 * chaque etape est fait par le composant.
 */

/**
 * Payload API (meme forme que TransactionDTO cote backend) reconstruit a
 * partir d'une transaction deja chargee, pour la recreer telle quelle.
 */
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
    validated: t.validated,
    // Recréé tel quel (pris en compte par le backend à la création seulement).
    balanceAdjustment: !!t.balanceAdjustment,
    // Libellé du relevé d'une transaction importée : restauré pour que l'import
    // continue de la reconnaître (voir Transaction#bankLabel côté backend).
    bankLabel: t.bankLabel || null
  }
}

/**
 * Côté d'un virement INTERNE : c'est la liaison à sa jumelle qui le définit
 * (voir Transaction#isInternalTransfer côté backend), pas le mode de
 * paiement « Virement », qui peut être celui d'un simple loyer.
 */
function isTransferLeg(t) {
  return !!t.transferTransactionId
}

/**
 * Etapes de restauration d'un lot de transactions supprimees :
 * - `{ kind: 'create', ids, payload }` : transaction normale, recreee via create() ;
 * - `{ kind: 'transfer', ids, payload }` : virement, recree en paire via
 *   transfer(). Le backend supprime toujours les deux cotes ensemble (voir
 *   ServiceTransaction#delete), et create() ignore transferTransactionId :
 *   il faut donc retrouver la jumelle dans `allTransactions` (elle n'est pas
 *   forcement dans le lot) et recreer la paire. Une paire dont les deux cotes
 *   sont dans le lot ne donne qu'une seule etape.
 *
 * `orphanTransferIds` liste les cotes de virement dont la jumelle est
 * introuvable : ils sont recrees en transaction simple, sans appariement.
 */
export function planRestore(transactionsToDelete, allTransactions) {
  const byId = new Map(allTransactions.map(t => [t.id, t]))
  const handledIds = new Set()
  const steps = []
  const orphanTransferIds = []

  for (const t of transactionsToDelete) {
    if (handledIds.has(t.id)) continue
    handledIds.add(t.id)

    const pair = isTransferLeg(t) ? byId.get(t.transferTransactionId) : null
    if (pair) {
      handledIds.add(pair.id)
      const [from, to] = t.amount < 0 ? [t, pair] : [pair, t]
      steps.push({
        kind: 'transfer',
        ids: [t.id, pair.id],
        payload: {
          fromAccountId: from.accountId,
          toAccountId: to.accountId,
          amount: Math.abs(t.amount),
          date: t.date,
          memo: t.memo
        }
      })
      continue
    }

    if (isTransferLeg(t)) orphanTransferIds.push(t.id)
    steps.push({ kind: 'create', ids: [t.id], payload: transactionPayloadFrom(t) })
  }

  return { steps, orphanTransferIds }
}

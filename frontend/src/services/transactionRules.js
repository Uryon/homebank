/**
 * Ce qu'une transaction refuse, et pourquoi : une seule source pour
 * l'info-bulle du bouton (bouton grisé, voir TransactionRow.vue) et pour le
 * message affiché au clic (voir transactionSplit.js et transactionCreation.js).
 * Chaîne vide : l'action est permise.
 */

/** Seul un virement interne (lié à sa jumelle) ou un ajustement de solde est indivisible ; un paiement par virement ordinaire (loyer…) se divise. */
export function splitBlockedReason(transaction) {
  if (transaction.transferTransactionId) return 'Un virement entre comptes ne peut pas être divisé'
  if (transaction.balanceAdjustment) return 'Un ajustement de solde ne peut pas être divisé'
  return ''
}

/** Virements et ajustements de solde ne se dupliquent pas : ils se créent depuis la page Comptes. */
export function duplicateBlockedReason(transaction) {
  if (transaction.transferTransactionId) return 'Un virement entre comptes se crée depuis la page Comptes'
  if (transaction.balanceAdjustment) return 'Un ajustement de solde se crée depuis la page Comptes'
  return ''
}

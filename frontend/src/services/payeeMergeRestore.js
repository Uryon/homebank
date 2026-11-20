/**
 * Annulation d'une fusion de tiers (voir ServiceTiers#merge) : les tiers
 * source sont supprimés, leurs transactions passent sur le tiers conservé,
 * dont le motif (et éventuellement la catégorie) change. Le plan est établi
 * AVANT la fusion, tant que ces informations existent encore.
 */

function payeeFields(p) {
  return { name: p.name, pattern: p.pattern, amount: p.amount, categoryId: p.categoryId ?? null }
}

/**
 * `payees`, `transactions` : cache partagé avant la fusion. Renvoie les
 * tiers source (avec les ids de leurs transactions) et l'état d'origine du
 * tiers conservé.
 */
export function planPayeeMergeRestore({ payees, transactions }, sourceIds, targetId) {
  const sourceSet = new Set(sourceIds)
  const transactionIdsByPayee = new Map()
  for (const t of transactions) {
    if (!sourceSet.has(t.payeeId)) continue
    if (!transactionIdsByPayee.has(t.payeeId)) transactionIdsByPayee.set(t.payeeId, [])
    transactionIdsByPayee.get(t.payeeId).push(t.id)
  }
  const target = payees.find(p => p.id === targetId)
  return {
    target: target ? { id: target.id, ...payeeFields(target) } : null,
    sources: payees
      .filter(p => sourceSet.has(p.id))
      .map(p => ({ fields: payeeFields(p), transactionIds: transactionIdsByPayee.get(p.id) ?? [] }))
  }
}

/**
 * Recrée les tiers source (nouveaux ids), leur rend leurs transactions, et
 * remet le tiers conservé dans son état d'origine. Ce dernier peut avoir été
 * supprimé par le nettoyage des tiers orphelins (tiers jamais personnalisé,
 * sans transaction avant la fusion) : il est alors recréé.
 * `api` : { payees, transactions } (PayeesAPI, TransactionsAPI, injectés pour les tests).
 */
export async function restorePayeeMerge(plan, api) {
  if (plan.target) await api.payees.update(plan.target.id, payeeFields(plan.target))
  for (const source of plan.sources) {
    const created = await api.payees.create(source.fields)
    if (source.transactionIds.length) {
      await api.transactions.bulkUpdate({ ids: source.transactionIds, payeeProvided: true, payeeId: created.id })
    }
  }
  if (plan.target) {
    const remaining = await api.payees.list()
    if (!remaining.some(p => p.id === plan.target.id)) await api.payees.create(payeeFields(plan.target))
  }
}

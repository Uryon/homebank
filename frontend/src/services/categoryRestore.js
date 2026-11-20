/**
 * Annulation des opérations de masse sur les catégories (fusion, changement
 * groupé de catégorie parente) par différence entre deux photographies :
 * plutôt que de rejouer à l'envers la logique du serveur (fusion récursive
 * des sous-catégories homonymes, voir ServiceCategorie#merge), on remet
 * catégories, transactions et tiers dans l'état d'avant.
 */

/** Photographie des données touchées par une opération sur les catégories (cache partagé, voir dataStore). */
export function snapshotCategoryState({ categories, transactions, payees }) {
  return {
    categories: categories.map(c => ({ ...c })),
    transactionCategory: new Map(transactions.map(t => [t.id, t.categoryId ?? null])),
    payees: payees.map(p => ({ ...p }))
  }
}

/** Catégories supprimées, parents avant enfants (un enfant recréé a besoin du nouvel id de son parent). */
function parentsFirst(deleted) {
  const pending = new Set(deleted.map(c => c.id))
  const ordered = []
  while (pending.size) {
    const before = pending.size
    for (const c of deleted) {
      if (pending.has(c.id) && !pending.has(c.parentId)) {
        ordered.push(c)
        pending.delete(c.id)
      }
    }
    if (pending.size === before) break // cycle impossible en pratique : on s'arrête plutôt que boucler
  }
  return ordered
}

/**
 * Étapes pour revenir de `after` (état courant) à `before` :
 * - `toCreate` : catégories supprimées, à recréer (parents d'abord) ;
 * - `toUpdate` : catégories encore présentes dont le nom, le type ou le parent a changé ;
 * - `transactionGroups` : [ancienne catégorie, ids] des transactions réaffectées ;
 * - `payeesToRestore` : tiers (état d'avant) dont la catégorie par défaut a changé.
 */
export function planCategoryStateRestore(before, after) {
  const afterCategories = new Map(after.categories.map(c => [c.id, c]))
  const toCreate = parentsFirst(before.categories.filter(c => !afterCategories.has(c.id)))
  const toUpdate = before.categories.filter(c => {
    const now = afterCategories.get(c.id)
    return now && (now.name !== c.name || now.type !== c.type || (now.parentId ?? null) !== (c.parentId ?? null))
  })

  const groups = new Map()
  for (const t of after.transactions) {
    if (!before.transactionCategory.has(t.id)) continue
    const previous = before.transactionCategory.get(t.id)
    if (previous === (t.categoryId ?? null)) continue
    if (!groups.has(previous)) groups.set(previous, [])
    groups.get(previous).push(t.id)
  }

  const afterPayees = new Map(after.payees.map(p => [p.id, p]))
  const payeesToRestore = before.payees.filter(p => {
    const now = afterPayees.get(p.id)
    return now && (now.categoryId ?? null) !== (p.categoryId ?? null)
  })

  return { toCreate, toUpdate, transactionGroups: [...groups.entries()], payeesToRestore }
}

/** Vrai si le plan ne change rien (opération sans effet visible : pas d'annulation proposée). */
export function isEmptyRestorePlan(plan) {
  return !plan.toCreate.length && !plan.toUpdate.length && !plan.transactionGroups.length && !plan.payeesToRestore.length
}

/**
 * Exécute un plan de planCategoryStateRestore. `api` : { categories, transactions,
 * payees } (CategoriesAPI, TransactionsAPI, PayeesAPI, injectés pour les tests).
 */
export async function restoreCategoryState(plan, api) {
  const newIdByOldId = new Map()
  const mapId = id => (id == null ? null : newIdByOldId.get(id) ?? id)

  for (const c of plan.toCreate) {
    const created = await api.categories.create({ name: c.name, icon: c.icon, type: c.type, parentId: mapId(c.parentId) })
    newIdByOldId.set(c.id, created.id)
    if (c.hidden) await api.categories.toggleHidden(created.id)
    if (c.annualized) await api.categories.toggleAnnualized(created.id)
  }
  for (const c of plan.toUpdate) {
    await api.categories.update(c.id, { name: c.name, icon: c.icon, type: c.type, parentId: mapId(c.parentId) })
  }
  await Promise.all([
    ...plan.transactionGroups.map(([categoryId, ids]) =>
      api.transactions.bulkUpdate({ ids, categoryIdProvided: true, categoryId: mapId(categoryId) })),
    ...plan.payeesToRestore.map(p =>
      api.payees.update(p.id, { name: p.name, pattern: p.pattern, amount: p.amount, categoryId: mapId(p.categoryId) }))
  ])
}

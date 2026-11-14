import { formatAmount } from './format'

/**
 * Calculs d'affichage sur les comptes, partagés entre la sidebar (App.vue),
 * le tableau de bord et la page Comptes, pour ne pas les dupliquer.
 */

const ACCOUNT_TYPE_LABELS = {
  CHECKING: 'Courant',
  SAVINGS: 'Épargne',
  CASH: 'Espèces',
  CREDIT_CARD: 'Carte',
  INVESTMENT: 'Placement'
}

export function accountTypeLabel(type) {
  return ACCOUNT_TYPE_LABELS[type] || type
}

function displayRank(account) {
  if (account.archived) return 2
  return account.type === 'CHECKING' ? 0 : 1
}

/** Comptes courants en tête, comptes archivés en fin de liste, le reste dans l'ordre reçu de l'API (tri stable). */
export function sortAccountsForDisplay(accounts) {
  return [...accounts].sort((a, b) => displayRank(a) - displayRank(b))
}

/**
 * Comptes non archivés : les seuls proposés pour une NOUVELLE transaction
 * (saisie, virement, import), le backend refusant d'en ajouter sur un compte
 * archivé (voir ServiceCompte#ensureNotArchived). Les filtres de consultation
 * gardent, eux, tous les comptes pour retrouver l'historique.
 */
export function activeAccounts(accounts) {
  return accounts.filter(a => !a.archived)
}

/** Libellé d'option de liste déroulante, marqué « (archivé) » le cas échéant. */
export function accountOptionLabel(account) {
  return account.archived ? `${account.name} (archivé)` : account.name
}

/** Somme de tous les soldes (une carte de crédit en négatif diminue le total : patrimoine NET). */
export function computeNetWorth(accounts, balances) {
  return accounts.reduce((sum, a) => sum + Number(balances[a.id] ?? 0), 0)
}

/** « 1 compte », « 3 comptes ». */
export function accountCountLabel(count) {
  return `${count} ${count > 1 ? 'comptes' : 'compte'}`
}

/** Classe CSS d'un montant selon son signe (zéro compté comme positif). */
export function amountToneClass(value) {
  return Number(value) >= 0 ? 'positive' : 'negative'
}

/**
 * Lignes « compte + solde » prêtes à afficher (sidebar, tableau de bord) :
 * solde, classe de signe et montant formaté calculés une seule fois par
 * compte, plutôt que dans le template à chaque rendu.
 */
export function accountBalanceRows(accounts, balances) {
  return accounts.map(account => {
    const balance = Number(balances[account.id] ?? 0)
    return {
      account,
      typeClass: account.type.toLowerCase(),
      balanceClass: amountToneClass(balance),
      formattedBalance: formatAmount(balance, account.currency)
    }
  })
}

/**
 * Part de chaque compte dans le patrimoine, pour la barre de répartition :
 * seuls les soldes positifs ont une part (un découvert ne se représente pas
 * comme une fraction d'un total), du plus gros au plus petit.
 */
export function computeAllocation(accounts, balances) {
  const positives = accounts
    .map(a => ({ id: a.id, name: a.name, type: a.type, balance: Number(balances[a.id] ?? 0) }))
    .filter(a => a.balance > 0)
  const total = positives.reduce((sum, a) => sum + a.balance, 0)
  if (!total) return []
  return positives
    .map(a => ({ ...a, share: a.balance / total }))
    .sort((a, b) => b.balance - a.balance)
}

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

/**
 * Comptes à solde négatif (découvert, carte de crédit), du plus endetté au
 * moins endetté : absents de la barre de répartition, ils sont listés à part
 * pour expliquer l'écart entre la barre et le patrimoine net.
 */
export function computeDebts(accounts, balances) {
  return accounts
    .map(a => ({ id: a.id, name: a.name, type: a.type, currency: a.currency, balance: Number(balances[a.id] ?? 0) }))
    .filter(a => a.balance < 0)
    .sort((a, b) => a.balance - b.balance)
}

/**
 * Message expliquant pourquoi un compte ne peut pas être supprimé (il
 * contient des transactions, voir ServiceCompte#delete), ou null s'il peut
 * l'être. Vérifié avant de demander confirmation : sinon l'utilisateur
 * confirmait une suppression forcément refusée par le serveur.
 */
export function accountDeletionBlocker(account, transactions) {
  let count = 0
  for (const t of transactions) if (t.accountId === account.id) count++
  if (!count) return null
  const plural = count > 1 ? 's' : ''
  return `Le compte « ${account.name} » contient ${count} transaction${plural} : archivez-le plutôt que de le supprimer, pour conserver son historique.`
}

/** Payload de recréation d'un compte supprimé (bouton « Annuler »), mêmes champs que CompteDTO. */
export function accountPayloadFrom(account) {
  const { name, type, initialBalance, currency, notes, archived } = account
  return { name, type, initialBalance, currency, notes, archived }
}

/**
 * Erreur du champ « Nom » d'un compte, vide si valide : obligatoire, et
 * différent (casse ignorée) de celui d'un autre compte, comme le vérifie
 * ServiceCompte#ensureUniqueName. Un compte déjà homonyme garde son nom.
 */
export function accountNameError(name, accounts, currentAccount = null) {
  const trimmed = (name || '').trim()
  if (!trimmed) return 'Saisissez le nom du compte.'
  const lower = trimmed.toLowerCase()
  if (currentAccount && currentAccount.name.toLowerCase() === lower) return ''
  const taken = accounts.some(a => a.id !== currentAccount?.id && a.name.toLowerCase() === lower)
  return taken ? `Un compte « ${trimmed} » existe déjà.` : ''
}

/**
 * Ids des transactions en attente du compte jusqu'à `isoDate` incluse :
 * celles que le rapprochement va valider (voir
 * ServiceRapprochement#validatePendingUpTo), relevées avant la validation
 * pour pouvoir l'annuler. L'autre côté d'un virement suit toujours le même
 * statut (remis en attente avec lui par la modification en masse).
 */
export function pendingIdsUpTo(transactions, accountId, isoDate) {
  return transactions
    .filter(t => t.accountId === accountId && t.validated === false && t.date <= isoDate)
    .map(t => t.id)
}

/**
 * Solde du compte juste après chaque transaction (id -> solde), dans l'ordre
 * chronologique (date, puis id : ordre de saisie le même jour), à partir du
 * solde initial du compte. Calculé en centimes pour ne pas accumuler les
 * erreurs d'arrondi des nombres à virgule sur un long historique.
 */
export function runningBalanceById(transactions, account) {
  const own = transactions
    .filter(t => t.accountId === account.id)
    .sort((a, b) => (a.date === b.date ? a.id - b.id : a.date < b.date ? -1 : 1))
  const balances = new Map()
  let cents = Math.round(Number(account.initialBalance ?? 0) * 100)
  for (const t of own) {
    cents += Math.round(Number(t.amount) * 100)
    balances.set(t.id, cents / 100)
  }
  return balances
}

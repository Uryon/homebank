import { markRaw, reactive } from 'vue'
import { AccountsAPI, CategoriesAPI, PayeesAPI, TransactionsAPI } from './api'

/**
 * Cache reactif partage entre toutes les pages, pour eviter de refaire les
 * memes appels API (comptes, categories, tiers, transactions) a chaque
 * navigation. Precharge au demarrage de l'app (voir App.vue) : quand
 * l'utilisateur clique sur "Transactions", les donnees sont deja la et
 * s'affichent instantanement, au lieu d'attendre une nouvelle requete.
 *
 * Pattern "stale-while-revalidate" : on affiche tout de suite ce qui est en
 * cache, puis on relance un fetch en arriere-plan pour rester a jour (au cas
 * ou une donnee a change sur une autre page depuis le prechargement).
 */

const state = reactive({
  accounts: [],
  balances: {},
  categories: [],
  payees: [],
  transactions: [],
  loaded: {
    accounts: false,
    categories: false,
    payees: false,
    transactions: false
  },
  /**
   * Dernier chargement complet en échec (serveur arrêté, réseau coupé...) :
   * affiché par App.vue sous forme de bandeau avec « Réessayer », pour ne pas
   * laisser les pages afficher des états vides trompeurs (« aucun compte »).
   */
  loadFailed: false
})

/** Met à jour loadFailed selon l'issue d'un chargement complet, sans changer son résultat. */
function trackLoad(promise) {
  return promise.then(
    result => {
      state.loadFailed = false
      return result
    },
    error => {
      state.loadFailed = true
      throw error
    }
  )
}

let prefetchPromise = null

/**
 * « Dernier lancé gagne », par ressource. Deux chargements d'une même
 * ressource peuvent se croiser (ex : revalidation de fond refreshAll au
 * montage d'une page, puis refreshTransactions juste après une suppression) :
 * sans ce garde-fou, la réponse la plus ANCIENNE arrivée en dernier écrasait
 * l'état frais (une transaction supprimée réapparaissait). Une réponse
 * dépassée n'est jamais appliquée, et son appelant attend la plus récente,
 * pour que l'état soit à jour quand sa promesse se résout.
 */
const latestLoad = {}

/** Instant (Date.now()) du dernier chargement appliqué de chaque ressource, voir revalidate. */
const loadedAt = {}

function loadLatest(key, fetcher, apply) {
  const entry = { promise: null, pending: true }
  latestLoad[key] = entry
  const superseded = () => latestLoad[key] !== entry
  entry.promise = fetcher().then(
    data => {
      entry.pending = false
      if (superseded()) return latestLoad[key].promise
      apply(data)
      loadedAt[key] = Date.now()
    },
    error => {
      entry.pending = false
      // Une requête dépassée qui échoue n'a plus d'importance : seule
      // l'issue de la plus récente compte.
      if (superseded()) return latestLoad[key].promise
      throw error
    }
  )
  return entry.promise
}

/**
 * Liste jamais modifiée sur place, toujours remplacée : sans suivi réactif de
 * chacune de ses milliers de lignes, les filtres, tris et totaux qui la
 * parcourent lisent directement les objets. Remplacer la liste suffit à
 * prévenir les pages.
 */
function setTransactions(transactions) {
  state.transactions = markRaw(transactions)
}

/** Comptes et soldes toujours rechargés ensemble : la sidebar (App.vue) et
 * la page Comptes affichent les deux côte à côte. */
function loadAccounts() {
  return loadLatest('accounts', () => Promise.all([AccountsAPI.list(), AccountsAPI.balances()]), ([accounts, balances]) => {
    state.accounts = accounts
    state.balances = balances
    state.loaded.accounts = true
  })
}

function loadCategories() {
  return loadLatest('categories', () => CategoriesAPI.list(), categories => {
    state.categories = categories
    state.loaded.categories = true
  })
}

function loadPayees() {
  return loadLatest('payees', () => PayeesAPI.list(), payees => {
    state.payees = payees
    state.loaded.payees = true
  })
}

function loadTransactions() {
  return loadLatest('transactions', () => TransactionsAPI.list(), transactions => {
    setTransactions(transactions)
    state.loaded.transactions = true
  })
}

/**
 * Applique une transaction renvoyée par le serveur sans retélécharger toute la
 * liste : réservé aux changements sans effet sur les soldes (validée,
 * périodique, exceptionnelle). Les deux côtés d'un virement partagent toujours
 * le même statut de validation. Un chargement encore en cours a pu lire
 * l'ancienne valeur : il est remplacé par un nouveau.
 */
function applyTransactionUpdate(updated) {
  const twinId = updated.transferTransactionId
  setTransactions(state.transactions.map(t => {
    if (t.id === updated.id) return updated
    return t.id === twinId ? { ...t, validated: updated.validated } : t
  }))
  if (latestLoad.transactions?.pending) loadTransactions().catch(() => {})
}

/**
 * Affichage immédiat d'une création ou modification confirmée par le serveur :
 * la ligne change sans attendre le rechargement complet, que l'appelant lance
 * ensuite (soldes, jumelle d'un virement).
 */
function showSavedTransaction(saved) {
  const known = state.transactions.some(t => t.id === saved.id)
  setTransactions(known
    ? state.transactions.map(t => (t.id === saved.id ? saved : t))
    : [saved, ...state.transactions])
}

/** Pendant de showSavedTransaction pour une suppression, jumelle de virement comprise. */
function hideDeletedTransactions(ids) {
  const deleted = new Set(ids)
  for (const t of state.transactions) {
    if (deleted.has(t.id) && t.transferTransactionId != null) deleted.add(t.transferTransactionId)
  }
  setTransactions(state.transactions.filter(t => !deleted.has(t.id)))
}

/**
 * Recharge transactions ET soldes ensemble : toute création/modification/
 * suppression de transaction (page Transactions, doublons, suggestions
 * périodiques, virement, import CSV/QIF...) change potentiellement le solde
 * des comptes affiché dans la sidebar et sur la page Comptes. Exportée sous
 * le nom `refreshTransactions` pour que tous les appelants existants
 * profitent du rafraîchissement des soldes sans modification supplémentaire.
 */
async function loadTransactionsAndBalances() {
  await Promise.all([loadTransactions(), loadAccounts()])
}

/**
 * Lance le prechargement de toutes les donnees en parallele. Appelee une
 * seule fois au demarrage de l'app (App.vue). Les appels suivants renvoient
 * la meme promesse tant qu'aucun reset n'a ete demande, pour ne jamais
 * dupliquer les requetes.
 */
function prefetch() {
  if (!prefetchPromise) {
    prefetchPromise = trackLoad(Promise.all([
      loadAccounts(),
      loadCategories(),
      loadPayees(),
      loadTransactions()
    ])).catch(e => {
      // Sans ce reset, un premier prefetch en echec (backend pas encore
      // demarre, reseau coupe...) laisserait prefetchPromise memoise sur une
      // promesse rejetee pour toujours : ensureLoaded() la renverrait telle
      // quelle a chaque nouvel appel (changement de page...) sans jamais
      // retenter le chargement, jusqu'au rechargement complet de la SPA.
      prefetchPromise = null
      throw e
    })
  }
  return prefetchPromise
}

/**
 * Garantit que le cache a au moins ete charge une fois (attend le
 * prechargement en cours s'il y en a un, ou en demarre un sinon). A utiliser
 * au montage d'une page pour etre sur d'avoir des donnees avant affichage,
 * sans jamais refaire deux fois le meme appel.
 */
function ensureLoaded() {
  return prefetch()
}

const LOADERS = {
  accounts: loadAccounts,
  categories: loadCategories,
  payees: loadPayees,
  transactions: loadTransactions
}

/** Recharge tout le cache, sans condition (ex : « Réessayer » après une panne du serveur). */
function refreshAll() {
  return trackLoad(Promise.all(Object.values(LOADERS).map(load => load())))
}

/**
 * Données considérées à jour pendant ce délai après leur chargement : la
 * revalidation au montage d'une page ne les redemande pas. Sans cela, ouvrir
 * l'appli directement sur Transactions téléchargeait tout deux fois à 300 ms
 * d'intervalle (préchargement, puis revalidation de la page) — plusieurs Mo
 * de transactions sur un gros historique.
 */
export const FRESH_FOR_MS = 10000

/**
 * Revalidation de fond au montage d'une page (stale-while-revalidate) :
 * recharge seulement les ressources chargées il y a plus de FRESH_FOR_MS.
 * Toute modification passe de toute façon par un refresh explicite.
 */
function revalidate(now = Date.now()) {
  const stale = Object.keys(LOADERS).filter(key => !(now - (loadedAt[key] ?? -Infinity) < FRESH_FOR_MS))
  if (!stale.length) return Promise.resolve()
  return trackLoad(Promise.all(stale.map(key => LOADERS[key]())))
}

export const dataStore = {
  state,
  prefetch,
  ensureLoaded,
  refreshAll,
  revalidate,
  refreshAccounts: loadAccounts,
  refreshCategories: loadCategories,
  refreshPayees: loadPayees,
  refreshTransactions: loadTransactionsAndBalances,
  applyTransactionUpdate,
  showSavedTransaction,
  hideDeletedTransactions
}

import { reactive } from 'vue'
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
  }
})

let prefetchPromise = null

/** Comptes et soldes toujours rechargés ensemble : la sidebar (App.vue) et
 * la page Comptes affichent les deux côte à côte. */
async function loadAccounts() {
  const [accounts, balances] = await Promise.all([AccountsAPI.list(), AccountsAPI.balances()])
  state.accounts = accounts
  state.balances = balances
  state.loaded.accounts = true
}

async function loadCategories() {
  state.categories = await CategoriesAPI.list()
  state.loaded.categories = true
}

async function loadPayees() {
  state.payees = await PayeesAPI.list()
  state.loaded.payees = true
}

async function loadTransactions() {
  state.transactions = await TransactionsAPI.list()
  state.loaded.transactions = true
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
    prefetchPromise = Promise.all([
      loadAccounts(),
      loadCategories(),
      loadPayees(),
      loadTransactions()
    ]).catch(e => {
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

/** Revalide tout le cache en arriere-plan (sans bloquer l'affichage courant). */
function refreshAll() {
  return Promise.all([
    loadAccounts(),
    loadCategories(),
    loadPayees(),
    loadTransactions()
  ])
}

export const dataStore = {
  state,
  prefetch,
  ensureLoaded,
  refreshAll,
  refreshAccounts: loadAccounts,
  refreshCategories: loadCategories,
  refreshPayees: loadPayees,
  refreshTransactions: loadTransactionsAndBalances
}

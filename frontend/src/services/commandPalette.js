import { normalizeSearch } from './search'
import { flattenCategories, transactionSearchForCategory } from './categories'
import { accountOptionLabel } from './accounts'
import { routeAction } from './routeActions'

/**
 * Logique de la palette de commandes (Ctrl+K), séparée du composant pour
 * être testable : construction des commandes et filtrage par texte.
 *
 * Une commande : { id, group, label, hint?, icon, keywords?, to?, action? }
 * - `to`     : route à ouvrir (string ou objet vue-router) ;
 * - `action` : identifiant d'action interprété par le composant (thème...).
 */

export const PAGE_COMMANDS = [
  { id: 'page-dashboard', group: 'Pages', hint: 'Alt+1', label: 'Tableau de bord', icon: 'dashboard', to: '/' },
  { id: 'page-transactions', group: 'Pages', hint: 'Alt+2', label: 'Transactions', icon: 'transactions', to: '/transactions' },
  { id: 'page-periodic', group: 'Pages', label: 'Transactions périodiques', icon: 'refresh', to: '/transactions/periodiques', keywords: 'suggestions recurrent' },
  { id: 'page-duplicates', group: 'Pages', label: 'Doublons', icon: 'transactions', to: '/transactions/doublons' },
  { id: 'page-accounts', group: 'Pages', hint: 'Alt+3', label: 'Comptes', icon: 'accounts', to: '/accounts' },
  { id: 'page-categories', group: 'Pages', hint: 'Alt+4', label: 'Catégories', icon: 'categories', to: '/categories' },
  { id: 'page-payees', group: 'Pages', hint: 'Alt+5', label: 'Tiers', icon: 'payees', to: '/payees' },
  { id: 'page-loans', group: 'Pages', hint: 'Alt+6', label: 'Prêts', icon: 'loans', to: '/loans', keywords: 'emprunt credit' },
  { id: 'page-import', group: 'Pages', hint: 'Alt+7', label: 'Importer un relevé', icon: 'import', to: '/import', keywords: 'csv qif banque' }
]

export const ACTION_COMMANDS = [
  { id: 'action-new-transaction', group: 'Actions', label: 'Nouvelle transaction', hint: 'N', icon: 'plus', to: { path: '/transactions', query: { new: '1' } }, keywords: 'ajouter creer depense revenu' },
  { id: 'action-uncategorized', group: 'Actions', label: 'Transactions sans catégorie', icon: 'filter', to: { path: '/transactions', query: { uncategorized: '1' } }, keywords: 'categoriser trier' },
  { id: 'action-pending', group: 'Actions', label: 'Transactions à traiter', icon: 'check', to: { path: '/transactions', query: { pending: '1' } }, keywords: 'valider rapprochement non validees import' },
  { id: 'action-new-account', group: 'Actions', label: 'Nouveau compte', icon: 'accounts', to: routeAction('/accounts', 'new-account'), keywords: 'ajouter creer banque livret' },
  { id: 'action-transfer', group: 'Actions', label: 'Virement entre comptes', icon: 'transactions', to: routeAction('/accounts', 'transfer'), keywords: 'transfert epargne livret' },
  { id: 'action-new-category', group: 'Actions', label: 'Nouvelle catégorie', icon: 'categories', to: routeAction('/categories', 'new-category'), keywords: 'ajouter creer' },
  { id: 'action-new-payee', group: 'Actions', label: 'Nouveau tiers', icon: 'payees', to: routeAction('/payees', 'new-payee'), keywords: 'ajouter creer beneficiaire commercant' },
  { id: 'action-new-loan', group: 'Actions', label: 'Nouveau prêt', icon: 'loans', to: routeAction('/loans', 'new-loan'), keywords: 'ajouter creer emprunt credit' },
  { id: 'action-export-csv', group: 'Actions', label: 'Exporter les transactions en CSV', icon: 'transactions', to: { path: '/transactions', query: { export: 'csv' } }, keywords: 'telecharger sauvegarde homebank excel tableur' },
  { id: 'action-export-pdf', group: 'Actions', label: 'Exporter le tableau de bord en PDF', icon: 'dashboard', to: routeAction('/', 'export-pdf'), keywords: 'imprimer telecharger rapport' },
  { id: 'action-shortcuts', group: 'Actions', label: 'Raccourcis clavier', hint: '?', icon: 'help', action: 'shortcuts', keywords: 'aide clavier touches' },
  { id: 'action-theme-light', group: 'Actions', label: 'Thème clair', icon: 'sun', action: 'theme:light', keywords: 'apparence mode jour' },
  { id: 'action-theme-dark', group: 'Actions', label: 'Thème sombre', icon: 'moon', action: 'theme:dark', keywords: 'apparence mode nuit' },
  { id: 'action-theme-system', group: 'Actions', label: 'Thème du système', icon: 'monitor', action: 'theme:system', keywords: 'apparence automatique' }
]

/** Index de recherche des commandes fixes, normalisé une seule fois. */
export function indexCommands(commands) {
  return commands.map(command => ({
    command,
    haystack: normalizeSearch(`${command.label} ${command.keywords || ''}`)
  }))
}

/** Index des tiers (potentiellement plusieurs centaines) : à calculer dans un computed, pas à chaque frappe. */
export function indexPayees(payees) {
  return payees.map(p => ({
    command: { id: `payee-${p.id}`, group: 'Tiers', label: p.name, icon: 'payees', to: { path: '/payees', query: { editId: String(p.id) } } },
    haystack: normalizeSearch(p.name)
  }))
}

/**
 * Index des comptes (archivés compris, signalés dans le libellé) : chacun
 * ouvre la liste des transactions filtrée sur ce compte, comme le nom du
 * compte cliqué sur la page Comptes.
 */
export function indexAccounts(accounts) {
  return accounts.map(a => ({
    command: { id: `account-${a.id}`, group: 'Comptes', label: accountOptionLabel(a), icon: 'accounts', to: { path: '/transactions', query: { account: String(a.id) } } },
    haystack: normalizeSearch(a.name)
  }))
}

/**
 * Index des catégories visibles : chacune ouvre ses transactions via la
 * recherche de la page Transactions, qui compare le libellé « Parent : Enfant »
 * de flattenCategories (voir transactionSearchForCategory).
 */
export function indexCategories(categories) {
  const byId = new Map(categories.map(c => [c.id, c]))
  return flattenCategories(categories)
    .filter(entry => !entry.hidden)
    .map(entry => {
      const category = byId.get(entry.id)
      const parent = category.parentId ? byId.get(category.parentId) : null
      const search = transactionSearchForCategory(entry)
      return {
        command: { id: `category-${entry.id}`, group: 'Catégories', label: entry.label, icon: 'categories', to: { path: '/transactions', query: { search } } },
        haystack: normalizeSearch(parent ? `${parent.name} ${category.name}` : category.name)
      }
    })
}

/**
 * Filtre un index : tous les mots saisis doivent apparaître (ordre libre).
 * Les libellés qui commencent par la saisie passent devant.
 */
export function filterIndex(index, query, limit) {
  const words = normalizeSearch(query).split(/\s+/).filter(Boolean)
  if (!words.length) return index.slice(0, limit).map(entry => entry.command)
  const matches = index.filter(entry => words.every(w => entry.haystack.includes(w)))
  const startsWith = matches.filter(entry => entry.haystack.startsWith(words[0]))
  const others = matches.filter(entry => !entry.haystack.startsWith(words[0]))
  return [...startsWith, ...others].slice(0, limit).map(entry => entry.command)
}

/**
 * Découpe un libellé en segments pour surligner les mots saisis :
 * [{ text, match }]. Insensible à la casse et aux accents, comme le filtrage
 * (« energie » surligne « Énergie »). Toutes les occurrences de chaque mot
 * sont surlignées ; les plages qui se chevauchent sont fusionnées.
 */
export function highlightSegments(label, query) {
  const words = normalizeSearch(query).split(/\s+/).filter(Boolean)
  if (!label || !words.length) return [{ text: label || '', match: false }]

  // Version normalisée du libellé + position d'origine de chaque caractère
  // normalisé (un caractère d'origine peut en produire plusieurs).
  let normalized = ''
  const origins = []
  for (let i = 0; i < label.length; i++) {
    const part = normalizeSearch(label[i]) || (label[i].trim() ? '' : ' ')
    for (const ch of part) {
      normalized += ch
      origins.push(i)
    }
  }

  const marked = new Array(label.length).fill(false)
  for (const word of words) {
    let from = normalized.indexOf(word)
    while (from !== -1) {
      for (let k = from; k < from + word.length; k++) marked[origins[k]] = true
      from = normalized.indexOf(word, from + word.length)
    }
  }

  const segments = []
  for (let i = 0; i < label.length; i++) {
    const last = segments[segments.length - 1]
    if (last && last.match === marked[i]) last.text += label[i]
    else segments.push({ text: label[i], match: marked[i] })
  }
  return segments
}

/** Commande « Rechercher dans les transactions » proposée dès qu'un texte est saisi. */
export function searchTransactionsCommand(query) {
  const text = query.trim()
  if (!text) return null
  return {
    id: 'search-transactions',
    group: 'Recherche',
    label: `Rechercher « ${text} » dans les transactions`,
    icon: 'search',
    to: { path: '/transactions', query: { search: text } }
  }
}

/** Résultats complets de la palette, dans l'ordre d'affichage. */
export function buildResults({ staticIndex, payeeIndex, categoryIndex = [], accountIndex = [], query }) {
  const hasQuery = query.trim().length > 0
  const results = [
    ...filterIndex(staticIndex, query, hasQuery ? 6 : staticIndex.length),
    ...(hasQuery ? filterIndex(accountIndex, query, 4) : []),
    ...(hasQuery ? filterIndex(categoryIndex, query, 5) : []),
    ...(hasQuery ? filterIndex(payeeIndex, query, 6) : [])
  ]
  const search = searchTransactionsCommand(query)
  if (search) results.push(search)
  return results
}

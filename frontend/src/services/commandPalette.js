import { normalizeSearch } from './search'

/**
 * Logique de la palette de commandes (Ctrl+K), séparée du composant pour
 * être testable : construction des commandes et filtrage par texte.
 *
 * Une commande : { id, group, label, hint?, icon, keywords?, to?, action? }
 * - `to`     : route à ouvrir (string ou objet vue-router) ;
 * - `action` : identifiant d'action interprété par le composant (thème...).
 */

export const PAGE_COMMANDS = [
  { id: 'page-dashboard', group: 'Pages', label: 'Tableau de bord', icon: 'dashboard', to: '/' },
  { id: 'page-transactions', group: 'Pages', label: 'Transactions', icon: 'transactions', to: '/transactions' },
  { id: 'page-periodic', group: 'Pages', label: 'Transactions périodiques', icon: 'refresh', to: '/transactions/periodiques', keywords: 'suggestions recurrent' },
  { id: 'page-duplicates', group: 'Pages', label: 'Doublons', icon: 'transactions', to: '/transactions/doublons' },
  { id: 'page-accounts', group: 'Pages', label: 'Comptes', icon: 'accounts', to: '/accounts' },
  { id: 'page-categories', group: 'Pages', label: 'Catégories', icon: 'categories', to: '/categories' },
  { id: 'page-payees', group: 'Pages', label: 'Tiers', icon: 'payees', to: '/payees' },
  { id: 'page-loans', group: 'Pages', label: 'Prêts', icon: 'loans', to: '/loans', keywords: 'emprunt credit' },
  { id: 'page-import', group: 'Pages', label: 'Importer un relevé', icon: 'import', to: '/import', keywords: 'csv qif banque' }
]

export const ACTION_COMMANDS = [
  { id: 'action-new-transaction', group: 'Actions', label: 'Nouvelle transaction', hint: 'N', icon: 'plus', to: { path: '/transactions', query: { new: '1' } }, keywords: 'ajouter creer depense revenu' },
  { id: 'action-uncategorized', group: 'Actions', label: 'Transactions sans catégorie', icon: 'filter', to: { path: '/transactions', query: { uncategorized: '1' } }, keywords: 'categoriser trier' },
  { id: 'action-pending', group: 'Actions', label: 'Transactions à traiter', icon: 'check', to: { path: '/transactions', query: { pending: '1' } }, keywords: 'valider rapprochement non validees import' },
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
export function buildResults({ staticIndex, payeeIndex, query }) {
  const hasQuery = query.trim().length > 0
  const results = [
    ...filterIndex(staticIndex, query, hasQuery ? 6 : staticIndex.length),
    ...(hasQuery ? filterIndex(payeeIndex, query, 6) : [])
  ]
  const search = searchTransactionsCommand(query)
  if (search) results.push(search)
  return results
}

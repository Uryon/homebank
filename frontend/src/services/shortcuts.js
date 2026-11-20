/**
 * Liste des raccourcis clavier de l'application, affichée par
 * ShortcutsDialog.vue (touche « ? », bouton de la barre latérale, palette).
 * Source unique : à tenir à jour avec les gestionnaires réels (App.vue pour
 * les raccourcis globaux, TransactionRow/Categories/Payees pour les lignes,
 * les autocompletes, la directive v-grid-nav et v-dialog).
 *
 * `keys` : liste de combinaisons alternatives, chacune étant une liste de
 * touches à presser ensemble — ex. [['Ctrl', 'K']] ou [['↑'], ['↓']].
 */
export const SHORTCUT_GROUPS = [
  {
    title: 'Partout',
    items: [
      { keys: [['Ctrl', 'K']], label: 'Ouvrir ou fermer la palette de commandes (pages, actions, tiers)' },
      { keys: [['/']], label: 'Rechercher dans la page (Transactions, Tiers, Catégories), sinon ouvrir la palette' },
      { keys: [['N']], label: 'Nouvelle transaction' },
      { keys: [['Alt', '1…7']], label: "Aller à une page du menu, dans l'ordre (Alt+1 : Tableau de bord … Alt+7 : Importer)" },
      { keys: [['Ctrl', 'Z']], label: 'Annuler la dernière action (tant que le bandeau « Annuler » est affiché)' },
      { keys: [['?']], label: 'Afficher cette aide' }
    ]
  },
  {
    title: 'Listes : Transactions, Catégories, Tiers',
    items: [
      { keys: [['↑'], ['↓']], label: 'Ligne précédente ou suivante' },
      { keys: [['Début'], ['Fin']], label: 'Première ou dernière ligne' },
      { keys: [['Tab']], label: "Aller d'élément en élément (cases à cocher, boutons de la ligne)" },
      { keys: [['Entrée']], label: 'Modifier la ligne, puis enregistrer la modification' },
      { keys: [['Échap']], label: 'Annuler la modification en cours' },
      { keys: [['Espace']], label: 'Transactions : sélectionner ou désélectionner la ligne' },
      { keys: [['Maj', 'Espace'], ['Maj', 'clic']], label: 'Transactions : sélectionner toute la plage depuis la dernière ligne cochée' },
      { keys: [['V']], label: 'Transactions : valider ou remettre en attente' },
      { keys: [['D']], label: 'Transactions : dupliquer la ligne (nouvelle transaction pré-remplie)' },
      { keys: [['Suppr']], label: 'Transactions : supprimer la ligne (après confirmation)' },
      { keys: [['Échap']], label: 'Transactions : vider la sélection multiple' }
    ]
  },
  {
    title: 'Champs Tiers et Catégorie',
    items: [
      { keys: [['↑'], ['↓']], label: 'Parcourir les suggestions' },
      { keys: [['Entrée']], label: 'Choisir la suggestion surlignée' },
      { keys: [['Échap']], label: 'Fermer la liste de suggestions' }
    ]
  },
  {
    title: 'Tableau de bord',
    items: [
      { keys: [['←'], ['→']], label: 'Mois précédent ou suivant (hors tableaux mensuels)' },
      { keys: [['Flèches']], label: "Tableaux mensuels : passer d'une case à l'autre (affiche le détail des transactions)" },
      { keys: [['Début'], ['Fin']], label: 'Première ou dernière case de la ligne' },
      { keys: [['Échap']], label: 'Masquer le détail' }
    ]
  },
  {
    title: 'Fenêtres et palette',
    items: [
      { keys: [['↑'], ['↓']], label: 'Palette : parcourir les résultats' },
      { keys: [['Entrée']], label: 'Palette : ouvrir le résultat choisi' },
      { keys: [['Tab'], ['Maj', 'Tab']], label: "Fenêtre : passer d'un champ à l'autre" },
      { keys: [['Échap']], label: 'Fermer la fenêtre ou la palette' }
    ]
  }
]

/** Vrai sur macOS / iOS, où la touche de commande remplace Ctrl. */
export function isApplePlatform(platform = globalThis.navigator?.platform || '') {
  return /mac|iphone|ipad/i.test(platform)
}

/** Libellé affiché d'une touche : « Ctrl » devient « ⌘ » sur Mac (le gestionnaire accepte les deux). */
export function displayKey(key, apple = isApplePlatform()) {
  return apple && key === 'Ctrl' ? '⌘' : key
}

/**
 * Raccourci Alt+1…9 de navigation entre pages : index (0 pour Alt+1) de la
 * page visée dans l'ordre du menu, ou null. Lit event.code (touche physique)
 * plutôt que event.key : en AZERTY, la rangée du haut donne « & é " ' … »
 * sans Maj, et event.key ne vaudrait pas « 1 ».
 */
export function pageShortcutIndex(event, pageCount) {
  if (!event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return null
  const match = /^(?:Digit|Numpad)([1-9])$/.exec(event.code || '')
  if (!match) return null
  const index = Number(match[1]) - 1
  return index < pageCount ? index : null
}

/** Éléments qui gèrent eux-mêmes les flèches : saisie, listes déroulantes, tableaux navigables (v-grid-nav). */
const ARROW_OWNER_SELECTOR = 'input, textarea, select, [contenteditable="true"], table, [role="slider"], [role="listbox"]'

/**
 * Raccourci ← / → du tableau de bord : -1 (mois précédent), +1 (mois
 * suivant) ou null. Ignoré avec un modificateur (Alt+← = page précédente du
 * navigateur) et quand le focus est dans un élément qui utilise déjà les
 * flèches (champ, tableau mensuel parcouru case par case…).
 */
export function monthStepFromKey(event) {
  if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return null
  const step = { ArrowLeft: -1, ArrowRight: 1 }[event.key]
  if (!step) return null
  if (event.target?.closest?.(ARROW_OWNER_SELECTOR)) return null
  return step
}

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
      { keys: [['?']], label: 'Afficher cette aide' }
    ]
  },
  {
    title: 'Listes : Transactions, Catégories, Tiers',
    items: [
      { keys: [['Tab']], label: 'Aller de ligne en ligne' },
      { keys: [['Entrée']], label: 'Modifier la ligne, puis enregistrer la modification' },
      { keys: [['Échap']], label: 'Annuler la modification en cours' },
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
    title: 'Tableau de bord : tableaux mensuels',
    items: [
      { keys: [['Flèches']], label: "Passer d'une case à l'autre (affiche le détail des transactions)" },
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

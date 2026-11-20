import { nextTick } from 'vue'

/**
 * Comportement clavier commun aux lignes éditables « en place » (tiers,
 * catégories) : la ligne est focusable (tabindex=0) et Entrée l'ouvre en
 * édition ; en édition, Entrée enregistre.
 */

/**
 * Entrée sur une ligne : enregistre si la ligne est en édition ; sinon ouvre
 * l'édition, mais seulement si c'est la ligne elle-même qui a le focus (une
 * case à cocher ou un bouton de la ligne garde son propre comportement).
 */
export function handleRowEnter(event, { isEditing, confirm, startEdit }) {
  if (isEditing) {
    event.preventDefault()
    confirm()
  } else if (event.target === event.currentTarget) {
    event.preventDefault()
    startEdit()
  }
}

/**
 * Après la fin d'une édition, rend le focus à la ligne (sélecteur CSS) si le
 * champ qui l'avait vient de disparaître, pour qu'un utilisateur clavier
 * reprenne là où il était plutôt qu'en haut de la page.
 */
export function restoreFocusToRow(selector) {
  nextTick(() => {
    if (document.activeElement && document.activeElement !== document.body) return
    document.querySelector(selector)?.focus({ preventScroll: true })
  })
}

/** Touches d'action d'une ligne de transaction (hors édition), voir transactionRowAction. */
const TRANSACTION_ROW_ACTIONS = {
  ' ': 'toggle-select',
  v: 'toggle-validated',
  V: 'toggle-validated',
  d: 'duplicate',
  D: 'duplicate',
  Delete: 'remove'
}

/**
 * Action déclenchée par une touche sur une ligne de transaction qui a le
 * focus (Espace : sélectionner, V : valider/dévalider, D : dupliquer,
 * Suppr : supprimer après confirmation), ou null. Seulement sur la ligne elle-même hors
 * édition, sans modificateur : une case à cocher ou un champ de la ligne
 * garde son propre comportement (Espace coche, V s'écrit).
 */
export function transactionRowAction(event, isEditing) {
  if (isEditing || event.target !== event.currentTarget) return null
  if (event.ctrlKey || event.metaKey || event.altKey) return null
  return TRANSACTION_ROW_ACTIONS[event.key] ?? null
}

/**
 * Id de la ligne qui reprend le focus quand la ligne `id` disparaît
 * (suppression) : la suivante dans l'ordre affiché, sinon la précédente,
 * sinon null. Évite qu'un utilisateur clavier retombe en haut de la page.
 */
export function neighborRowId(items, id) {
  const index = items.findIndex(item => item.id === id)
  if (index === -1) return null
  return (items[index + 1] ?? items[index - 1])?.id ?? null
}

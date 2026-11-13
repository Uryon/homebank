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

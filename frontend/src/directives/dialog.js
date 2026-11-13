import { getFocusableElements, trapTabKey } from '../services/focusTrap'

/**
 * Directive v-dialog, à poser sur l'élément .modal (role="dialog") :
 *   <div class="modal" role="dialog" aria-modal="true" v-dialog="fermer">
 * Apporte le comportement attendu d'une fenêtre modale (WAI-ARIA, motif « Dialog ») :
 * - focus placé dans la fenêtre à l'ouverture (premier champ de saisie, sinon
 *   premier élément focusable, ou un élément [autofocus]) ;
 * - Tab / Maj+Tab bouclent à l'intérieur de la fenêtre ;
 * - Échap ferme la fenêtre (appel de la fonction passée en valeur) ;
 * - focus rendu à l'élément qui l'avait avant l'ouverture ;
 * - titre (premier h2) relié à la fenêtre par aria-labelledby.
 */

let titleCounter = 0

function initialFocusTarget(el) {
  const explicit = el.querySelector('[autofocus]')
  if (explicit) return explicit
  const focusable = getFocusableElements(el)
  return focusable.find(node => node.matches('input, select, textarea')) || focusable[0] || null
}

function labelWithHeading(el) {
  if (el.hasAttribute('aria-labelledby') || el.hasAttribute('aria-label')) return
  const heading = el.querySelector('h2')
  if (!heading) return
  if (!heading.id) heading.id = `dialog-title-${++titleCounter}`
  el.setAttribute('aria-labelledby', heading.id)
}

export const vDialog = {
  mounted(el, binding) {
    const state = {
      previouslyFocused: document.activeElement,
      close: binding.value,
      onKeydown(event) {
        if (event.key === 'Escape') {
          // Une liste déroulante ouverte dans la fenêtre a déjà traité Échap
          // et stoppé sa propagation : on n'arrive ici que si rien d'autre ne l'a pris.
          event.stopPropagation()
          state.close?.()
        } else {
          trapTabKey(event, el)
        }
      }
    }
    el._vDialog = state
    labelWithHeading(el)
    el.addEventListener('keydown', state.onKeydown)
    // Le champ qui a le focus ne doit pas rester derrière la fenêtre.
    if (!el.contains(document.activeElement)) initialFocusTarget(el)?.focus()
  },

  updated(el, binding) {
    el._vDialog.close = binding.value
  },

  unmounted(el) {
    const state = el._vDialog
    el.removeEventListener('keydown', state.onKeydown)
    const previous = state.previouslyFocused
    if (previous?.isConnected && typeof previous.focus === 'function') previous.focus({ preventScroll: true })
  }
}

/**
 * Piégeage du focus dans une fenêtre modale (Tab / Maj+Tab bouclent sur ses
 * propres éléments), partagé par la directive v-dialog et FeedbackHost.vue.
 */

const FOCUSABLE_SELECTOR = [
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[href]',
  '[tabindex]:not([tabindex="-1"])'
].join(', ')

/** Éléments focusables et visibles du conteneur, dans l'ordre du document. */
export function getFocusableElements(container) {
  return [...container.querySelectorAll(FOCUSABLE_SELECTOR)]
    .filter(el => el.getClientRects().length > 0)
}

/**
 * Index de l'élément à focaliser pour boucler, ou -1 si le comportement
 * normal du navigateur convient (on n'est pas sur un bord de la liste).
 */
export function wrappedFocusIndex(count, currentIndex, backwards) {
  if (!count) return -1
  if (currentIndex === -1) return backwards ? count - 1 : 0
  if (backwards && currentIndex === 0) return count - 1
  if (!backwards && currentIndex === count - 1) return 0
  return -1
}

/** À appeler sur keydown : garde le focus dans `container` quand on appuie sur Tab. */
export function trapTabKey(event, container) {
  if (event.key !== 'Tab') return
  const focusable = getFocusableElements(container)
  const index = wrappedFocusIndex(focusable.length, focusable.indexOf(document.activeElement), event.shiftKey)
  if (index === -1) return
  event.preventDefault()
  focusable[index].focus()
}

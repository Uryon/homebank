/**
 * Directive v-row-nav, à poser sur le conteneur d'une liste dont les lignes
 * sont focusables (Transactions, Tiers, Catégories). Valeur : sélecteur CSS
 * des lignes, ex. v-row-nav="'tr[data-payee-id]'".
 *
 * Quand une ligne a le focus (la ligne elle-même, pas un champ de saisie à
 * l'intérieur — une ligne en édition garde ses flèches pour ses champs) :
 * - ↑ / ↓ : ligne précédente / suivante ;
 * - Début / Fin : première / dernière ligne affichée.
 * Complète Tab, qui passe aussi par les cases à cocher et boutons de chaque
 * ligne. Les lignes masquées ou non focusables (en édition) sont ignorées.
 * Le calcul de la ligne cible est dans nextRowIndex (testé).
 */

/** Index de la ligne cible après une touche, ou null si la touche ne déplace pas. */
export function nextRowIndex(key, current, count) {
  if (count === 0) return null
  switch (key) {
    case 'ArrowDown': return Math.min(current + 1, count - 1)
    case 'ArrowUp': return Math.max(current - 1, 0)
    case 'Home': return 0
    case 'End': return count - 1
    default: return null
  }
}

/** Lignes navigables : focusables (tabindex ≥ 0) et réellement affichées. */
function navigableRows(container, selector) {
  return [...container.querySelectorAll(selector)]
    .filter(row => row.tabIndex >= 0 && row.getClientRects().length > 0)
}

function onKeydown(event) {
  if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
  const container = event.currentTarget
  const selector = container._rowNavSelector
  const row = event.target
  if (!selector || !row.matches?.(selector)) return

  const rows = navigableRows(container, selector)
  const current = rows.indexOf(row)
  if (current === -1) return
  const target = nextRowIndex(event.key, current, rows.length)
  if (target === null) return

  event.preventDefault()
  rows[target].focus({ preventScroll: true })
  rows[target].scrollIntoView({ block: 'nearest' })
}

export const vRowNav = {
  mounted(el, binding) {
    el._rowNavSelector = binding.value
    el.addEventListener('keydown', onKeydown)
  },
  updated(el, binding) {
    el._rowNavSelector = binding.value
  },
  beforeUnmount(el) {
    el.removeEventListener('keydown', onKeydown)
  }
}

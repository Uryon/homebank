/**
 * Position d'un popover `position: fixed` ancré sous (ou au-dessus de) un
 * élément. Ouvert en dessous par défaut ; au-dessus quand la hauteur maximale
 * du popover ne tient pas sous l'élément ET qu'il y a plus de place au-dessus
 * (case en bas de l'écran). Renvoie { x, y, above } : avec `above`, `y` est
 * la distance au BAS de la fenêtre (propriété CSS `bottom`), sinon au haut
 * (`top`) — le popover reste ainsi collé à l'élément quelle que soit sa
 * hauteur réelle.
 */
export function popoverPlacement(rect, viewport, { width, maxHeight, gap = 6, margin = 8 }) {
  const x = Math.max(margin, Math.min(rect.left, viewport.width - width - margin))
  const spaceBelow = viewport.height - rect.bottom - gap
  const spaceAbove = rect.top - gap
  if (spaceBelow < maxHeight && spaceAbove > spaceBelow) {
    return { x, y: viewport.height - rect.top + gap, above: true }
  }
  return { x, y: rect.bottom + gap, above: false }
}

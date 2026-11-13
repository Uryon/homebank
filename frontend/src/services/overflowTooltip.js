/**
 * Infobulle « texte tronqué » : logique pure (sans DOM réel), utilisée par
 * components/OverflowTooltip.vue et testée dans overflowTooltip.test.js.
 *
 * Principe : tout élément affiché avec text-overflow: ellipsis dont le
 * contenu dépasse réellement (le « … » est visible) révèle son texte complet
 * au survol ou au focus. Un ancêtre portant data-tooltip fournit le texte à
 * afficher (ex : chemin complet « Parent › Sous-catégorie » d'une pastille).
 */

/** Tolérance d'un pixel : arrondis de sous-pixels sans troncature visible. */
const OVERFLOW_TOLERANCE = 1

export function isTruncated(el) {
  return el.scrollWidth > el.clientWidth + OVERFLOW_TOLERANCE
}

function hasEllipsis(el, getStyle) {
  return getStyle(el).textOverflow === 'ellipsis'
}

/** Vrai si el ou l'un de ses descendants affiche actuellement un « … ». */
function containsTruncatedText(el, getStyle) {
  if (hasEllipsis(el, getStyle) && isTruncated(el)) return true
  return [...(el.querySelectorAll?.('*') || [])].some(child => hasEllipsis(child, getStyle) && isTruncated(child))
}

/**
 * Remonte depuis l'élément survolé jusqu'à `boundary` (exclu) et renvoie
 * l'élément dont il faut afficher le texte complet, ou null s'il n'y a rien
 * de tronqué. Les éléments qui ont déjà un title natif sont ignorés (le
 * navigateur affiche déjà leur contenu : pas de double infobulle).
 */
export function findOverflowTarget(start, getStyle, boundary = null) {
  for (let el = start; el && el !== boundary; el = el.parentElement) {
    if (el.hasAttribute?.('title')) return null
    if (el.dataset?.tooltip && containsTruncatedText(el, getStyle)) return el
    if (hasEllipsis(el, getStyle) && isTruncated(el)) return el
  }
  return null
}

/** Texte complet à afficher : data-tooltip d'un ancêtre si présent, sinon le texte de l'élément. */
export function tooltipTextFor(el) {
  const holder = el.closest?.('[data-tooltip]')
  const raw = holder?.dataset.tooltip || el.textContent || ''
  return raw.replace(/\s+/g, ' ').trim()
}

/**
 * Position de l'infobulle (coordonnées viewport, position: fixed) : sous
 * l'élément, alignée sur son bord gauche ; au-dessus s'il n'y a pas la
 * place en bas ; toujours maintenue dans l'écran avec une marge.
 */
export function computeTooltipPosition(anchor, tip, viewport, { gap = 6, margin = 8 } = {}) {
  const fitsBelow = anchor.bottom + gap + tip.height <= viewport.height - margin
  const placement = fitsBelow || anchor.top - gap - tip.height < margin ? 'bottom' : 'top'
  const top = placement === 'bottom' ? anchor.bottom + gap : anchor.top - gap - tip.height
  const maxLeft = viewport.width - margin - tip.width
  const left = Math.max(margin, Math.min(anchor.left, maxLeft))
  return { top: Math.round(top), left: Math.round(left), placement }
}

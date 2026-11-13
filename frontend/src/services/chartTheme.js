/**
 * Couleurs et styles communs des graphiques Chart.js. Un canvas ne lit pas
 * les variables CSS : on les résout au moment du dessin (voir les variables
 * --chart-* de style.css), et les vues redessinent leurs graphiques quand
 * effectiveTheme (services/theme.js) change.
 */
const FALLBACK_COLORS = {
  text: '#6B7585',
  legend: '#566070',
  grid: 'rgba(148, 163, 184, 0.18)',
  surface: '#FFFFFF',
  accent: '#3E63DD',
  secondary: '#E08A1E',
  positive: '#12805C',
  negative: '#C2372F',
  forecast: '#9AA3B5',
  tooltipBg: '#172033'
}

const CSS_VARIABLES = {
  text: '--text-faint',
  legend: '--text-muted',
  grid: '--grid-line',
  surface: '--ink-900',
  accent: '--chart-accent',
  secondary: '--chart-secondary',
  positive: '--chart-positive',
  negative: '--chart-negative',
  forecast: '--chart-forecast',
  tooltipBg: '--chart-tooltip-bg'
}

export const CHART_FONT_FAMILY = 'Inter, sans-serif'

/** Couleurs du thème courant ; valeurs du thème clair si une variable manque (tests, CSS non chargé). */
export function readChartColors(root = globalThis.document?.documentElement) {
  const style = root ? globalThis.getComputedStyle?.(root) : null
  const colors = {}
  for (const [key, variable] of Object.entries(CSS_VARIABLES)) {
    colors[key] = style?.getPropertyValue(variable).trim() || FALLBACK_COLORS[key]
  }
  return colors
}

/** Couleur hexadécimale (#RRGGBB ou #RGB) -> rgba() ; toute autre notation est renvoyée telle quelle. */
export function withAlpha(color, alpha) {
  const match = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color)
  if (!match) return color
  const hex = match[1].length === 3 ? [...match[1]].map(c => c + c).join('') : match[1]
  const n = Number.parseInt(hex, 16)
  return `rgba(${(n >> 16) & 0xff}, ${(n >> 8) & 0xff}, ${n & 0xff}, ${alpha})`
}

/** Remplissage sous une courbe : dégradé vertical qui s'estompe vers le bas. */
export function areaGradient(color, topAlpha) {
  return (ctx) => {
    const area = ctx.chart.chartArea
    if (!area) return withAlpha(color, topAlpha / 2)
    const gradient = ctx.chart.ctx.createLinearGradient(0, area.top, 0, area.bottom)
    gradient.addColorStop(0, withAlpha(color, topAlpha))
    gradient.addColorStop(1, withAlpha(color, 0))
    return gradient
  }
}

/** Infobulle commune (fond sombre dans les deux thèmes) ; `overrides` fusionné par-dessus. */
export function tooltipOptions(colors, overrides = {}) {
  return {
    backgroundColor: colors.tooltipBg,
    titleColor: '#FFFFFF',
    bodyColor: '#E8EDF5',
    borderColor: 'rgba(255, 255, 255, 0.12)',
    borderWidth: 1,
    padding: 12,
    cornerRadius: 10,
    titleFont: { family: CHART_FONT_FAMILY, size: 12, weight: '700' },
    bodyFont: { family: CHART_FONT_FAMILY, size: 12 },
    ...overrides
  }
}

/** Axe sans bordure ; grille horizontale seulement si `withGrid`. `ticks` fusionné par-dessus le style commun. */
export function axisOptions(colors, { withGrid = false, ticks = {} } = {}) {
  return {
    grid: withGrid ? { color: colors.grid, drawTicks: false } : { display: false },
    border: { display: false },
    ticks: { color: colors.text, font: { family: CHART_FONT_FAMILY, size: 11 }, ...ticks }
  }
}

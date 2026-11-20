import { computed } from 'vue'
import { dataStore } from './dataStore'
import { categoryHue } from './categories'

/**
 * Couleur fixe de chaque catégorie, la même partout (puces CategoryChip,
 * « Où va mon argent ? », tableaux d'analyse du tableau de bord) : la
 * teinte vient de la catégorie racine, et chaque sous-catégorie la nuance en
 * luminosité. Une catégorie garde donc sa couleur d'un mois à l'autre, quel
 * que soit son rang dans un classement. Saturation et luminosité de base
 * dépendent du thème (variables --category-saturation / --category-lightness).
 */

/** Écarts de luminosité des sous-catégories d'une même racine, dans l'ordre de leurs ids. */
const CHILD_LIGHTNESS_OFFSETS = [12, -12, 22, -22, 6, -6, 17, -17]

/** Couleur neutre des montants sans catégorie. */
export const UNCATEGORIZED_COLOR = Object.freeze({ hue: 0, neutral: true, lightnessOffset: 0 })

/** Couleur de chaque catégorie (id -> { hue, neutral, lightnessOffset }), calculée une fois pour toute la liste. */
export function buildCategoryColors(categories) {
  const byId = new Map(categories.map(c => [c.id, c]))
  const colors = new Map()
  const childrenCount = new Map()
  for (const category of [...categories].sort((a, b) => a.id - b.id)) {
    const parent = category.parentId != null ? byId.get(category.parentId) : null
    if (!parent) {
      colors.set(category.id, { hue: categoryHue(category.id), neutral: false, lightnessOffset: 0 })
      continue
    }
    const rank = childrenCount.get(parent.id) ?? 0
    childrenCount.set(parent.id, rank + 1)
    colors.set(category.id, {
      hue: categoryHue(parent.id),
      neutral: false,
      lightnessOffset: CHILD_LIGHTNESS_OFFSETS[rank % CHILD_LIGHTNESS_OFFSETS.length]
    })
  }
  return colors
}

/** Variables CSS d'une couleur, pour un élément de classe .category-color (pastille, barre). */
export function categoryColorStyle(color) {
  return {
    '--h': color.hue,
    '--category-s': color.neutral ? '0%' : 'var(--category-saturation)',
    '--l-offset': `${color.lightnessOffset}%`
  }
}

/**
 * Couleur résolue pour Chart.js (un canvas ne lit pas les variables CSS) ;
 * `scale` : { saturation, lightness } du thème courant, voir readCategoryColorScale.
 */
export function categoryColorString(color, scale) {
  const saturation = color.neutral ? 0 : scale.saturation
  const lightness = Math.min(85, Math.max(20, scale.lightness + color.lightnessOffset))
  return `hsl(${color.hue} ${saturation}% ${lightness}%)`
}

/** Saturation et luminosité de base du thème courant (valeurs du thème clair à défaut). */
export function readCategoryColorScale(root = globalThis.document?.documentElement) {
  const style = root ? globalThis.getComputedStyle?.(root) : null
  const read = (name, fallback) => Number.parseFloat(style?.getPropertyValue(name)) || fallback
  return { saturation: read('--category-saturation', 62), lightness: read('--category-lightness', 52) }
}

/** Couleurs des catégories du cache partagé, recalculées seulement quand la liste change. */
export function useCategoryColors() {
  const colors = computed(() => buildCategoryColors(dataStore.state.categories))
  return (categoryId) => (categoryId != null && colors.value.get(categoryId)) || UNCATEGORIZED_COLOR
}

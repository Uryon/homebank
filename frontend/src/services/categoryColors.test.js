import { describe, expect, it } from 'vitest'
import { buildCategoryColors, categoryColorString, categoryColorStyle, UNCATEGORIZED_COLOR } from './categoryColors'
import { categoryChipInfo, categoryHue } from './categories'

const categories = [
  { id: 1, name: 'Alimentation', parentId: null },
  { id: 7, name: 'Restaurant', parentId: 1 },
  { id: 4, name: 'Courses', parentId: 1 },
  { id: 2, name: 'Logement', parentId: null }
]

describe('buildCategoryColors', () => {
  const colors = buildCategoryColors(categories)

  it('donne à chaque sous-catégorie la teinte de sa racine, nuancée dans l\'ordre des ids', () => {
    expect(colors.get(1)).toEqual({ hue: categoryHue(1), neutral: false, lightnessOffset: 0 })
    expect(colors.get(4)).toMatchObject({ hue: categoryHue(1), lightnessOffset: 12 })
    expect(colors.get(7)).toMatchObject({ hue: categoryHue(1), lightnessOffset: -12 })
    expect(colors.get(2).hue).not.toBe(colors.get(1).hue)
  })

  it('reprend la teinte des puces de catégorie', () => {
    const byId = new Map(categories.map(c => [c.id, c]))
    expect(categoryChipInfo(7, byId).hue).toBe(colors.get(7).hue)
  })
})

describe('rendu des couleurs', () => {
  it('produit une couleur hsl bornée pour Chart.js, grise sans catégorie', () => {
    expect(categoryColorString({ hue: 47, neutral: false, lightnessOffset: 40 }, { saturation: 62, lightness: 52 })).toBe('hsl(47 62% 85%)')
    expect(categoryColorString(UNCATEGORIZED_COLOR, { saturation: 62, lightness: 52 })).toBe('hsl(0 0% 52%)')
  })

  it('pose les variables CSS des pastilles', () => {
    expect(categoryColorStyle({ hue: 94, neutral: false, lightnessOffset: -12 })).toEqual({
      '--h': 94, '--category-s': 'var(--category-saturation)', '--l-offset': '-12%'
    })
  })
})

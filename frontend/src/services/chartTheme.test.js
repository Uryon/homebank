import { describe, expect, it } from 'vitest'
import { axisOptions, barGradient, readChartColors, tooltipOptions, withAlpha } from './chartTheme'

describe('chartTheme', () => {
  it('dégrade les barres du haut vers le bas de la zone de dessin', () => {
    const stops = []
    const gradient = { addColorStop: (offset, color) => stops.push([offset, color]) }
    const chart = { chartArea: { top: 10, bottom: 210 }, ctx: { createLinearGradient: () => gradient } }
    expect(barGradient('#0B5CD5', 1, 0.5)({ chart })).toBe(gradient)
    expect(stops).toEqual([[0, 'rgba(11, 92, 213, 1)'], [1, 'rgba(11, 92, 213, 0.5)']])
  })

  it('renvoie une couleur unie avant le calcul de la zone de dessin', () => {
    expect(barGradient('#0B5CD5')({ chart: { chartArea: null } })).toBe('rgba(11, 92, 213, 1)')
  })

  it('convertit une couleur hexadécimale en rgba', () => {
    expect(withAlpha('#12805C', 0.2)).toBe('rgba(18, 128, 92, 0.2)')
    expect(withAlpha('#fff', 0)).toBe('rgba(255, 255, 255, 0)')
  })

  it('laisse intactes les couleurs non hexadécimales', () => {
    expect(withAlpha('rgba(1, 2, 3, 0.5)', 0.2)).toBe('rgba(1, 2, 3, 0.5)')
  })

  it('lit les variables CSS et complète les manquantes avec le thème clair', () => {
    const root = {}
    const values = { '--chart-accent': ' #7EA6FF ', '--text-faint': '#8A95A8' }
    const previous = globalThis.getComputedStyle
    globalThis.getComputedStyle = () => ({ getPropertyValue: (name) => values[name] ?? '' })
    try {
      const colors = readChartColors(root)
      expect(colors.accent).toBe('#7EA6FF')
      expect(colors.text).toBe('#8A95A8')
      expect(colors.positive).toBe('#12805C')
    } finally {
      globalThis.getComputedStyle = previous
    }
  })

  it('renvoie les couleurs du thème clair sans document', () => {
    expect(readChartColors(null).accent).toBe('#3E63DD')
  })

  it('fusionne les surcharges de l\'infobulle et des axes', () => {
    const colors = readChartColors(null)
    expect(tooltipOptions(colors, { padding: 11 })).toMatchObject({ backgroundColor: '#172033', padding: 11 })
    const axis = axisOptions(colors, { withGrid: true, ticks: { padding: 9 } })
    expect(axis.grid.color).toBe(colors.grid)
    expect(axis.ticks).toMatchObject({ color: colors.text, padding: 9 })
    expect(axisOptions(colors).grid).toEqual({ display: false })
  })
})

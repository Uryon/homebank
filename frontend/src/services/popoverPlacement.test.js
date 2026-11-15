import { describe, expect, it } from 'vitest'
import { popoverPlacement } from './popoverPlacement'

const viewport = { width: 1400, height: 800 }
const options = { width: 320, maxHeight: 320 }

describe('popoverPlacement', () => {
  it('ouvre sous la case quand la place suffit', () => {
    const rect = { left: 500, top: 100, bottom: 130 }
    expect(popoverPlacement(rect, viewport, options)).toEqual({ x: 500, y: 136, above: false })
  })

  it('ouvre au-dessus d\'une case en bas de l\'écran', () => {
    const rect = { left: 500, top: 740, bottom: 770 }
    expect(popoverPlacement(rect, viewport, options)).toEqual({ x: 500, y: 800 - 740 + 6, above: true })
  })

  it('reste en dessous si le dessus est encore plus étroit', () => {
    const rect = { left: 500, top: 150, bottom: 600 }
    expect(popoverPlacement(rect, viewport, options).above).toBe(false)
  })

  it('ne déborde pas à droite de la fenêtre', () => {
    const rect = { left: 1300, top: 100, bottom: 130 }
    expect(popoverPlacement(rect, viewport, options).x).toBe(1400 - 320 - 8)
  })
})

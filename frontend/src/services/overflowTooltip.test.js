import { describe, expect, it } from 'vitest'
import { computeTooltipPosition, findOverflowTarget, isTruncated, tooltipTextFor } from './overflowTooltip'

/** Faux élément DOM minimal : juste ce que la logique consulte. */
function fakeEl({ ellipsis = false, scrollWidth = 100, clientWidth = 100, text = '', title = null, tooltip = null, children = [] } = {}) {
  const el = {
    ellipsis,
    scrollWidth,
    clientWidth,
    textContent: text,
    parentElement: null,
    dataset: tooltip ? { tooltip } : {},
    hasAttribute: (name) => name === 'title' && title !== null,
    querySelectorAll: () => children,
    closest: (selector) => {
      for (let node = el; node; node = node.parentElement) {
        if (selector === '[data-tooltip]' && node.dataset.tooltip) return node
      }
      return null
    }
  }
  for (const child of children) child.parentElement = el
  return el
}
const getStyle = (el) => ({ textOverflow: el.ellipsis ? 'ellipsis' : 'clip' })

describe('isTruncated', () => {
  it('détecte un contenu plus large que la boîte, avec 1 px de tolérance', () => {
    expect(isTruncated({ scrollWidth: 150, clientWidth: 100 })).toBe(true)
    expect(isTruncated({ scrollWidth: 101, clientWidth: 100 })).toBe(false)
  })
})

describe('findOverflowTarget', () => {
  it('renvoie la cellule tronquée survolée', () => {
    const td = fakeEl({ ellipsis: true, scrollWidth: 240, clientWidth: 180, text: 'Boucherie du nouveau Roubaix' })
    expect(findOverflowTarget(td, getStyle)).toBe(td)
  })

  it('ignore un texte avec ellipsis qui tient entièrement', () => {
    const td = fakeEl({ ellipsis: true, scrollWidth: 120, clientWidth: 180 })
    expect(findOverflowTarget(td, getStyle)).toBeNull()
  })

  it('remonte depuis un enfant jusqu’à l’ancêtre tronqué', () => {
    const span = fakeEl()
    const td = fakeEl({ ellipsis: true, scrollWidth: 300, clientWidth: 100, children: [span] })
    expect(findOverflowTarget(span, getStyle)).toBe(td)
  })

  it('sur une pastille data-tooltip, réagit si une de ses parties est tronquée, même en survolant la partie visible', () => {
    const parent = fakeEl({ ellipsis: true, scrollWidth: 160, clientWidth: 40 })
    const name = fakeEl({ ellipsis: true, scrollWidth: 80, clientWidth: 80 })
    const chip = fakeEl({ tooltip: 'Autres revenus › Remboursement médical', children: [parent, name] })
    expect(findOverflowTarget(name, getStyle)).toBe(chip)
  })

  it('laisse la place au title natif existant', () => {
    const td = fakeEl({ ellipsis: true, scrollWidth: 300, clientWidth: 100, title: 'déjà là' })
    expect(findOverflowTarget(td, getStyle)).toBeNull()
  })

  it('s’arrête à la limite donnée', () => {
    const span = fakeEl()
    const boundary = fakeEl({ ellipsis: true, scrollWidth: 300, clientWidth: 100, children: [span] })
    expect(findOverflowTarget(span, getStyle, boundary)).toBeNull()
  })
})

describe('tooltipTextFor', () => {
  it('préfère le data-tooltip d’un ancêtre au texte visible', () => {
    const name = fakeEl({ text: 'Remboursement' })
    fakeEl({ tooltip: 'Autres revenus › Remboursement médical', children: [name] })
    expect(tooltipTextFor(name)).toBe('Autres revenus › Remboursement médical')
  })

  it('normalise les espaces du texte de l’élément', () => {
    expect(tooltipTextFor(fakeEl({ text: '  ACHAT CB   SumUp  Clavet \n ' }))).toBe('ACHAT CB SumUp Clavet')
  })
})

describe('computeTooltipPosition', () => {
  const viewport = { width: 1440, height: 900 }
  const tip = { width: 200, height: 40 }

  it('se place sous l’élément, aligné à gauche', () => {
    const pos = computeTooltipPosition({ top: 100, bottom: 120, left: 300 }, tip, viewport)
    expect(pos).toEqual({ top: 126, left: 300, placement: 'bottom' })
  })

  it('passe au-dessus quand la place manque en bas', () => {
    const pos = computeTooltipPosition({ top: 860, bottom: 880, left: 300 }, tip, viewport)
    expect(pos.placement).toBe('top')
    expect(pos.top).toBe(860 - 6 - 40)
  })

  it('reste dans l’écran à droite', () => {
    const pos = computeTooltipPosition({ top: 100, bottom: 120, left: 1400 }, tip, viewport)
    expect(pos.left).toBe(1440 - 8 - 200)
  })
})

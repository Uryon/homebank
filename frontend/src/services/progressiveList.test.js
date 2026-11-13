import { describe, expect, it } from 'vitest'
import { effectScope, ref } from 'vue'
import { countToReveal, useProgressiveList } from './progressiveList'

/** Le composable enregistre un onBeforeUnmount : hors composant, on le crée dans un effectScope. */
function createList(length, pageSize) {
  const items = ref(Array.from({ length }, (_, i) => i))
  const scope = effectScope()
  const list = scope.run(() => useProgressiveList(items, { pageSize }))
  return { items, list }
}

describe('countToReveal', () => {
  it('arrondit au lot entier qui contient l\'index', () => {
    expect(countToReveal(50, 0, 50)).toBe(50)
    expect(countToReveal(50, 49, 50)).toBe(50)
    expect(countToReveal(50, 50, 50)).toBe(100)
    expect(countToReveal(50, 137, 50)).toBe(150)
  })

  it('ne réduit jamais le lot déjà affiché', () => {
    expect(countToReveal(200, 10, 50)).toBe(200)
    expect(countToReveal(100, -1, 50)).toBe(100)
  })
})

describe('useProgressiveList', () => {
  it('rend un premier lot puis charge la suite par paliers', () => {
    const { list } = createList(120, 50)
    expect(list.visibleItems.value).toHaveLength(50)
    expect(list.hasMore.value).toBe(true)
    list.loadMore()
    list.loadMore()
    expect(list.visibleItems.value).toHaveLength(120)
    expect(list.hasMore.value).toBe(false)
    list.loadMore()
    expect(list.visibleCount.value).toBe(150)
  })

  it('revient au premier lot avec reset', () => {
    const { list } = createList(300, 50)
    list.loadMore()
    list.reset()
    expect(list.visibleItems.value).toHaveLength(50)
  })

  it('révèle un élément lointain, ou signale qu\'il est absent', () => {
    const { list } = createList(300, 50)
    expect(list.revealIndex(210)).toBe(true)
    expect(list.visibleItems.value).toHaveLength(250)
    expect(list.revealIndex(-1)).toBe(false)
    expect(list.visibleItems.value).toHaveLength(250)
  })

  it('suit les changements de la liste source', () => {
    const { items, list } = createList(30, 50)
    expect(list.hasMore.value).toBe(false)
    items.value = Array.from({ length: 80 }, (_, i) => i)
    expect(list.visibleItems.value).toHaveLength(50)
    expect(list.hasMore.value).toBe(true)
  })
})

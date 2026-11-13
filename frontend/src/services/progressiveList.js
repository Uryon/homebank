import { computed, onScopeDispose, ref, watch } from 'vue'

/**
 * Affichage progressif d'une longue liste (tableaux Transactions, Tiers) :
 * seules les `pageSize` premieres lignes sont rendues, puis un lot de plus a
 * chaque fois que la sentinelle placee sous le tableau approche de l'ecran.
 * Rendre d'un coup des centaines de lignes rendait la frappe dans la
 * recherche perceptiblement lente (~0,5 ms de rendu par ligne).
 *
 * Utilisation : `visibleItems` dans le v-for, une sentinelle
 * `<div v-if="hasMore" ref="...">` liee au ref `sentinel` sous le tableau,
 * et `reset()` quand les filtres changent.
 */

/** Nombre de lignes a rendre pour que l'element d'index `index` soit affiche (par lots entiers). */
export function countToReveal(currentCount, index, pageSize) {
  if (index < 0) return currentCount
  return Math.max(currentCount, Math.ceil((index + 1) / pageSize) * pageSize)
}

export function useProgressiveList(items, { pageSize = 50, rootMargin = '600px' } = {}) {
  const visibleCount = ref(pageSize)
  const visibleItems = computed(() => items.value.slice(0, visibleCount.value))
  const hasMore = computed(() => visibleCount.value < items.value.length)

  function loadMore() {
    if (hasMore.value) visibleCount.value += pageSize
  }

  function reset() {
    visibleCount.value = pageSize
  }

  /** Etend le lot rendu jusqu'a l'element d'index donne ; false s'il n'est pas dans la liste. */
  function revealIndex(index) {
    if (index < 0) return false
    visibleCount.value = countToReveal(visibleCount.value, index, pageSize)
    return true
  }

  // La sentinelle apparait/disparait avec v-if="hasMore" : on reobserve a
  // chaque nouvel element plutot qu'un observe() unique au montage.
  const sentinel = ref(null)
  const observer = globalThis.IntersectionObserver
    ? new IntersectionObserver((entries) => {
      if (entries[0]?.isIntersecting) loadMore()
    }, { rootMargin })
    : null

  watch(sentinel, (el, oldEl) => {
    if (oldEl) observer?.unobserve(oldEl)
    if (el) observer?.observe(el)
  })

  onScopeDispose(() => observer?.disconnect())

  return { visibleItems, visibleCount, hasMore, sentinel, loadMore, reset, revealIndex }
}

import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Hauteur d'un élément, tenue à jour par un ResizeObserver : aucun calcul au
 * scroll ni au rendu. Reste à 0 tant que l'élément n'est pas monté, ou sans
 * ResizeObserver (environnement de test).
 */
export function useElementHeight(elementRef) {
  const height = ref(0)
  let observer = null

  onMounted(() => {
    if (!elementRef.value || typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver(([entry]) => {
      height.value = entry.target.offsetHeight
    })
    observer.observe(elementRef.value)
  })
  onBeforeUnmount(() => observer?.disconnect())

  return height
}

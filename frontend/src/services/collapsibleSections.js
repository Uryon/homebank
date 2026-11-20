import { computed, inject, provide, ref } from 'vue'

/**
 * Sections repliables d'une page (cartes du tableau de bord) : l'ensemble des
 * sections repliées est mémorisé dans le navigateur, pour retrouver la page
 * telle qu'on l'a laissée. Le stockage peut être indisponible (navigation
 * privée, données effacées) : la page fonctionne alors sans mémoire.
 */

const SECTIONS_KEY = Symbol('collapsibleSections')

export function readCollapsed(storageKey, storage = globalThis.localStorage) {
  try {
    const value = JSON.parse(storage?.getItem(storageKey) ?? '[]')
    return Array.isArray(value) ? value.filter(id => typeof id === 'string') : []
  } catch {
    return []
  }
}

export function saveCollapsed(storageKey, ids, storage = globalThis.localStorage) {
  try {
    storage?.setItem(storageKey, JSON.stringify(ids))
  } catch {
    // Préférence d'affichage seulement : rien à faire si le stockage refuse.
  }
}

/** Sections repliées (relues du stockage) et bascule qui les enregistre ; exportée pour les tests. */
export function createCollapsibleSections(storageKey, storage = globalThis.localStorage) {
  const collapsed = ref(new Set(readCollapsed(storageKey, storage)))
  function toggle(id) {
    const next = new Set(collapsed.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    collapsed.value = next
    saveCollapsed(storageKey, [...next], storage)
  }
  return { collapsed, toggle }
}

/** À appeler dans la page : crée l'état partagé par ses sections. */
export function provideCollapsibleSections(storageKey, storage = globalThis.localStorage) {
  const sections = createCollapsibleSections(storageKey, storage)
  provide(SECTIONS_KEY, sections)
  return sections
}

/** État d'une section : { collapsed (computed), toggle }. */
export function useCollapsibleSection(id) {
  const sections = inject(SECTIONS_KEY)
  return {
    collapsed: computed(() => sections.collapsed.value.has(id)),
    toggle: () => sections.toggle(id)
  }
}

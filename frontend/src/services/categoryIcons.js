import { computed } from 'vue'
import { dataStore } from './dataStore'
import { categoryIconPrefix } from './categories'

/**
 * Préfixe d'icône (emoji + espace) d'une catégorie désignée par son id, lu
 * dans le cache partagé des catégories (index par id recalculé seulement
 * quand la liste change). Pour les tableaux qui ne reçoivent que des ids de
 * catégorie (rapports du tableau de bord).
 */
export function useCategoryIconPrefix() {
  const categoriesById = computed(() => new Map(dataStore.state.categories.map(c => [c.id, c])))
  return (categoryId) => categoryIconPrefix(categoriesById.value.get(categoryId)?.icon)
}

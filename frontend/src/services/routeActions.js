import { onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * Actions de page déclenchées par l'URL (`?action=<nom>`), pour que la
 * palette de commandes (Ctrl+K) puisse ouvrir une page ET y lancer une
 * action : « Nouveau compte », « Virement entre comptes », « Exporter en
 * PDF »… L'action est retirée de l'URL aussitôt, pour qu'un retour arrière
 * ou une nouvelle commande identique puisse la relancer.
 *
 * Appelée au montage (les fonctions visées, déclarées plus bas dans le
 * <script setup>, sont alors toutes initialisées), puis à chaque changement
 * de query : la page n'est pas remontée quand on y navigue alors qu'elle est
 * déjà affichée.
 */
export function useRouteActions(handlers) {
  const route = useRoute()
  const router = useRouter()

  function apply(action) {
    // hasOwn : l'action vient de l'URL, elle ne doit jamais atteindre une
    // methode heritee d'Object.prototype (« ?action=constructor »).
    const handler = typeof action === 'string' && Object.hasOwn(handlers, action) ? handlers[action] : null
    if (typeof handler !== 'function') return
    const rest = { ...route.query }
    delete rest.action
    router.replace({ query: rest })
    handler()
  }

  onMounted(() => {
    apply(route.query.action)
    watch(() => route.query.action, apply)
  })
}

/** Route d'une action de page, pour les commandes de la palette. */
export function routeAction(path, action) {
  return { path, query: { action } }
}

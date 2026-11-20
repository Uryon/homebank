/**
 * Une seule fenêtre HomeBank à la fois.
 *
 * Le raccourci (HomeBank.vbs) n'ouvre qu'une fenêtre, mais Ctrl+N ou un lien
 * ouvert dans une nouvelle fenêtre en ouvrait une deuxième sur les mêmes
 * données : deux saisies concurrentes pouvaient s'écraser. La fenêtre active
 * tient un verrou du navigateur (Web Locks API, partagé par toutes les
 * fenêtres du même profil et de la même adresse, rendu automatiquement à la
 * fermeture). Une autre fenêtre reste inactive (l'appli n'y est pas montée,
 * aucune donnée chargée) et :
 * - prend le relais d'elle-même dès que la fenêtre active est fermée ;
 * - peut prendre la main tout de suite (takeOver) : elle s'empare du verrou
 *   (option « steal »), et la fenêtre active, avertie par le rejet de sa
 *   demande, devient inactive à son tour.
 */
export const SINGLE_WINDOW_LOCK = 'homebank-fenetre-unique'

/**
 * @param {object} options
 * @param {() => void} options.onActive appelée quand cette fenêtre devient la fenêtre active
 * @param {() => void} options.onInactive appelée quand une autre fenêtre l'est
 * @param {LockManager} [options.locks] gestionnaire de verrous (navigator.locks par défaut)
 * @returns {{ takeOver: () => void }}
 */
export function startSingleWindow({ onActive, onInactive, locks = globalThis.navigator?.locks }) {
  // Navigateur sans Web Locks : pas de garde plutôt qu'une appli inutilisable.
  if (!locks?.request) {
    onActive()
    return { takeOver() {} }
  }

  // Demande en file d'attente (une seule à la fois), annulée par takeOver.
  let waiting = null

  /** Verrou obtenu : gardé jusqu'à la fermeture de la fenêtre ou une prise de main. */
  function holdLock() {
    onActive()
    return new Promise(() => {})
  }

  /** Verrou pris par une autre fenêtre (steal) : celle-ci devient inactive et se remet en file. */
  function watch(request, ownSignal) {
    request.catch(error => {
      if (ownSignal?.aborted) return
      if (error?.name !== 'AbortError') return
      onInactive()
      waitForLock()
    })
  }

  function waitForLock() {
    waiting = new AbortController()
    watch(locks.request(SINGLE_WINDOW_LOCK, { signal: waiting.signal }, holdLock), waiting.signal)
  }

  watch(locks.request(SINGLE_WINDOW_LOCK, { ifAvailable: true }, lock => {
    if (lock) return holdLock()
    onInactive()
    waitForLock()
    return undefined
  }))

  return {
    takeOver() {
      waiting?.abort()
      waiting = null
      watch(locks.request(SINGLE_WINDOW_LOCK, { steal: true }, holdLock))
    }
  }
}

import { getCurrentInstance, onBeforeUnmount, ref } from 'vue'
import { alertApiError } from './errors'

export const UNDO_TIMEOUT_MS = 10000

/**
 * Annulation de la dernière action (création, suppression, virement...) : un
 * seul niveau, pas d'historique complet, affiché par UndoBar.vue tant que
 * lastAction n'est pas null. setLastAction remplace toujours l'action
 * précédente et se dissipe seule après `timeoutMs`, pour ne pas rester
 * affichée une fois qu'elle n'est plus pertinente.
 *
 * Utilisation :
 *   const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()
 *   setLastAction('Virement effectué', () => TransactionsAPI.undoCreation([id]))
 *   <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
 */
export function useLastAction({ timeoutMs = UNDO_TIMEOUT_MS, onError = alertApiError } = {}) {
  const lastAction = ref(null) // { label, undo: () => Promise<void>, retryable: boolean }
  const undoing = ref(false)
  let timer = null

  function stopTimer() {
    if (timer) clearTimeout(timer)
    timer = null
  }

  function clearLastAction() {
    stopTimer()
    lastAction.value = null
  }

  function startTimer() {
    stopTimer()
    timer = setTimeout(clearLastAction, timeoutMs)
  }

  /**
   * `retryable` : l'annulation est tout ou rien, on peut donc la relancer
   * après un échec ; le bandeau reste affiché, sinon les données à restaurer
   * seraient perdues.
   */
  function setLastAction(label, undo, { retryable = false } = {}) {
    lastAction.value = { label, undo, retryable }
    startTimer()
  }

  async function runLastAction() {
    if (!lastAction.value || undoing.value) return
    const action = lastAction.value
    // Pas d'expiration pendant l'annulation : son résultat décide de la suite.
    stopTimer()
    undoing.value = true
    let keepForRetry = false
    try {
      await action.undo()
    } catch (e) {
      keepForRetry = action.retryable
      onError("Erreur lors de l'annulation", e)
    } finally {
      undoing.value = false
      // Une nouvelle action arrivée entre-temps garde son bandeau et son délai.
      if (lastAction.value === action) {
        if (keepForRetry) startTimer()
        else clearLastAction()
      }
    }
  }

  // En quittant la page : plus de bandeau ni de minuterie en suspens.
  if (getCurrentInstance()) onBeforeUnmount(clearLastAction)

  return { lastAction, undoing, setLastAction, clearLastAction, runLastAction }
}

/**
 * Ctrl+Z (Cmd+Z sur Mac) déclenche le bouton « Annuler » du bandeau, sauf
 * pendant une saisie (l'annulation de frappe du navigateur reste prioritaire)
 * ou quand une fenêtre est ouverte (son contenu n'est pas concerné).
 */
function overlayIsOpen() {
  return !!document.querySelector('.modal-overlay, .feedback-overlay, .palette-overlay')
}

export function isUndoShortcut(event, isOverlayOpen = overlayIsOpen) {
  if (!(event.ctrlKey || event.metaKey) || event.shiftKey || event.altKey) return false
  if (event.key.toLowerCase() !== 'z') return false
  const target = event.target
  const typing = !!target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
  return !typing && !isOverlayOpen()
}

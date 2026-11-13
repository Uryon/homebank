import { reactive } from 'vue'

let nextId = 1
const state = reactive({
  toasts: [],
  confirmation: null,
  textPrompt: null
})
let pendingConfirmation = null
let pendingTextPrompt = null

export function notify(message, type = 'success', duration = 3800) {
  const toast = { id: nextId++, message: String(message), type }
  state.toasts.push(toast)
  if (duration > 0) {
    globalThis.setTimeout(() => dismissToast(toast.id), duration)
  }
  return toast.id
}

export function dismissToast(id) {
  const index = state.toasts.findIndex(item => item.id === id)
  if (index !== -1) state.toasts.splice(index, 1)
}

/** Confirmation non bloquante, présentée dans la fenêtre HomeBank. */
export function askConfirm(message, options = {}) {
  if (pendingConfirmation) pendingConfirmation.resolve(false)
  return new Promise(resolve => {
    pendingConfirmation = { resolve }
    state.confirmation = {
      title: options.title || 'Confirmer cette action',
      message: String(message),
      confirmLabel: options.confirmLabel || 'Confirmer',
      cancelLabel: options.cancelLabel || 'Annuler',
      destructive: options.destructive !== false
    }
  })
}

export function resolveConfirm(accepted) {
  const current = pendingConfirmation
  pendingConfirmation = null
  state.confirmation = null
  current?.resolve(Boolean(accepted))
}

/** Saisie texte non bloquante, rendue dans une modale HomeBank. */
export function askText(message, initialValue = '', options = {}) {
  if (pendingTextPrompt) pendingTextPrompt.resolve(null)
  return new Promise(resolve => {
    pendingTextPrompt = { resolve }
    state.textPrompt = {
      title: options.title || 'Modifier le motif',
      message: String(message),
      value: String(initialValue ?? ''),
      placeholder: options.placeholder || '',
      confirmLabel: options.confirmLabel || 'Enregistrer',
      cancelLabel: options.cancelLabel || 'Annuler'
    }
  })
}

export function resolveTextPrompt(value) {
  const current = pendingTextPrompt
  pendingTextPrompt = null
  state.textPrompt = null
  current?.resolve(value === null ? null : String(value))
}

export { state as feedbackState }

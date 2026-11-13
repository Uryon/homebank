import { notify } from './feedback'

const UNEXPECTED_ERROR = 'Une erreur inattendue est survenue.'

/**
 * Message d'erreur a afficher pour un appel API en echec : privilegie le
 * message renvoye par le backend (voir GlobalExceptionHandler). A defaut,
 * traduit les echecs reseau d'axios, dont le message brut est en anglais
 * ("Network Error", "Request failed with status code 500") et n'aide pas
 * l'utilisateur.
 */
export function apiErrorMessage(error) {
  const backendMessage = error?.response?.data?.error
  if (backendMessage) return backendMessage

  if (error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT') {
    return 'Le serveur met trop de temps à répondre.'
  }
  // Requete partie mais aucune reponse : serveur arrete ou reseau coupe.
  if (error?.request && !error.response) {
    return 'Serveur injoignable : vérifiez que HomeBank est démarré.'
  }
  const status = error?.response?.status
  // 5xx sans message : GlobalExceptionHandler en renvoie toujours un, c'est
  // donc presque toujours le proxy (dev) qui n'a pas pu joindre le backend.
  if (status >= 500) return `Le serveur ne répond pas correctement (code ${status}) : vérifiez que HomeBank est démarré.`
  if (status) return `Le serveur a renvoyé une erreur (code ${status}).`

  return error?.message || UNEXPECTED_ERROR
}

/** Alerte utilisateur standard pour un appel API en echec : "<prefixe> : <message>". */
export function alertApiError(prefix, error) {
  notify(`${prefix} : ${apiErrorMessage(error)}`, 'error', 6000)
}

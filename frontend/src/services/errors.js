/**
 * Message d'erreur a afficher pour un appel API en echec : privilegie le
 * message renvoye par le backend (voir GlobalExceptionHandler), sinon
 * retombe sur le message generique de l'exception JS/axios.
 */
export function apiErrorMessage(error) {
  return error?.response?.data?.error || error?.message
}

/** Alerte utilisateur standard pour un appel API en echec : "<prefixe> : <message>". */
export function alertApiError(prefix, error) {
  alert(`${prefix} : ${apiErrorMessage(error)}`)
}

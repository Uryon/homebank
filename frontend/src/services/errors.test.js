import { afterEach, describe, expect, it } from 'vitest'
import { alertApiError, apiErrorMessage } from './errors'
import { dismissToast, feedbackState } from './feedback'

describe('apiErrorMessage', () => {
  it('privilégie le message renvoyé par le backend', () => {
    const error = { message: 'Request failed with status code 400', response: { status: 400, data: { error: 'Nom obligatoire' } } }
    expect(apiErrorMessage(error)).toBe('Nom obligatoire')
  })

  it('signale un serveur injoignable plutôt que « Network Error »', () => {
    expect(apiErrorMessage({ message: 'Network Error', request: {} })).toBe('Serveur injoignable : vérifiez que HomeBank est démarré.')
  })

  it('signale un délai dépassé', () => {
    expect(apiErrorMessage({ code: 'ECONNABORTED', message: 'timeout of 0ms exceeded', request: {} })).toBe('Le serveur met trop de temps à répondre.')
  })

  it('donne le code HTTP quand la réponse ne contient pas de message', () => {
    const error = { message: 'Request failed with status code 500', request: {}, response: { status: 500, data: '' } }
    expect(apiErrorMessage(error)).toBe('Le serveur ne répond pas correctement (code 500) : vérifiez que HomeBank est démarré.')
    expect(apiErrorMessage({ request: {}, response: { status: 404, data: {} } })).toBe('Le serveur a renvoyé une erreur (code 404).')
  })

  it('retombe sur le message de l\'exception, puis sur un message générique', () => {
    expect(apiErrorMessage(new Error('boum'))).toBe('boum')
    expect(apiErrorMessage(undefined)).toBe('Une erreur inattendue est survenue.')
  })
})

describe('alertApiError', () => {
  afterEach(() => {
    for (const toast of [...feedbackState.toasts]) dismissToast(toast.id)
  })

  it('affiche une notification d\'erreur préfixée', () => {
    alertApiError('Enregistrement échoué', { response: { status: 409, data: { error: 'Déjà existant' } } })
    expect(feedbackState.toasts.at(-1)).toMatchObject({ message: 'Enregistrement échoué : Déjà existant', type: 'error' })
  })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  askConfirm,
  askText,
  dismissToast,
  feedbackState,
  notify,
  resolveConfirm,
  resolveTextPrompt
} from './feedback'

afterEach(() => {
  vi.useRealTimers()
  for (const toast of [...feedbackState.toasts]) dismissToast(toast.id)
  resolveConfirm(false)
  resolveTextPrompt(null)
})

describe('notify', () => {
  it('ajoute une notification qui disparaît après sa durée', () => {
    vi.useFakeTimers()
    const id = notify('Enregistré', 'success', 1000)
    expect(feedbackState.toasts.map(t => t.id)).toContain(id)
    vi.advanceTimersByTime(1000)
    expect(feedbackState.toasts.map(t => t.id)).not.toContain(id)
  })

  it('garde une notification de durée nulle jusqu\'à fermeture manuelle', () => {
    vi.useFakeTimers()
    const id = notify('À lire', 'error', 0)
    vi.advanceTimersByTime(60_000)
    expect(feedbackState.toasts.map(t => t.id)).toContain(id)
    dismissToast(id)
    expect(feedbackState.toasts.map(t => t.id)).not.toContain(id)
  })
})

describe('askConfirm', () => {
  it('résout la promesse avec le choix de l\'utilisateur', async () => {
    const answer = askConfirm('Supprimer ?', { confirmLabel: 'Supprimer' })
    expect(feedbackState.confirmation).toMatchObject({ message: 'Supprimer ?', confirmLabel: 'Supprimer', destructive: true })
    resolveConfirm(true)
    await expect(answer).resolves.toBe(true)
    expect(feedbackState.confirmation).toBeNull()
  })

  it('refuse la confirmation précédente quand une nouvelle est demandée', async () => {
    const first = askConfirm('Première ?')
    const second = askConfirm('Seconde ?')
    await expect(first).resolves.toBe(false)
    expect(feedbackState.confirmation.message).toBe('Seconde ?')
    resolveConfirm(true)
    await expect(second).resolves.toBe(true)
  })
})

describe('askText', () => {
  it('renvoie le texte saisi, ou null si annulé', async () => {
    const saved = askText('Motif ?', 'CARTE')
    expect(feedbackState.textPrompt.value).toBe('CARTE')
    resolveTextPrompt('CARTE*')
    await expect(saved).resolves.toBe('CARTE*')

    const cancelled = askText('Motif ?')
    resolveTextPrompt(null)
    await expect(cancelled).resolves.toBeNull()
  })
})

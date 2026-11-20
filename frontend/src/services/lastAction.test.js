import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useLastAction, isUndoShortcut } from './lastAction'

describe('useLastAction', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it("expose l'action puis la retire après le délai", () => {
    const { lastAction, setLastAction } = useLastAction({ timeoutMs: 1000 })
    setLastAction('Virement effectué', async () => {})

    expect(lastAction.value.label).toBe('Virement effectué')
    vi.advanceTimersByTime(1000)
    expect(lastAction.value).toBeNull()
  })

  it('remplace la précédente et repart sur un délai complet', () => {
    const { lastAction, setLastAction } = useLastAction({ timeoutMs: 1000 })
    setLastAction('A', async () => {})
    vi.advanceTimersByTime(800)
    setLastAction('B', async () => {})
    vi.advanceTimersByTime(800)

    expect(lastAction.value.label).toBe('B')
  })

  it("exécute l'annulation une seule fois puis efface le bandeau", async () => {
    const undo = vi.fn().mockResolvedValue()
    const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()
    setLastAction('Compte archivé', undo)

    const first = runLastAction()
    expect(undoing.value).toBe(true)
    await runLastAction() // ignoré : annulation déjà en cours
    await first

    expect(undo).toHaveBeenCalledTimes(1)
    expect(undoing.value).toBe(false)
    expect(lastAction.value).toBeNull()
  })

  it("signale l'échec de l'annulation sans laisser le bandeau bloqué", async () => {
    const onError = vi.fn()
    const error = new Error('réseau')
    const { lastAction, undoing, setLastAction, runLastAction } = useLastAction({ onError })
    setLastAction('Virement effectué', () => Promise.reject(error))

    await runLastAction()

    expect(onError).toHaveBeenCalledWith("Erreur lors de l'annulation", error)
    expect(undoing.value).toBe(false)
    expect(lastAction.value).toBeNull()
  })

  it('garde une annulation relançable après un échec, puis la réussit', async () => {
    const undo = vi.fn().mockRejectedValueOnce(new Error('réseau')).mockResolvedValue()
    const { lastAction, setLastAction, runLastAction } = useLastAction({ onError: vi.fn(), timeoutMs: 1000 })
    setLastAction('Transaction supprimée', undo, { retryable: true })

    await runLastAction()
    expect(lastAction.value.label).toBe('Transaction supprimée')

    await runLastAction()
    expect(undo).toHaveBeenCalledTimes(2)
    expect(lastAction.value).toBeNull()
  })

  it('relance le délai après un échec relançable', async () => {
    const { lastAction, setLastAction, runLastAction } = useLastAction({ onError: vi.fn(), timeoutMs: 1000 })
    setLastAction('Transaction supprimée', () => Promise.reject(new Error('réseau')), { retryable: true })
    vi.advanceTimersByTime(900)

    await runLastAction()
    vi.advanceTimersByTime(900)
    expect(lastAction.value).not.toBeNull()
    vi.advanceTimersByTime(100)
    expect(lastAction.value).toBeNull()
  })

  it('n’expire pas pendant une annulation en cours', async () => {
    let terminer
    const { lastAction, setLastAction, runLastAction } = useLastAction({ onError: vi.fn(), timeoutMs: 1000 })
    setLastAction('Suppression', () => new Promise((_, reject) => { terminer = reject }), { retryable: true })

    const enCours = runLastAction()
    vi.advanceTimersByTime(5000)
    expect(lastAction.value).not.toBeNull()
    terminer(new Error('réseau'))
    await enCours
    expect(lastAction.value).not.toBeNull()
  })

  it('n’efface pas une nouvelle action arrivée pendant l’annulation', async () => {
    let terminer
    const { lastAction, setLastAction, runLastAction } = useLastAction()
    setLastAction('A', () => new Promise(resolve => { terminer = resolve }))

    const enCours = runLastAction()
    setLastAction('B', async () => {})
    terminer()
    await enCours

    expect(lastAction.value.label).toBe('B')
  })
})

describe('isUndoShortcut', () => {
  const onPage = { tagName: 'BODY', isContentEditable: false }
  const noOverlay = () => false
  it('reconnaît Ctrl+Z et Cmd+Z hors saisie', () => {
    expect(isUndoShortcut({ ctrlKey: true, key: 'z', target: onPage }, noOverlay)).toBe(true)
    expect(isUndoShortcut({ metaKey: true, key: 'Z', target: onPage }, noOverlay)).toBe(true)
  })
  it('ne fait rien quand une fenêtre est ouverte', () => {
    expect(isUndoShortcut({ ctrlKey: true, key: 'z', target: onPage }, () => true)).toBe(false)
  })
  it('laisse le navigateur annuler la frappe dans un champ, et ignore Ctrl+Maj+Z', () => {
    expect(isUndoShortcut({ ctrlKey: true, key: 'z', target: { tagName: 'INPUT' } })).toBe(false)
    expect(isUndoShortcut({ ctrlKey: true, shiftKey: true, key: 'z', target: onPage })).toBe(false)
    expect(isUndoShortcut({ key: 'z', target: onPage })).toBe(false)
  })
})

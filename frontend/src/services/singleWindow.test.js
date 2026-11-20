import { describe, it, expect, vi } from 'vitest'
import { startSingleWindow } from './singleWindow'

const abortError = () => Object.assign(new Error('verrou perdu'), { name: 'AbortError' })

/**
 * Gestionnaire de verrous simulé, partagé entre « fenêtres », comme celui du
 * navigateur : file d'attente, ifAvailable, steal (le détenteur voit sa
 * demande rejetée), annulation d'une demande en attente par signal, et
 * verrous rendus à la fermeture d'une fenêtre. Un seul nom de verrou suffit ici.
 */
function fakeBrowser() {
  let holder = null
  let waiting = []

  function grant(entry) {
    holder = entry
    Promise.resolve(entry.callback({ name: entry.name })).then(value => {
      if (holder !== entry) return
      holder = null
      entry.resolve(value)
      grantNext()
    })
  }

  function grantNext() {
    if (!holder && waiting.length) grant(waiting.shift())
  }

  function locksFor(owner) {
    return {
      request(name, optionsOrCallback, maybeCallback) {
        const options = maybeCallback ? optionsOrCallback : {}
        const callback = maybeCallback ?? optionsOrCallback
        return new Promise((resolve, reject) => {
          const entry = { owner, name, callback, resolve, reject }
          if (options.ifAvailable && (holder || waiting.length)) {
            resolve(callback(null))
            return
          }
          if (options.steal) {
            const robbed = holder
            holder = null
            robbed?.reject(abortError())
            grant(entry)
            return
          }
          options.signal?.addEventListener('abort', () => {
            if (!waiting.includes(entry)) return
            waiting = waiting.filter(other => other !== entry)
            reject(abortError())
          })
          waiting.push(entry)
          grantNext()
        })
      }
    }
  }

  /** Fermeture d'une fenêtre : ses demandes disparaissent et son verrou est rendu. */
  function close(owner) {
    waiting = waiting.filter(entry => entry.owner !== owner)
    if (holder?.owner === owner) {
      holder = null
      grantNext()
    }
  }

  function openWindow(owner) {
    const onActive = vi.fn()
    const onInactive = vi.fn()
    const guard = startSingleWindow({ onActive, onInactive, locks: locksFor(owner) })
    return { onActive, onInactive, guard }
  }

  return { openWindow, close, waitingCount: () => waiting.length }
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0))

describe('startSingleWindow', () => {
  it('rend active la première fenêtre', async () => {
    const browser = fakeBrowser()
    const first = browser.openWindow('A')
    await flush()

    expect(first.onActive).toHaveBeenCalledTimes(1)
    expect(first.onInactive).not.toHaveBeenCalled()
  })

  it('laisse une deuxième fenêtre inactive, sans monter l’appli', async () => {
    const browser = fakeBrowser()
    browser.openWindow('A')
    const second = browser.openWindow('B')
    await flush()

    expect(second.onInactive).toHaveBeenCalledTimes(1)
    expect(second.onActive).not.toHaveBeenCalled()
  })

  it('active la fenêtre en attente dès que la fenêtre active est fermée', async () => {
    const browser = fakeBrowser()
    browser.openWindow('A')
    const second = browser.openWindow('B')
    await flush()

    browser.close('A')
    await flush()

    expect(second.onActive).toHaveBeenCalledTimes(1)
  })

  it("donne la main à la fenêtre qui la prend, et rend l'autre inactive", async () => {
    const browser = fakeBrowser()
    const first = browser.openWindow('A')
    const second = browser.openWindow('B')
    await flush()

    second.guard.takeOver()
    await flush()

    expect(second.onActive).toHaveBeenCalledTimes(1)
    expect(first.onInactive).toHaveBeenCalledTimes(1)
  })

  it('donne la main à celle qui la prend même si une autre attendait avant elle', async () => {
    const browser = fakeBrowser()
    browser.openWindow('A')
    const second = browser.openWindow('B')
    const third = browser.openWindow('C')
    await flush()

    third.guard.takeOver()
    await flush()

    expect(third.onActive).toHaveBeenCalledTimes(1)
    expect(second.onActive).not.toHaveBeenCalled()
  })

  it("rend la main à l'ancienne fenêtre quand la nouvelle est fermée", async () => {
    const browser = fakeBrowser()
    const first = browser.openWindow('A')
    const second = browser.openWindow('B')
    await flush()
    second.guard.takeOver()
    await flush()

    browser.close('B')
    await flush()

    expect(first.onActive).toHaveBeenCalledTimes(2)
  })

  it('ne garde qu’une demande en attente par fenêtre après des prises de main répétées', async () => {
    const browser = fakeBrowser()
    const first = browser.openWindow('A')
    const second = browser.openWindow('B')
    await flush()

    second.guard.takeOver()
    await flush()
    first.guard.takeOver()
    await flush()
    second.guard.takeOver()
    await flush()

    // B active, A en attente (une seule demande).
    expect(browser.waitingCount()).toBe(1)
    expect(second.onActive).toHaveBeenCalledTimes(2)
    expect(first.onInactive).toHaveBeenCalledTimes(2)
  })

  it('active directement la fenêtre si le navigateur ne connaît pas les verrous', () => {
    const onActive = vi.fn()
    const onInactive = vi.fn()
    const guard = startSingleWindow({ onActive, onInactive, locks: undefined })

    expect(onActive).toHaveBeenCalledTimes(1)
    expect(() => guard.takeOver()).not.toThrow()
  })
})

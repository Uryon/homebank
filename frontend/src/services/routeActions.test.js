import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'

// Pas de DOM dans ces tests : onMounted est capture pour etre declenche a la
// main, et le routeur est simule par une route reactive (le vrai watch de Vue
// reagit donc aux changements de query comme dans l'application).
const fake = vi.hoisted(() => ({ mountedCallbacks: [], route: null, router: null }))

vi.mock('vue', async importOriginal => ({
  ...(await importOriginal()),
  onMounted: callback => fake.mountedCallbacks.push(callback)
}))

vi.mock('vue-router', () => ({
  useRoute: () => fake.route,
  useRouter: () => fake.router
}))

const { routeAction, useRouteActions } = await import('./routeActions')

function mountPage(handlers) {
  useRouteActions(handlers)
  fake.mountedCallbacks.forEach(callback => callback())
}

beforeEach(() => {
  fake.mountedCallbacks = []
  fake.route = reactive({ query: {} })
  fake.router = {
    replace: vi.fn(({ query }) => { fake.route.query = query })
  }
})

describe('useRouteActions', () => {
  it('lance au montage l’action presente dans l’URL et la retire en gardant les autres parametres', () => {
    fake.route.query = { action: 'create', accountId: '3' }
    const create = vi.fn()

    mountPage({ create })

    expect(create).toHaveBeenCalledTimes(1)
    expect(fake.router.replace).toHaveBeenCalledWith({ query: { accountId: '3' } })
  })

  it('ignore une action inconnue sans toucher a l’URL', () => {
    fake.route.query = { action: 'inconnue' }
    const create = vi.fn()

    mountPage({ create })

    expect(create).not.toHaveBeenCalled()
    expect(fake.router.replace).not.toHaveBeenCalled()
  })

  it('ne confond pas une action avec une propriete heritee de l’objet des handlers', () => {
    fake.route.query = { action: 'toString' }

    mountPage({})

    expect(fake.router.replace).not.toHaveBeenCalled()
  })

  it('lance l’action quand elle arrive alors que la page est deja affichee', async () => {
    const exportPdf = vi.fn()
    mountPage({ exportPdf })
    expect(exportPdf).not.toHaveBeenCalled()

    fake.route.query = { action: 'exportPdf' }
    await nextTick()

    expect(exportPdf).toHaveBeenCalledTimes(1)
    expect(fake.route.query).toEqual({})
  })

  it('relance la meme action demandee une seconde fois', async () => {
    const create = vi.fn()
    mountPage({ create })

    fake.route.query = { action: 'create' }
    await nextTick()
    fake.route.query = { action: 'create' }
    await nextTick()

    expect(create).toHaveBeenCalledTimes(2)
  })
})

describe('routeAction', () => {
  it('construit la route d’une action de page pour la palette', () => {
    expect(routeAction('/accounts', 'create')).toEqual({ path: '/accounts', query: { action: 'create' } })
  })
})

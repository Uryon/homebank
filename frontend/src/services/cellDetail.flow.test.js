import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('./api', () => ({ ReportsAPI: { cellTransactions: vi.fn() } }))
const { ReportsAPI } = await import('./api')
const { createCellDetail, HOVER_DELAY_MS } = await import('./cellDetail')

/** Événement de survol minimal : la case visée et sa position à l'écran. */
function hoverEvent() {
  return { currentTarget: { getBoundingClientRect: () => ({ top: 100, bottom: 120, left: 50, right: 150, width: 100, height: 20 }) } }
}

describe('détail des cases du tableau de bord', () => {
  let router
  let detail

  beforeEach(() => {
    vi.useFakeTimers()
    globalThis.window = { innerWidth: 1440, innerHeight: 900 }
    router = { push: vi.fn() }
    detail = createCellDetail(router)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('n\'affiche le détail qu\'après le délai de survol, totalisé et trié', async () => {
    const rows = [{ id: 1, date: '2026-01-02', payee: 'A', amount: -5 }, { id: 2, date: '2026-01-03', payee: 'B', amount: -20 }]
    detail.scheduleHover(hoverEvent(), 'Courses', () => Promise.resolve(rows))
    expect(detail.hover.value.visible).toBe(false)
    await vi.advanceTimersByTimeAsync(HOVER_DELAY_MS)
    expect(detail.hover.value).toMatchObject({ visible: true, loading: false, title: 'Courses', total: -25 })
    expect(detail.hover.value.rows[0].payee).toBe('B')
  })

  it('n\'ouvre rien si la souris quitte la case avant le délai', async () => {
    const loadRows = vi.fn(() => Promise.resolve([]))
    detail.scheduleHover(hoverEvent(), 'Courses', loadRows)
    detail.onCellLeave()
    await vi.advanceTimersByTimeAsync(HOVER_DELAY_MS + 500)
    expect(loadRows).not.toHaveBeenCalled()
    expect(detail.hover.value.visible).toBe(false)
  })

  it('ignore une réponse arrivée après avoir quitté la case', async () => {
    let resolve
    detail.scheduleHover(hoverEvent(), 'Courses', () => new Promise(r => { resolve = r }))
    await vi.advanceTimersByTimeAsync(HOVER_DELAY_MS)
    detail.onCellLeave()
    resolve([{ id: 1, payee: 'A', amount: -5 }])
    await vi.advanceTimersByTimeAsync(500)
    expect(detail.hover.value.rows).toEqual([])
    expect(detail.hover.value.visible).toBe(false)
  })

  it('ne détaille ni n\'ouvre un mois prévisionnel', async () => {
    const loadRows = vi.fn(() => Promise.resolve([]))
    detail.scheduleMonthHover(hoverEvent(), 'Prévu', loadRows, true)
    detail.openMonthTransactions('2027-01-01', 'expense', {}, 'Prévu', true)
    await vi.advanceTimersByTimeAsync(HOVER_DELAY_MS)
    expect(loadRows).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })

  it('ouvre les transactions du mois, ou d\'un tiers par recherche exacte', () => {
    detail.openMonthTransactions('2026-02-01', 'expense', { categoryId: 7 }, 'Courses — Février 2026', false)
    expect(router.push.mock.calls[0][0].query).toMatchObject({ from: '2026-02-01', to: '2026-02-28', flow: 'expense', category: '7' })
    detail.openPayeeTransactions('Netflix')
    expect(router.push).toHaveBeenLastCalledWith({ path: '/transactions', query: { search: '"Netflix"' } })
    detail.openPayeeTransactions('')
    expect(router.push).toHaveBeenCalledTimes(2)
  })

  it('interroge le serveur sur la période et le sens de la case', async () => {
    ReportsAPI.cellTransactions.mockResolvedValue([])
    await detail.fetchMonthCellTransactions('2026-02-01', null, { rootCategoryId: 3 })
    expect(ReportsAPI.cellTransactions).toHaveBeenCalledWith({ from: '2026-02-01', to: '2026-02-28', flow: undefined, rootCategoryId: 3 })
  })

  it('ne rouvre plus rien une fois la page quittée', async () => {
    const loadRows = vi.fn(() => Promise.resolve([]))
    detail.scheduleHover(hoverEvent(), 'Courses', loadRows)
    detail.dispose()
    await vi.advanceTimersByTimeAsync(HOVER_DELAY_MS + 200)
    expect(loadRows).not.toHaveBeenCalled()
  })
})

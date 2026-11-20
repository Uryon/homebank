import { describe, expect, it, vi } from 'vitest'

vi.mock('./feedback', () => ({ notify: vi.fn() }))
const { notify } = await import('./feedback')
const { exportDashboardAfterReconciliation } = await import('./dashboardExport')

describe('exportDashboardAfterReconciliation', () => {
  it('signale la réussite de l\'export', async () => {
    await exportDashboardAfterReconciliation({ dashboardPdf: () => Promise.resolve({ status: 'ok' }) })
    expect(notify).toHaveBeenLastCalledWith(expect.stringContaining('exporté en PDF'), 'success')
  })

  it('ne fait qu\'avertir en cas d\'échec : le rapprochement reste enregistré', async () => {
    await expect(exportDashboardAfterReconciliation({ dashboardPdf: () => Promise.reject(new Error('Node introuvable')) })).resolves.not.toThrow()
    expect(notify).toHaveBeenLastCalledWith(expect.stringContaining('Rapprochement enregistré'), 'warning', 8000)
  })
})

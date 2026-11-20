import { ExportAPI } from './api'
import { apiErrorMessage } from './errors'
import { notify } from './feedback'

/**
 * Export du tableau de bord en PDF dans le dossier synchronisé (voir
 * ServiceExport côté backend), lancé automatiquement après un rapprochement
 * validé : le PDF reflète alors des comptes vérifiés. Non bloquant — le
 * rapprochement est déjà enregistré ; un échec n'est qu'un avertissement.
 */
export function exportDashboardAfterReconciliation(api = ExportAPI) {
  return api.dashboardPdf().then(
    () => notify('Tableau de bord exporté en PDF dans le dossier de sortie.', 'success'),
    (e) => notify(`Rapprochement enregistré, mais l'export PDF du tableau de bord a échoué : ${apiErrorMessage(e)}`, 'warning', 8000)
  )
}

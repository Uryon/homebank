import { inject, onBeforeUnmount, provide, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ReportsAPI } from './api'
import { drillDownQuery } from './drillDown'
import { popoverPlacement } from './popoverPlacement'
import { mergeRowsByPayee, monthRange } from './dashboardAnalysis'

/**
 * Détail des montants du tableau de bord, partagé par toutes ses cartes
 * (graphiques et tableaux, voir components/dashboard/) :
 * - survol d'un montant : popover unique (CellTransactionsPopover) listant les
 *   transactions qui le composent, après un délai de survol continu ;
 * - clic : page Transactions filtrée sur la même période, le même sens et la
 *   même catégorie (voir services/drillDown.js).
 * Un seul état pour toute la page plutôt qu'un popover par case : l'interaction
 * n'est jamais active qu'à un seul endroit à la fois.
 */

export const HOVER_DELAY_MS = 1000
const HIDE_GRACE_MS = 150
const POPOVER_WIDTH = 320
const POPOVER_MAX_HEIGHT = 320 // max-height de .cell-popover

const CELL_DETAIL_KEY = Symbol('cellDetail')

/** Plus gros montant d'abord, en valeur absolue : sur une liste de dépenses, -1,25 € passe avant -1,15 €. */
export function sortRowsByMagnitude(rows) {
  return [...rows].sort((a, b) => Math.abs(Number(b.amount)) - Math.abs(Number(a.amount)))
}

/** Lignes et total affichés par le popover pour les transactions d'une case. */
export function popoverContent(rows) {
  const total = rows.reduce((sum, r) => sum + Number(r.amount || 0), 0)
  return { rows: sortRowsByMagnitude(mergeRowsByPayee(rows)), total }
}

function createCellDetail(router) {
  const hover = ref({ visible: false, loading: false, x: 0, y: 0, above: false, title: '', rows: [], total: 0 })
  let showTimer = null
  let hideTimer = null
  let requestToken = 0

  /** Sous la case, ou au-dessus pour une case en bas de l'écran (voir popoverPlacement). */
  function positionFromEvent(event) {
    return popoverPlacement(
      event.currentTarget.getBoundingClientRect(),
      { width: window.innerWidth, height: window.innerHeight },
      { width: POPOVER_WIDTH, maxHeight: POPOVER_MAX_HEIGHT }
    )
  }

  function cancelHideTimer() {
    clearTimeout(hideTimer)
    hideTimer = null
  }

  function scheduleHide() {
    cancelHideTimer()
    hideTimer = setTimeout(() => { hover.value.visible = false }, HIDE_GRACE_MS)
  }

  /**
   * Affiche le popover après HOVER_DELAY_MS de survol continu (pas au simple
   * passage de la souris). `loadRows` renvoie les transactions { id, date,
   * payee, amount } qui composent le montant de la case.
   */
  function scheduleHover(event, title, loadRows) {
    cancelHideTimer()
    clearTimeout(showTimer)
    const { x, y, above } = positionFromEvent(event)
    const token = ++requestToken
    showTimer = setTimeout(async () => {
      hover.value = { visible: true, loading: true, x, y, above, title, rows: [], total: 0 }
      try {
        const content = popoverContent(await loadRows())
        if (token !== requestToken) return
        hover.value = { visible: true, loading: false, x, y, above, title, ...content }
      } catch {
        if (token !== requestToken) return
        hover.value = { ...hover.value, loading: false }
      }
    }, HOVER_DELAY_MS)
  }

  /**
   * Comme scheduleHover, sans effet sur une case prévisionnelle : son montant
   * est une moyenne estimée (voir ServiceRapport#completeAnalysisRow), jamais
   * une somme de transactions réelles — un détail serait trompeur.
   */
  function scheduleMonthHover(event, title, loadRows, isForecast) {
    if (isForecast) return
    scheduleHover(event, title, loadRows)
  }

  /** Quitte la case survolée : annule un affichage pas encore déclenché, ou programme la fermeture. */
  function onCellLeave() {
    clearTimeout(showTimer)
    requestToken++ // invalide une réponse déjà en vol
    scheduleHide()
  }

  function fetchCellTransactions(from, to, flow, filters = {}) {
    return ReportsAPI.cellTransactions({ from, to, flow: flow || undefined, ...filters })
  }

  function fetchMonthCellTransactions(monthKey, flow, filters = {}) {
    const { from, to } = monthRange(monthKey)
    return fetchCellTransactions(from, to, flow, filters)
  }

  /** Ouvre la page Transactions sur la période, le sens et la catégorie d'un montant. */
  function openTransactions(from, to, flow, filters, label) {
    router.push({ path: '/transactions', query: drillDownQuery({ from, to, flow, filters, label }) })
  }

  /** Même chose pour une case d'un mois ; sans effet sur un mois prévisionnel (pas encore de transactions réelles). */
  function openMonthTransactions(monthKey, flow, filters, label, isForecast) {
    if (isForecast) return
    const { from, to } = monthRange(monthKey)
    openTransactions(from, to, flow, filters, label)
  }

  /** Une dépense périodique : ses occurrences, retrouvées par une recherche exacte sur le tiers. */
  function openPayeeTransactions(payee) {
    if (!payee) return
    router.push({ path: '/transactions', query: { search: `"${payee}"` } })
  }

  /** En quittant la page : plus aucun minuteur ni réponse en vol ne doit rouvrir le popover. */
  function dispose() {
    requestToken++
    clearTimeout(showTimer)
    clearTimeout(hideTimer)
  }

  return {
    hover,
    scheduleHover,
    scheduleMonthHover,
    scheduleHide,
    cancelHideTimer,
    onCellLeave,
    fetchCellTransactions,
    fetchMonthCellTransactions,
    openTransactions,
    openMonthTransactions,
    openPayeeTransactions,
    dispose
  }
}

/** À appeler dans le setup de la page : crée le détail des cases et le met à disposition des cartes. */
export function provideCellDetail() {
  const cellDetail = createCellDetail(useRouter())
  provide(CELL_DETAIL_KEY, cellDetail)
  onBeforeUnmount(cellDetail.dispose)
  return cellDetail
}

/** Détail des cases fourni par la page (voir provideCellDetail). */
export function useCellDetail() {
  return inject(CELL_DETAIL_KEY)
}

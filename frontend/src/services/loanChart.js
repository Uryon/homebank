import { areaGradient, axisOptions, tooltipOptions } from './chartTheme'
import { formatAmount } from './format'
import { formatScheduleDate, yearTickLabel } from './loanMath'

const tickFormatter = new Intl.NumberFormat('fr-FR')

/**
 * Configuration Chart.js du capital restant dû d'un prêt (page Prêts) :
 * ligne pleine pour les échéances déjà passées, pointillés pour celles à
 * venir (même codage visuel que le graphique du tableau de bord), pour bien
 * montrer ce qui a déjà été remboursé.
 *
 * - `schedule` : échéancier ({ date, remainingBalance, paid }) ;
 * - `principal` : capital emprunté, pour la part « vous appartient » de l'infobulle ;
 * - `colors` : couleurs du thème (voir readChartColors).
 */
export function loanBalanceChartConfig(schedule, { principal, colors }) {
  const presentIndex = schedule.findIndex(r => !r.paid)
  const splitIndex = presentIndex === -1 ? schedule.length - 1 : presentIndex

  const pastData = schedule.map((r, i) => (i <= splitIndex ? r.remainingBalance : null))
  const futureData = schedule.map((r, i) => (i >= splitIndex ? r.remainingBalance : null))

  return {
    type: 'line',
    data: {
      labels: schedule.map(r => formatScheduleDate(r.date)),
      datasets: [
        {
          label: 'Remboursé',
          data: pastData,
          borderColor: colors.positive,
          backgroundColor: areaGradient(colors.positive, 0.22),
          fill: true,
          tension: 0.2,
          pointRadius: 0,
          borderWidth: 2.5,
          pointHoverRadius: 5,
          spanGaps: false
        },
        {
          label: 'Restant à rembourser',
          data: futureData,
          borderColor: colors.accent,
          backgroundColor: areaGradient(colors.accent, 0.14),
          borderDash: [6, 5],
          fill: true,
          tension: 0.2,
          pointRadius: 0,
          borderWidth: 2.5,
          pointHoverRadius: 5,
          spanGaps: false
        }
      ]
    },
    options: {
      responsive: true,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: tooltipOptions(colors, {
          boxPadding: 4,
          usePointStyle: true,
          callbacks: {
            label: (ctx) => `${ctx.dataset.label} : ${formatAmount(ctx.parsed.y)}`,
            // Meme stat que l'encart "vous appartiennent aujourd'hui" en
            // haut du graphique, mais recalculee au point survole (et non
            // figee sur le solde actuel) : capital = deja rembourse a cette
            // echeance = principal - solde restant du a cette echeance.
            afterBody: (items) => {
              if (!items.length || principal == null) return ''
              const row = schedule[items[0].dataIndex]
              if (!row) return ''
              const owned = (principal || 0) - row.remainingBalance
              return `Vous appartient à cette échéance : ${formatAmount(owned)}`
            }
          }
        })
      },
      scales: {
        // Axe : seulement les années (date complète dans l'infobulle), à la
        // première échéance de chaque année retenue — au plus ~12 repères.
        x: axisOptions(colors, { ticks: { maxRotation: 0, autoSkip: false, callback: (_value, index) => yearTickLabel(schedule, index) } }),
        y: axisOptions(colors, { withGrid: true, ticks: { callback: (v) => tickFormatter.format(v) } })
      }
    }
  }
}

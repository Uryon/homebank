import { describe, expect, it } from 'vitest'
import { loanBalanceChartConfig } from './loanChart'
import {
  formatScheduleDate, loanPayload, markCurrentRow, ownedAmount, ownedPercent, progressPercent, termMonthsError,
  totalMonthly, yearTickLabel
} from './loanMath'

const colors = { positive: '#0a0', accent: '#00a', grid: '#ccc', text: '#333', surface: '#fff' }

/** Échéancier mensuel de `months` mois à partir de janvier `startYear`, `paidCount` échéances payées. */
function schedule(months, startYear, paidCount) {
  return Array.from({ length: months }, (_, i) => ({
    date: `${startYear + Math.floor(i / 12)}-${String((i % 12) + 1).padStart(2, '0')}-05`,
    remainingBalance: 1000 - i * 10,
    paid: i < paidCount
  }))
}

describe('calculs d’un prêt', () => {
  const loan = { principal: 200000, currentRemainingBalance: 150000, monthlyPayment: 1000.26, monthlyInsurance: 30 }

  it('part remboursée, pourcentage et barre de progression bornée', () => {
    expect(ownedAmount(loan)).toBe(50000)
    expect(ownedPercent(loan)).toBe(25)
    expect(progressPercent({ principal: 100, currentRemainingBalance: -5 })).toBe(100)
    expect(progressPercent({ principal: 0 })).toBe(0)
    expect(totalMonthly(loan)).toBeCloseTo(1030.26)
    expect(totalMonthly({ monthlyPayment: 500 })).toBe(500)
  })

  it('durée : obligatoire et limitée à 600 mois, comme le backend', () => {
    expect(termMonthsError(0)).toBe('Saisissez la durée en mois.')
    expect(termMonthsError(601)).toContain('600 mois')
    expect(termMonthsError(240)).toBe('')
  })

  it('ne garde que les paramètres saisis pour recréer le prêt', () => {
    expect(loanPayload({ id: 3, name: 'Maison', principal: 1, currentRemainingBalance: 2, notes: '' }))
      .toEqual({ name: 'Maison', principal: 1, annualRate: undefined, termMonths: undefined, startDate: undefined, monthlyPayment: undefined, monthlyInsurance: undefined, notes: '' })
  })

  it('marque seulement la première échéance non payée comme courante', () => {
    expect(markCurrentRow(schedule(4, 2026, 2)).map(r => r.isCurrent)).toEqual([false, false, true, false])
    expect(markCurrentRow(schedule(2, 2026, 2)).some(r => r.isCurrent)).toBe(false)
  })

  it('date d’échéance en jj/mm/aaaa, « — » si absente', () => {
    expect(formatScheduleDate('2026-11-05')).toBe('05/11/2026')
    expect(formatScheduleDate(null)).toBe('—')
  })

  it('axe : une seule étiquette par année, au plus une douzaine sur un prêt de 25 ans', () => {
    const rows = schedule(300, 2020, 0)
    const labels = rows.map((_, i) => yearTickLabel(rows, i)).filter(Boolean)
    expect(labels[0]).toBe('2020')
    expect(labels.length).toBeLessThanOrEqual(13)
    expect(new Set(labels).size).toBe(labels.length)
  })
})

describe('loanBalanceChartConfig', () => {
  it('coupe la courbe à l’échéance courante : passé en ligne pleine, futur en pointillés, raccordés', () => {
    const config = loanBalanceChartConfig(schedule(5, 2026, 2), { principal: 1000, colors })
    const [past, future] = config.data.datasets

    expect(past.data).toEqual([1000, 990, 980, null, null])
    expect(future.data).toEqual([null, null, 980, 970, 960])
    expect(future.borderDash).toEqual([6, 5])
    expect(config.data.labels[0]).toBe('05/01/2026')
  })

  it('infobulle : part du bien remboursée à l’échéance survolée', () => {
    const config = loanBalanceChartConfig(schedule(5, 2026, 2), { principal: 1000, colors })
    const afterBody = config.options.plugins.tooltip.callbacks.afterBody

    expect(afterBody([{ dataIndex: 3 }])).toMatch(/^Vous appartient à cette échéance : 30,00/)
    expect(afterBody([])).toBe('')
    const sansPret = loanBalanceChartConfig(schedule(5, 2026, 2), { principal: null, colors })
    expect(sansPret.options.plugins.tooltip.callbacks.afterBody([{ dataIndex: 3 }])).toBe('')
  })

  it('prêt entièrement remboursé : tout est « passé »', () => {
    const config = loanBalanceChartConfig(schedule(3, 2026, 3), { principal: 1000, colors })
    expect(config.data.datasets[0].data).toEqual([1000, 990, 980])
    expect(config.data.datasets[1].data).toEqual([null, null, 980])
  })
})

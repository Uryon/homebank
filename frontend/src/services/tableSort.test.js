import { describe, expect, it } from 'vitest'
import { ariaSortOf, nextSort, sortIconOf } from './tableSort'

describe('tableSort', () => {
  it('nextSort : sens par défaut de la colonne au premier clic, inversé ensuite', () => {
    expect(nextSort(null, 'amount', { amount: 'desc' })).toEqual({ key: 'amount', dir: 'desc' })
    expect(nextSort(null, 'name')).toEqual({ key: 'name', dir: 'asc' })
    expect(nextSort({ key: 'amount', dir: 'desc' }, 'amount')).toEqual({ key: 'amount', dir: 'asc' })
  })

  it('aria-sort et icône suivent la colonne triée et son sens', () => {
    const sort = { key: 'date', dir: 'asc' }
    expect(ariaSortOf(sort, 'date')).toBe('ascending')
    expect(ariaSortOf({ key: 'date', dir: 'desc' }, 'date')).toBe('descending')
    expect(ariaSortOf(sort, 'payee')).toBe('none')
    expect(sortIconOf(sort, 'date')).toBe('arrowUp')
    expect(sortIconOf(sort, 'payee')).toBe('sortable')
  })
})

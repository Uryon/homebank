import { describe, expect, it } from 'vitest'
import { deleteLabel, keeperId, patternSuggestionFor, patternWithLabel, splitDuplicateGroups } from './duplicateGroups'

const tx = (id, extra = {}) => ({ id, validated: false, ...extra })

describe('groupes de doublons', () => {
  it('répartit les groupes en certains, probables et remboursements', () => {
    const groups = [{ certain: true }, { certain: false }, { certain: false, reimbursement: true }]
    const { certain, probable, reimbursement } = splitDuplicateGroups(groups)
    expect([certain.length, probable.length, reimbursement.length]).toEqual([1, 1, 1])
    expect(reimbursement[0]).toBe(groups[2])
  })

  it('garde la première occurrence validée, sinon la première', () => {
    expect(keeperId({ transactions: [tx(1), tx(2, { validated: true }), tx(3, { validated: true })] })).toBe(2)
    expect(keeperId({ transactions: [tx(1), tx(2)] })).toBe(1)
  })

  it('libellé du bouton selon le nombre de doublons', () => {
    expect(deleteLabel({ transactions: [tx(1), tx(2)] })).toBe('Supprimer le doublon')
    expect(deleteLabel({ transactions: [tx(1), tx(2), tx(3)] })).toBe('Garder 1 occurrence, supprimer 2 doublons')
  })
})

describe('apprentissage du motif après suppression d’un doublon probable', () => {
  const socares = { id: 7, name: 'Socares', pattern: 'SOCARES' }
  const payeesById = new Map([[7, socares]])
  const group = { transactions: [tx(1, { payee: 'PRLV SOCARES REF 123', payeeId: 9 }), tx(2, { payee: 'Socares', payeeId: 7 })] }

  it('propose le tiers de l’occurrence conservée avec le libellé supprimé', () => {
    expect(patternSuggestionFor({ id: 1, payee: ' PRLV ASSUR 06.2026 ' }, group, payeesById))
      .toEqual({ payee: socares, label: 'PRLV ASSUR 06.2026' })
  })

  it('rien à proposer si le motif reconnaît déjà le libellé, si le libellé est vide ou sans autre tiers', () => {
    expect(patternSuggestionFor({ id: 1, payee: 'SOCARES 10.06.2026' }, group, payeesById)).toBeNull()
    expect(patternSuggestionFor({ id: 1, payee: '  ' }, group, payeesById)).toBeNull()
    expect(patternSuggestionFor({ id: 2, payee: 'X' }, { transactions: [tx(2, { payeeId: 7 })] }, payeesById)).toBeNull()
    expect(patternSuggestionFor({ id: 1, payee: 'X' }, { transactions: [tx(1), tx(2, { payeeId: 99 })] }, payeesById)).toBeNull()
  })

  it('ajoute le libellé à la fin du motif édité, en un segment séparé par « ; »', () => {
    expect(patternWithLabel(' SOCARES ', 'PRLV ASSUR')).toBe('SOCARES;PRLV ASSUR')
    expect(patternWithLabel('', 'PRLV ASSUR')).toBe('PRLV ASSUR')
  })
})

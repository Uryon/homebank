import { describe, expect, it } from 'vitest'
import { CSV_HEADER, csvField, safeFileName, spreadsheetSafeText, transactionsToCsv } from './csvExport'

const categoriesById = new Map([
  [1, { id: 1, name: 'Alimentation', parentId: null }],
  [2, { id: 2, name: 'Supermarché', parentId: 1 }]
])
const accountsById = new Map([[10, { id: 10, name: 'Courant' }]])

describe('csvField', () => {
  it('laisse tel quel un texte simple et vide une valeur absente', () => {
    expect(csvField('Boulangerie')).toBe('Boulangerie')
    expect(csvField(null)).toBe('')
  })

  it('met entre guillemets un texte contenant un séparateur ou un guillemet', () => {
    expect(csvField('pain; croissants')).toBe('"pain; croissants"')
    expect(csvField('dit "bonjour"')).toBe('"dit ""bonjour"""')
  })
})

describe('transactionsToCsv', () => {
  it('écrit l’en-tête puis une ligne au format HomeBank par transaction', () => {
    const csv = transactionsToCsv([
      { date: '2026-03-01', accountId: 10, payee: 'Lidl', memo: 'courses', amount: -54.3, categoryId: 2, paymentMode: 'CARD' },
      { date: '2026-03-02', accountId: 10, payee: 'Employeur', memo: null, amount: 2000, categoryId: null, paymentMode: 'TRANSFER' }
    ], { categoriesById, accountsById })
    expect(csv.split('\r\n')).toEqual([
      CSV_HEADER,
      '01-03-2026;6;Courant;Lidl;courses;-54.30;Alimentation:Supermarché;',
      '02-03-2026;4;Courant;Employeur;;2000.00;;',
      ''
    ])
  })

  it('code un virement interne en 5 et une catégorie racine sans parent', () => {
    const csv = transactionsToCsv([
      { date: '2026-03-05', accountId: 99, payee: null, memo: 'épargne', amount: -200, categoryId: 1, paymentMode: 'TRANSFER', transferTransactionId: 7 }
    ], { categoriesById, accountsById })
    expect(csv.split('\r\n')[1]).toBe('05-03-2026;5;;;épargne;-200.00;Alimentation;')
  })
})

describe('spreadsheetSafeText', () => {
  it('neutralise ce qu’un tableur exécuterait comme une formule', () => {
    for (const text of ['=HYPERLINK("x")', '+33', '-remise', '@cmd', '  =1+1']) {
      expect(spreadsheetSafeText(text)).toBe(`'${text}`)
    }
  })

  it('laisse les textes ordinaires et une valeur absente', () => {
    expect(spreadsheetSafeText('Boulangerie - centre')).toBe('Boulangerie - centre')
    expect(spreadsheetSafeText(null)).toBe('')
  })

  it('protège les textes de la ligne sans toucher au montant négatif', () => {
    const csv = transactionsToCsv([
      { date: '2026-03-01', accountId: 10, payee: '=CMD', memo: '-note', amount: -5, categoryId: null, paymentMode: 'CARD' }
    ], { categoriesById, accountsById })
    expect(csv.split('\r\n')[1]).toBe("01-03-2026;6;Courant;'=CMD;'-note;-5.00;;")
  })
})

describe('safeFileName', () => {
  it('remplace les caractères interdits dans un nom de fichier', () => {
    expect(safeFileName('Livret A / B: 2026')).toBe('Livret A - B- 2026')
    expect(safeFileName('C:\\compte?')).toBe('C-compte-')
  })
})

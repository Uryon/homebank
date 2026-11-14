import { describe, expect, it } from 'vitest'
import { MAX_IMPORT_FILE_BYTES, formatFileSize, importFileProblem, importOutcome, lastImportLabel } from './importResult'

describe('importOutcome', () => {
  it('signale un succès avec le nombre de transactions importées', () => {
    expect(importOutcome({ imported: 12, duplicates: 3, skipped: 0 }))
      .toEqual({ type: 'success', message: '12 transactions importées.' })
  })

  it('signale une erreur quand aucune ligne n\'a pu être importée', () => {
    const outcome = importOutcome({ imported: 0, duplicates: 0, skipped: 40 })
    expect(outcome.type).toBe('error')
    expect(outcome.message).toContain('40 lignes en erreur')
  })

  it('avertit d\'un import partiel', () => {
    expect(importOutcome({ imported: 1, duplicates: 0, skipped: 2 }))
      .toEqual({ type: 'warning', message: '1 transaction importée, 2 lignes en erreur.' })
  })

  it('informe qu\'un relevé déjà importé n\'apporte rien de nouveau', () => {
    expect(importOutcome({ imported: 0, duplicates: 25, skipped: 0 }))
      .toEqual({ type: 'info', message: 'Aucune nouvelle transaction : 25 lignes déjà présentes.' })
  })

  it('avertit d\'un fichier vide', () => {
    expect(importOutcome({ imported: 0, duplicates: 0, skipped: 0 }).type).toBe('warning')
  })
})

describe('formatFileSize', () => {
  it('affiche en Ko sous 1 Mo (au moins 1 Ko), en Mo avec une décimale au-delà', () => {
    expect(formatFileSize(10)).toBe('1 Ko')
    expect(formatFileSize(150 * 1024)).toBe('150 Ko')
    expect(formatFileSize(3.45 * 1024 * 1024)).toBe('3,5 Mo')
  })
})

describe('importFileProblem', () => {
  const fichier = (name, size) => ({ name, size })

  it('accepte un fichier de la bonne extension, quelle que soit la casse', () => {
    expect(importFileProblem(fichier('releve.CSV', 2048), 'csv')).toBeNull()
    expect(importFileProblem(fichier('export.qif', 2048), 'qif')).toBeNull()
  })

  it('refuse une extension qui ne correspond pas au format choisi', () => {
    expect(importFileProblem(fichier('releve.qif', 2048), 'csv')).toContain("n'est pas un .csv")
  })

  it('refuse un fichier vide', () => {
    expect(importFileProblem(fichier('releve.csv', 0), 'csv')).toBe('Ce fichier est vide.')
  })

  it('refuse un fichier au-delà de la limite du serveur, mais pas à la limite', () => {
    expect(importFileProblem(fichier('releve.csv', MAX_IMPORT_FILE_BYTES), 'csv')).toBeNull()
    expect(importFileProblem(fichier('releve.csv', MAX_IMPORT_FILE_BYTES + 1), 'csv')).toContain('trop volumineux')
  })

  it('ne signale rien sans fichier', () => {
    expect(importFileProblem(null, 'csv')).toBeNull()
  })
})

describe('lastImportLabel', () => {
  const now = new Date(2026, 10, 13, 15, 0)

  it("indique aujourd'hui ou hier avec l'heure", () => {
    expect(lastImportLabel('2026-11-13T14:45:10', now)).toBe("aujourd'hui à 14:45")
    expect(lastImportLabel('2026-11-12T09:12:00', now)).toBe('hier à 09:12')
  })

  it('indique le nombre de jours et la date au-delà', () => {
    expect(lastImportLabel('2026-11-01T08:00:00', now)).toBe('il y a 12 jours (le 01/11/2026)')
  })

  it('renvoie null sans import ou pour une date illisible', () => {
    expect(lastImportLabel(null, now)).toBeNull()
    expect(lastImportLabel('pas une date', now)).toBeNull()
  })
})

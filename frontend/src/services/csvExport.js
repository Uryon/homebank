/**
 * Export CSV des transactions au format HomeBank, celui que relit l'import
 * (voir ServiceImport#importCsv) : un fichier exporté puis réimporté sur
 * le même compte est reconnu comme déjà présent (doublons), et un
 * réimport sur un autre compte recrée tiers, catégories et mémos.
 *   date;paymode;info;payee;memo;amount;category;tags
 * - date au format jj-mm-aaaa ;
 * - paymode : code numérique de HomeBank (ignoré par notre import) ;
 * - info : nom du compte (ignoré par l'import, utile quand l'export
 *   regroupe plusieurs comptes) ;
 * - category : « Parent:Enfant » pour une sous-catégorie ;
 * - tags : toujours vide.
 */

/** Codes des modes de paiement dans le CSV de HomeBank. */
const HOMEBANK_PAYMODE = {
  NONE: 0,
  CREDIT_CARD: 1,
  CHEQUE: 2,
  CASH: 3,
  TRANSFER: 4,
  CARD: 6,
  STANDING_ORDER: 7,
  ELECTRONIC_PAYMENT: 8,
  DEPOSIT: 9,
  DIRECT_DEBIT: 11
}
/** Virement interne (transaction liée à sa jumelle sur un autre compte). */
const HOMEBANK_INTERNAL_TRANSFER = 5

export const CSV_HEADER = 'date;paymode;info;payee;memo;amount;category;tags'

/** Champ CSV : entre guillemets (guillemets internes doublés) s'il contient un séparateur, un guillemet ou un retour à la ligne. */
export function csvField(value) {
  const text = value == null ? '' : String(value)
  return /[;"\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

/** "2026-03-01" → "01-03-2026" (jj-mm-aaaa, lu par ServiceImport#parseDate). */
function homeBankDate(isoDate) {
  const [year, month, day] = String(isoDate).split('-')
  return `${day}-${month}-${year}`
}

/**
 * Texte libre (libellé bancaire, mémo...) : une apostrophe devant ce qu'Excel
 * exécuterait comme une formule, par exemple un libellé « =HYPERLINK(...) »
 * reçu d'un tiers. L'import la retire (voir ServiceImport#withoutSpreadsheetGuard).
 */
export function spreadsheetSafeText(value) {
  const text = value == null ? '' : String(value)
  return /^\s*[=+\-@]/.test(text) ? `'${text}` : text
}

function categoryPath(categoryId, categoriesById) {
  const category = categoriesById.get(categoryId)
  if (!category) return ''
  const parent = category.parentId != null ? categoriesById.get(category.parentId) : null
  return parent ? `${parent.name}:${category.name}` : category.name
}

function paymodeCode(t) {
  if (t.transferTransactionId) return HOMEBANK_INTERNAL_TRANSFER
  return HOMEBANK_PAYMODE[t.paymentMode] ?? 0
}

/**
 * Texte CSV (en-tête compris, fins de ligne Windows comme HomeBank) des
 * transactions données, dans l'ordre reçu.
 */
export function transactionsToCsv(transactions, { categoriesById, accountsById }) {
  const lines = [CSV_HEADER]
  for (const t of transactions) {
    lines.push([
      homeBankDate(t.date),
      paymodeCode(t),
      spreadsheetSafeText(accountsById.get(t.accountId)?.name),
      spreadsheetSafeText(t.payee),
      spreadsheetSafeText(t.memo),
      Number(t.amount).toFixed(2),
      spreadsheetSafeText(categoryPath(t.categoryId, categoriesById)),
      ''
    ].map(csvField).join(';'))
  }
  return lines.join('\r\n') + '\r\n'
}

/** Nom de fichier sans caractère interdit par Windows (ex : compte « Livret A / B »). */
export function safeFileName(name) {
  return name.replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').trim()
}

/**
 * Propose le téléchargement du texte CSV. BOM UTF-8 en tête pour qu'Excel
 * affiche correctement les accents (l'import l'ignore, voir
 * ServiceImport#decodeImportedText).
 */
export function downloadCsv(fileName, csvText) {
  downloadBlob(fileName, new Blob(['﻿', csvText], { type: 'text/csv;charset=utf-8' }))
}

/** Fait enregistrer un fichier (Blob) par le navigateur sous le nom donné. */
export function downloadBlob(fileName, blob) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

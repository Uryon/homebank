/**
 * Interprétation du résultat d'un import CSV/QIF (ImportResult côté backend :
 * imported, duplicates, skipped, errors) pour la notification affichée
 * après l'import (voir ImportView.vue).
 */

/** Nombre d'erreurs affichées d'emblée : au-delà, la liste est repliée (fichier entièrement mal formé). */
export const VISIBLE_ERRORS_LIMIT = 50

function plural(count, singular, pluralForm) {
  return `${count} ${count > 1 ? pluralForm : singular}`
}

/**
 * Message et type de notification selon l'issue réelle de l'import, plutôt
 * qu'un « succès » systématique : un fichier au mauvais format (toutes les
 * lignes en erreur) ou déjà importé (que des doublons) doit se voir tout de suite.
 */
export function importOutcome(result) {
  const imported = Number(result?.imported || 0)
  const duplicates = Number(result?.duplicates || 0)
  const skipped = Number(result?.skipped || 0)

  if (imported === 0 && skipped > 0) {
    return {
      type: 'error',
      message: `Aucune transaction importée : ${plural(skipped, 'ligne en erreur', 'lignes en erreur')}. Vérifiez le format du fichier.`
    }
  }
  if (skipped > 0) {
    return {
      type: 'warning',
      message: `${plural(imported, 'transaction importée', 'transactions importées')}, ${plural(skipped, 'ligne en erreur', 'lignes en erreur')}.`
    }
  }
  if (imported === 0 && duplicates > 0) {
    return {
      type: 'info',
      message: `Aucune nouvelle transaction : ${plural(duplicates, 'ligne déjà présente', 'lignes déjà présentes')}.`
    }
  }
  if (imported === 0) {
    return { type: 'warning', message: 'Aucune transaction trouvée dans ce fichier.' }
  }
  return { type: 'success', message: `${plural(imported, 'transaction importée', 'transactions importées')}.` }
}

/**
 * Taille maximale acceptée par le backend (spring.servlet.multipart.max-file-size
 * dans application.properties) : contrôlée avant l'envoi pour ne pas
 * transférer un gros fichier juste pour recevoir un refus (413).
 */
export const MAX_IMPORT_FILE_BYTES = 8 * 1024 * 1024

/** Taille lisible : « 12 Ko », « 3,4 Mo » (au moins 1 Ko pour un fichier non vide). */
export function formatFileSize(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024 * 1024) return `${Math.max(1, Math.round(n / 1024))} Ko`
  return `${(n / 1024 / 1024).toFixed(1).replace('.', ',')} Mo`
}

/**
 * Raison pour laquelle ce fichier ne peut pas être importé dans ce format
 * (« csv » ou « qif »), ou null s'il est acceptable. Même contrôle pour le
 * sélecteur de fichier et le glisser-déposer.
 */
export function importFileProblem(file, format) {
  if (!file) return null
  if (!file.name.toLowerCase().endsWith(`.${format}`)) {
    return `Ce fichier n'est pas un .${format}. Changez de format ou de fichier.`
  }
  if (file.size === 0) return 'Ce fichier est vide.'
  if (file.size > MAX_IMPORT_FILE_BYTES) {
    return `Fichier trop volumineux (${formatFileSize(file.size)}, maximum ${formatFileSize(MAX_IMPORT_FILE_BYTES)}) : vérifiez qu'il s'agit bien d'un relevé bancaire, ou découpez-le en plusieurs imports.`
  }
  return null
}

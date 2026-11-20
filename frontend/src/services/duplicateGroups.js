import { labelToPatternSegment, matchesPatternField } from './payee'

/**
 * Groupes de l'onglet Doublons (voir ServiceDoublon#findDuplicateGroups), en
 * trois catégories :
 * - certains : même compte, date, montant et tiers canonique — mêmes critères
 *   que la détection à l'import (voir ServiceImport#processOperation) ;
 * - probables : même compte, date et montant, tiers différent ;
 * - remboursements : même tiers, montants opposés de même valeur absolue.
 */
export function splitDuplicateGroups(groups) {
  return {
    certain: groups.filter(g => g.certain),
    probable: groups.filter(g => !g.certain && !g.reimbursement),
    reimbursement: groups.filter(g => g.reimbursement)
  }
}

/**
 * Occurrence conservée automatiquement dans un groupe : la première déjà
 * validée (rapprochement bancaire, donc plus probablement la "vraie"
 * transaction) s'il y en a une, sinon simplement la première occurrence.
 */
export function keeperId(group) {
  const validated = group.transactions.find(t => t.validated)
  return (validated ?? group.transactions[0]).id
}

/** Libellé du bouton qui garde une occurrence et supprime les autres. */
export function deleteLabel(group) {
  const extra = group.transactions.length - 1
  return extra === 1 ? 'Supprimer le doublon' : `Garder 1 occurrence, supprimer ${extra} doublons`
}

/**
 * Tiers dont le motif mériterait d'apprendre le libellé d'une occurrence
 * supprimée d'un doublon probable : celui de l'occurrence CONSERVÉE, pour que
 * le prochain import de ce libellé (ex : prélèvement dont la référence change
 * chaque mois) le reconnaisse au lieu de recréer le même doublon. Null si
 * rien n'est à proposer : aucune autre occurrence avec un tiers, libellé vide,
 * ou motif qui le reconnaît déjà (voir matchesPatternField).
 *
 * `payeesById` : Map id -> tiers.
 */
export function patternSuggestionFor(deletedTransaction, group, payeesById) {
  const kept = group?.transactions.find(t => t.id !== deletedTransaction.id && t.payeeId)
  if (!kept) return null
  const payee = payeesById.get(kept.payeeId)
  if (!payee) return null
  const label = (deletedTransaction.payee || '').trim()
  if (!label || matchesPatternField(payee.pattern, label)) return null
  return { payee, label }
}

/** Motif édité par l'utilisateur, complété du libellé appris (segments séparés par « ; »). */
export function patternWithLabel(editedPattern, label) {
  return [editedPattern.trim(), labelToPatternSegment(label)].filter(Boolean).join(';')
}

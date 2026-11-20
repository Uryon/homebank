import { normalizeSearch } from './search'

/**
 * Transaction déjà enregistrée identique à celle en cours de saisie : même
 * compte, même date, même montant (au centime) et même tiers (casse et
 * accents ignorés), ou null. Sert à prévenir une double saisie avant
 * l'enregistrement (ex : dépense déjà importée depuis le relevé, ou
 * duplication oubliée sans changer la date). Appelée une seule fois, au
 * clic sur « Enregistrer », pas à chaque frappe.
 */
export function findIdenticalTransaction(entry, transactions) {
  const cents = Math.round(Number(entry.amount) * 100)
  const payee = normalizeSearch(entry.payee || '')
  return transactions.find(t =>
    t.accountId === entry.accountId
    && t.date === entry.date
    && Math.round(Number(t.amount) * 100) === cents
    && normalizeSearch(t.payee || '') === payee
  ) ?? null
}

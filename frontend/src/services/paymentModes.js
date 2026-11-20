/**
 * Modes de paiement proposés à la saisie d'une transaction (création et
 * modification groupée), dans l'ordre d'affichage. Les valeurs sont celles de
 * l'énumération ModePaiement du backend.
 */
export const PAYMENT_MODE_OPTIONS = Object.freeze([
  { value: 'NONE', label: 'Aucun' },
  { value: 'CASH', label: 'Espèces' },
  { value: 'CHEQUE', label: 'Chèque' },
  { value: 'TRANSFER', label: 'Virement' },
  { value: 'CARD', label: 'Carte' },
  { value: 'DIRECT_DEBIT', label: 'Prélèvement' },
  { value: 'STANDING_ORDER', label: 'Virement permanent' }
])

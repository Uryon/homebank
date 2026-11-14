/**
 * Edition en masse des transactions (Transactions.vue) : construction du
 * payload attendu par TransactionsAPI.bulkUpdate (TransactionBulkUpdateDTO
 * cote backend), ou chaque champ modifie est accompagne d'un drapeau
 * "<champ>Provided" pour distinguer "mettre a null" de "ne pas toucher".
 */

/** Champs modifiables en masse, dans l'ordre du formulaire. */
const BULK_FIELDS = ['categoryId', 'payee', 'paymentMode', 'periodicExpense', 'exceptional', 'validated']

/**
 * Formulaire vierge : Categorie/Tiers utilisent un drapeau *Touched explicite,
 * car leur valeur "non renseignee" (null / chaine vide) est aussi une valeur
 * metier legitime ("aucune categorie", tiers vide) qu'on doit pouvoir
 * distinguer de "champ pas touche". Les select simples (mode de paiement,
 * marqueurs, statut) demarrent a `null`, une valeur qui n'existe dans aucune
 * de leurs <option> reelles : "valeur !== null" suffit a savoir s'ils sont actifs.
 */
export function emptyBulkForm() {
  return {
    categoryTouched: false,
    categoryId: null,
    payeeTouched: false,
    payee: '',
    paymentMode: null,
    periodicExpense: null,
    exceptional: null,
    validated: null
  }
}

/** Champs effectivement modifies dans le formulaire, sous la forme { categoryId: bool, payee: bool, ... }. */
export function bulkTouchedFields(form) {
  return {
    categoryId: form.categoryTouched,
    payee: form.payeeTouched,
    paymentMode: form.paymentMode !== null,
    periodicExpense: form.periodicExpense !== null,
    exceptional: form.exceptional !== null,
    validated: form.validated !== null
  }
}

/** Nombre de champs modifies (0 = rien a appliquer). */
export function countTouchedFields(touchedFields) {
  return BULK_FIELDS.filter(field => touchedFields[field]).length
}

/**
 * Payload bulkUpdate pour `ids` : seuls les champs marques dans
 * `touchedFields` sont envoyes, avec la valeur lue dans `values` (le
 * formulaire a l'application, l'etat d'avant modification a l'annulation).
 */
export function bulkUpdatePayload(ids, touchedFields, values) {
  const payload = { ids }
  for (const field of BULK_FIELDS) {
    if (!touchedFields[field]) continue
    payload[`${field}Provided`] = true
    payload[field] = values[field]
  }
  return payload
}

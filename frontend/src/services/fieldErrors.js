import { reactive } from 'vue'

/**
 * Erreurs de saisie affichées sous les champs d'un formulaire (voir
 * components/FieldError.vue), plutôt qu'en notification dans un coin de
 * l'écran : on voit quel champ corriger, et le curseur y est placé.
 *
 * `idsByField` associe chaque champ validé à l'id de son <input>, dans
 * l'ordre du formulaire (le premier champ en erreur reçoit le focus). Le
 * message d'un champ a pour id `<id du champ>-error`.
 *
 * Usage :
 *   const { errors, check, clear, invalidAttrs } = useFieldErrors({ name: 'account-modal-name' })
 *   if (!check({ name: form.name.trim() ? '' : 'Saisissez un nom.' })) return
 *   <input id="account-modal-name" v-bind="invalidAttrs('name')" @input="clear('name')" />
 *   <FieldError id="account-modal-name-error" :message="errors.name" />
 */
export function useFieldErrors(idsByField, { focus = focusById } = {}) {
  const fields = Object.keys(idsByField)
  const errors = reactive(Object.fromEntries(fields.map(field => [field, ''])))

  /** Remplace toutes les erreurs ; vrai si aucune. Place le curseur sur le premier champ en erreur. */
  function check(messages) {
    for (const field of fields) errors[field] = messages[field] || ''
    const firstInvalid = fields.find(field => errors[field])
    if (firstInvalid) focus(idsByField[firstInvalid])
    return !firstInvalid
  }

  /** Efface l'erreur d'un champ (dès qu'il est modifié), ou toutes sans argument. */
  function clear(field) {
    for (const key of field ? [field] : fields) errors[key] = ''
  }

  /** Attributs d'accessibilité du champ : marqué invalide et relié à son message. */
  function invalidAttrs(field) {
    return errors[field]
      ? { 'aria-invalid': 'true', 'aria-describedby': `${idsByField[field]}-error` }
      : {}
  }

  return { errors, check, clear, invalidAttrs }
}

function focusById(id) {
  document.getElementById(id)?.focus()
}

<template>
  <input
    ref="inputEl"
    class="amount-input"
    type="text"
    inputmode="decimal"
    v-model="text"
    :id="id"
    :title="attrTitle"
    @keydown.enter="commit"
    @blur="commit"
  />
</template>

<script setup>
/**
 * Champ de saisie de montant : tape comme un input numerique classique (le
 * curseur se place normalement, rien n'est preselectionne), mais reconnait
 * aussi un calcul a la validation (blur ou Entree) : "21000-500+300" ou
 * "21000 - 500 + 300", chaine de termes +/- (autant que voulu) evaluee de
 * gauche a droite. Le resultat REMPLACE toujours le montant : un signe en
 * tete fait partie de la valeur ("-4" vaut -4, jamais "montant actuel - 4").
 * Le calcul ne s'applique jamais pendant la frappe, seulement a la
 * validation, pour pouvoir taper "21000-500+300" et voir ce texte tel quel
 * avant de valider.
 *
 * Validation geree a la fois par blur et par Entree (et non par un seul des
 * deux) : un parent qui lit le montant des qu'il detecte une confirmation
 * (Entree geree plus haut dans l'arbre, clic sur un bouton "Enregistrer"...)
 * doit toujours voir la valeur a jour, quel que soit le chemin de
 * confirmation utilise. Idempotent : revalider un texte deja applique ne
 * remet rien en cause (evite un plantage si un blur tardif se declenche
 * apres qu'un parent a deja retire ce champ de l'edition, ex : ligne de
 * transaction qui se referme au moment meme de la confirmation).
 */
import { ref, watch } from 'vue'
import { formatAmountForInput as formatForDisplay } from '../services/format'

const props = defineProps({
  modelValue: { type: Number, default: null },
  // Transmis tel quel au <input> interne, pour permettre a l'appelant
  // d'associer un <label for="..."> (accessibilite).
  id: { type: String, default: null }
})
const emit = defineEmits(['update:modelValue'])

const inputEl = ref(null)
const attrTitle = "Tapez un montant, ou un calcul +/- (ex : 21000-500+300) : il s'applique en validant (Entrée ou en quittant le champ)"

const text = ref(formatForDisplay(props.modelValue))

// Reste synchronise si la valeur change depuis l'exterieur (annulation d'edition,
// ouverture d'une autre ligne, reinitialisation du formulaire...).
watch(() => props.modelValue, (value) => {
  text.value = formatForDisplay(value)
})

/**
 * Interprete le texte courant : { value, valid }. value peut etre null
 * (champ vide -> "aucun montant") sans que ce soit invalide. valid=false
 * signifie un texte illisible (ex : "abc"), a ignorer sans rien casser.
 */
function resolve(raw) {
  const trimmed = raw.trim()
  if (trimmed === '') return { value: null, valid: true }

  // Normalise virgule decimale et espaces (y compris un espace comme
  // separateur de milliers, ex : "21 000") avant d'evaluer.
  const normalized = trimmed.replaceAll(',', '.').replaceAll(/\s+/g, '')

  // Chaine de termes +/- : un premier nombre optionnellement signe, puis
  // autant de "+/- nombre" que voulu (ex : "21000-500+300", "-500+300").
  if (/^[+-]?\d+(?:\.\d+)?([+-]\d+(?:\.\d+)?)*$/.test(normalized)) {
    const terms = normalized.match(/[+-]?\d+(?:\.\d+)?/g) || []
    const total = terms.reduce((sum, term) => sum + Number(term), 0)
    return { value: Math.round(total * 100) / 100, valid: true }
  }

  const value = Number(normalized)
  return Number.isFinite(value) ? { value, valid: true } : { value: null, valid: false }
}

function commit() {
  const { value, valid } = resolve(text.value)
  if (!valid) {
    // Saisie illisible : on revient au montant precedent.
    text.value = formatForDisplay(props.modelValue)
    return
  }
  if (value !== props.modelValue) emit('update:modelValue', value)
  text.value = formatForDisplay(value)
}

defineExpose({ focus: () => inputEl.value?.focus() })
</script>

<style scoped>
/* Montants alignés à droite partout, comme dans les tableaux */
.amount-input {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>

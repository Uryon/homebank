<template>
  <div class="modal-overlay" @click.self="emit('cancel')">
    <div v-dialog="() => emit('cancel')" class="modal" role="dialog" aria-modal="true">
      <h2>Nouveau tiers</h2>
      <div class="form-grid">
        <div class="form-field span-2">
          <label for="new-payee-name">Nom du tiers</label>
          <input id="new-payee-name" v-model="form.name" maxlength="255" placeholder="Ex : Carrefour" v-bind="invalidAttrs('name')" @input="clear('name')" />
          <FieldError id="new-payee-name-error" :message="errors.name" />
        </div>
        <div class="form-field span-2">
          <label for="new-payee-pattern">Motif de reconnaissance</label>
          <input id="new-payee-pattern" v-model="form.pattern" placeholder="Ex : Carrefour" v-bind="invalidAttrs('pattern')" @input="clear('pattern')" />
          <FieldError id="new-payee-pattern-error" :message="errors.pattern" />
          <div class="text-hint">
            Tapez simplement le texte à repérer (ex : <code>Carrefour</code>) : il sera
            reconnu partout où il apparaît dans le libellé de la transaction, quelle que
            soit la casse (CARREFOUR, Carrefour, carrefour…). Pas besoin d'ajouter d'étoiles,
            c'est fait automatiquement. Pour des cas plus précis, vous pouvez tout de même
            utiliser <code>*</code> comme joker (ex : <code>Carrefour*Paris</code>).
          </div>
        </div>
        <div class="form-field span-2">
          <label for="new-payee-amount">Montant associé (laisser vide = s'applique à tous les montants)</label>
          <AmountInput id="new-payee-amount" v-model="form.amount" placeholder="Ex : 10" />
        </div>
        <div class="form-field span-2">
          <label for="new-payee-category">Catégorie associée (appliquée automatiquement aux transactions de ce tiers)</label>
          <CategoryAutocomplete
            id="new-payee-category"
            v-model="form.categoryId"
            :categories="categories"
            placeholder="Rechercher une catégorie… (ex : ali)"
          />
        </div>
      </div>
      <div class="modal-actions">
        <button class="btn secondary" @click="emit('cancel')">Annuler</button>
        <button class="btn" @click="submit">Enregistrer</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Fenêtre « Nouveau tiers » de la page Tiers : saisie et contrôle des champs
 * obligatoires ; l'enregistrement est fait par la page (événement save).
 */
import { ref } from 'vue'
import AmountInput from './AmountInput.vue'
import CategoryAutocomplete from './CategoryAutocomplete.vue'
import FieldError from './FieldError.vue'
import { useFieldErrors } from '../services/fieldErrors'

const props = defineProps({
  /** Valeurs de départ (ex : nom et motif pré-remplis depuis un lien), champs absents vides. */
  initial: { type: Object, default: () => ({}) },
  /** Catégories à plat (voir flattenCategories), proposées par l'autocomplétion. */
  categories: { type: Array, required: true }
})
const emit = defineEmits(['save', 'cancel'])

const form = ref({ name: '', pattern: '', amount: null, categoryId: null, ...props.initial })
const { errors, check, clear, invalidAttrs } = useFieldErrors({ name: 'new-payee-name', pattern: 'new-payee-pattern' })

function submit() {
  const valid = check({
    name: form.value.name.trim() ? '' : 'Saisissez le nom du tiers.',
    pattern: form.value.pattern.trim() ? '' : 'Saisissez le texte à reconnaître.'
  })
  if (valid) emit('save', { ...form.value })
}
</script>

<style scoped>
.text-hint {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 4px;
  line-height: 1.4;
}
.text-hint code {
  background: var(--ink-800);
  padding: 3px 6px;
  border-radius: 5px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
</style>

<template>
  <div class="modal-overlay" @click.self="emit('cancel')">
    <div v-dialog="() => emit('cancel')" class="modal" role="dialog" aria-modal="true">
      <h2>Nouvelle catégorie</h2>
      <div class="form-grid">
        <div class="form-field">
          <label for="new-category-icon">Icône</label>
          <input id="new-category-icon" v-model="form.icon" placeholder="🙂" maxlength="4" class="icon-field icon-field-full" />
        </div>
        <div class="form-field">
          <label for="new-category-name">Nom</label>
          <input id="new-category-name" v-model="form.name" maxlength="255" placeholder="Ex : Alimentation" v-bind="invalidAttrs('name')" @input="clear('name')" />
          <FieldError id="new-category-name-error" :message="errors.name" />
        </div>
        <div class="form-field">
          <label for="new-category-parent">Catégorie parente</label>
          <select id="new-category-parent" v-model.number="form.parentId">
            <option :value="null">Aucune (catégorie racine)</option>
            <option v-for="r in roots" :key="r.id" :value="r.id">{{ iconPrefix(r.icon) }}{{ r.name }}</option>
          </select>
        </div>
        <div class="form-field" v-if="!form.parentId">
          <label for="new-category-type">Type</label>
          <select id="new-category-type" v-model="form.type">
            <option v-for="option in CATEGORY_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </div>
        <div class="form-field" v-else>
          <span class="form-field-label">Type</span>
          <div class="inherited-type inherited-type-modal">
            {{ typeLabel(rootTypeById.get(form.parentId)) }} (hérité de la catégorie parente)
          </div>
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
 * Fenêtre « Nouvelle catégorie » de la page Catégories : saisie et contrôle
 * du nom ; l'enregistrement est fait par la page (événement save). Une
 * sous-catégorie prend le type de sa catégorie parente.
 */
import { ref } from 'vue'
import FieldError from './FieldError.vue'
import { useFieldErrors } from '../services/fieldErrors'
import { CATEGORY_TYPE_OPTIONS, categoryIconPrefix as iconPrefix, categoryTypeLabel as typeLabel } from '../services/categories'

const props = defineProps({
  /** Catégories racines, proposées comme parente. */
  roots: { type: Array, required: true },
  /** Type de chaque racine par id, affiché comme type hérité d'une sous-catégorie. */
  rootTypeById: { type: Map, required: true },
  /** Parente pré-choisie (bouton « + » d'une racine), null pour une racine. */
  initialParentId: { type: Number, default: null }
})
const emit = defineEmits(['save', 'cancel'])

const form = ref({ name: '', parentId: props.initialParentId, type: 'EXPENSE', icon: '' })
const { errors, check, clear, invalidAttrs } = useFieldErrors({ name: 'new-category-name' })

function submit() {
  if (!check({ name: form.value.name.trim() ? '' : 'Saisissez le nom de la catégorie.' })) return
  emit('save', {
    name: form.value.name,
    type: form.value.type,
    parentId: form.value.parentId,
    icon: form.value.icon || null
  })
}
</script>

<style scoped>
/* Champ icône (emoji) : centré, sur toute la largeur de sa colonne (voir aussi CategoryInlineEditor.vue). */
.icon-field {
  width: 44px;
  flex: 0 0 auto;
  text-align: center;
}
.icon-field-full { width: 100%; }
.inherited-type { color: var(--text-muted); font-size: 12px; }
.inherited-type-modal { padding-top: 8px; font-size: 13px; }
</style>

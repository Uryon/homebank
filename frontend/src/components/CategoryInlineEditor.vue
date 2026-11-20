<template>
  <div class="cat-row-inline inline-edit-fields" @click.stop>
    <input v-model="icon" aria-label="Icône" placeholder="🙂" maxlength="4" class="icon-field" />
    <span class="cat-name-edit">
      <input
        id="category-edit-name"
        ref="nameField"
        v-model="name"
        maxlength="255"
        aria-label="Nom"
        placeholder="Nom"
        class="cat-name-field"
        v-bind="nameAttrs"
        @input="$emit('name-input')"
      />
      <FieldError id="category-edit-name-error" :message="nameError" />
    </span>
    <select v-if="!isChild" v-model="type" aria-label="Type">
      <option v-for="option in CATEGORY_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
    <template v-else>
      <select v-model.number="parentId" aria-label="Catégorie parente">
        <option v-for="r in roots" :key="r.id" :value="r.id">{{ iconPrefix(r.icon) }}{{ r.name }}</option>
      </select>
      <span class="inherited-type">
        {{ typeLabel(rootTypeById.get(form.parentId)) }} (hérité du parent)
      </span>
    </template>
    <button class="btn btn-icon" title="Enregistrer (Entrée)" aria-label="Enregistrer" @click="$emit('confirm')"><Icon name="check" /></button>
    <button class="btn secondary btn-icon" title="Annuler (Échap)" aria-label="Annuler" @click="$emit('cancel')"><Icon name="x" /></button>
  </div>
</template>

<script setup>
/**
 * Édition en ligne d'une catégorie (icône, nom, puis type pour une racine ou
 * catégorie parente pour une sous-catégorie, dont le type est hérité).
 * Le formulaire reste dans la page, qui applique chaque saisie (événement
 * update-field) : une catégorie « Les deux » s'affiche dans les deux colonnes
 * et ses deux éditeurs partagent la saisie, et Entrée sur la ligne
 * l'enregistre (voir onRowEnter dans Categories.vue).
 */
import { computed, ref } from 'vue'
import FieldError from './FieldError.vue'
import Icon from './Icon.vue'
import { CATEGORY_TYPE_OPTIONS, categoryIconPrefix as iconPrefix, categoryTypeLabel as typeLabel } from '../services/categories'

const props = defineProps({
  /** { name, type, parentId, icon } : la saisie en cours. */
  form: { type: Object, required: true },
  /** Sous-catégorie : choix du parent au lieu du type. */
  isChild: { type: Boolean, default: false },
  /** Catégories racines, cibles possibles comme parent. */
  roots: { type: Array, required: true },
  rootTypeById: { type: Map, required: true },
  nameError: { type: String, default: '' },
  /** Attributs d'erreur du champ Nom (voir useFieldErrors#invalidAttrs). */
  nameAttrs: { type: Object, default: () => ({}) }
})

const emit = defineEmits({
  /** (champ, valeur) : un seul champ, appliqué par la page au formulaire courant. */
  'update-field': (key) => typeof key === 'string',
  'name-input': null,
  confirm: null,
  cancel: null
})

/**
 * Champ du formulaire lié en v-model. Seul le champ modifié remonte : renvoyer
 * tout le formulaire depuis la prop écraserait une saisie faite dans un autre
 * champ pendant le même tick (la prop n'est rafraîchie qu'au rendu suivant).
 */
function formField(key) {
  return computed({
    get: () => props.form[key],
    set: (value) => emit('update-field', key, value)
  })
}
const icon = formField('icon')
const name = formField('name')
const type = formField('type')
const parentId = formField('parentId')

const nameField = ref(null)
/** Place le curseur sur le Nom (ouverture de l'édition, erreur de saisie). */
defineExpose({ focus: () => nameField.value?.focus() })
</script>

<style scoped>
.cat-row-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 4px 0;
}
/* Champ icône (emoji) : gabarit court et centré, plutôt que la largeur pleine par défaut d'un input. */
.icon-field {
  width: 44px;
  flex: 0 0 auto;
  text-align: center;
}
/* Champ Nom + son éventuel message d'erreur, empilés ; occupe la place libre de la ligne. */
.cat-name-edit { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.cat-name-field { width: 100%; }
.inherited-type { color: var(--text-muted); font-size: 12px; }
</style>

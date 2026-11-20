<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div v-dialog="() => $emit('close')" class="modal bulk-edit-modal inline-edit-fields" role="dialog" aria-modal="true">
      <h2>Modifier {{ countLabel }}</h2>
      <div class="text-hint modal-hint">
        Modifiez uniquement les champs à changer : un champ non touché reste inchangé sur chaque transaction.
      </div>

      <div class="bulk-section">
        <div class="bulk-section-title">Catégorisation</div>

        <div class="bulk-field" :class="{ 'bulk-field-active': form.categoryTouched }">
          <div class="bulk-field-label">Catégorie</div>
          <div class="bulk-field-control">
            <CategoryAutocomplete
              id="bulk-category"
              aria-label="Catégorie"
              :model-value="form.categoryId"
              @update:model-value="onCategoryChange"
              :categories="categories"
              placeholder="Ne pas modifier"
            />
          </div>
          <span v-if="form.categoryTouched" class="bulk-field-check"><Icon name="check" /></span>
        </div>

        <div class="bulk-field" :class="{ 'bulk-field-active': form.payeeTouched }">
          <div class="bulk-field-label">Tiers</div>
          <div class="bulk-field-control">
            <PayeeAutocomplete
              id="bulk-payee"
              aria-label="Tiers"
              :model-value="form.payee"
              @update:model-value="onPayeeChange"
              :payees="payees"
              placeholder="Ne pas modifier"
            />
          </div>
          <span v-if="form.payeeTouched" class="bulk-field-check"><Icon name="check" /></span>
        </div>

        <div class="bulk-field" :class="{ 'bulk-field-active': form.paymentMode !== null }">
          <div class="bulk-field-label">Mode de paiement</div>
          <div class="bulk-field-control">
            <select v-model="form.paymentMode" aria-label="Mode de paiement">
              <option :value="null">Ne pas modifier</option>
              <option v-for="mode in PAYMENT_MODE_OPTIONS" :key="mode.value" :value="mode.value">{{ mode.label }}</option>
            </select>
          </div>
          <span v-if="form.paymentMode !== null" class="bulk-field-check"><Icon name="check" /></span>
        </div>
      </div>

      <div v-for="section in FLAG_SECTIONS" :key="section.title" class="bulk-section">
        <div class="bulk-section-title">{{ section.title }}</div>

        <div v-for="field in section.fields" :key="field.key" class="bulk-field" :class="{ 'bulk-field-active': form[field.key] !== null }">
          <div class="bulk-field-label">{{ field.label }}</div>
          <div class="bulk-field-control">
            <select v-model="form[field.key]" :aria-label="field.label">
              <option :value="null">Ne pas modifier</option>
              <option :value="true">{{ field.yes }}</option>
              <option :value="false">{{ field.no }}</option>
            </select>
          </div>
          <span v-if="form[field.key] !== null" class="bulk-field-check"><Icon name="check" /></span>
        </div>
      </div>

      <div class="bulk-footer">
        <button
          v-if="touchedFieldCount > 0"
          type="button"
          class="bulk-clear-link"
          @click="reset"
        >
          Réinitialiser ({{ pluralize(touchedFieldCount, 'champ modifié', 'champs modifiés') }})
        </button>
        <span v-else class="text-hint">Aucune modification pour l'instant</span>

        <div class="bulk-footer-actions">
          <button class="btn secondary" @click="$emit('close')">Annuler</button>
          <button class="btn" :disabled="touchedFieldCount === 0 || saving" @click="submit">
            {{ saving ? 'Application…' : `Appliquer à ${countLabel}` }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Modification en masse des transactions sélectionnées. Plus de case à
 * cocher par champ : modifier un champ suffit à le marquer « à appliquer »
 * (voir emptyBulkForm/bulkTouchedFields dans services/bulkEdit.js). La
 * modale ne fait que la saisie ; l'appel au serveur et l'« Annuler » restent
 * dans la page (voir saveBulkEdit dans Transactions.vue).
 */
import { computed, ref } from 'vue'
import CategoryAutocomplete from './CategoryAutocomplete.vue'
import PayeeAutocomplete from './PayeeAutocomplete.vue'
import Icon from './Icon.vue'
import { bulkTouchedFields, countTouchedFields, emptyBulkForm } from '../services/bulkEdit'
import { PAYMENT_MODE_OPTIONS } from '../services/paymentModes'
import { pluralize } from '../services/format'

defineProps({
  /** « 3 transactions » : titre et bouton d'application. */
  countLabel: { type: String, required: true },
  /** Catégories aplaties (voir flattenCategories), comme pour CategoryAutocomplete. */
  categories: { type: Array, required: true },
  payees: { type: Array, required: true },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits({
  close: null,
  /** Formulaire avec au moins un champ modifié (voir bulkUpdatePayload). */
  submit: (form) => form != null
})

/** Marqueurs et statut : listes Oui/Non, « Ne pas modifier » par défaut (null). */
const FLAG_SECTIONS = [
  {
    title: 'Marqueurs',
    fields: [
      { key: 'periodicExpense', label: 'Dépense périodique', yes: 'Oui', no: 'Non' },
      { key: 'exceptional', label: 'Exceptionnelle', yes: 'Oui', no: 'Non' }
    ]
  },
  {
    title: 'Statut',
    fields: [{ key: 'validated', label: 'Validée / en attente', yes: 'Validée', no: 'En attente' }]
  }
]

const form = ref(emptyBulkForm())
const touchedFieldCount = computed(() => countTouchedFields(bulkTouchedFields(form.value)))

/** Revient à l'état « aucune modification », sans fermer la modale. */
function reset() {
  form.value = emptyBulkForm()
}

function onCategoryChange(value) {
  form.value.categoryId = value
  form.value.categoryTouched = true
}

function onPayeeChange(value) {
  form.value.payee = value
  form.value.payeeTouched = true
}

function submit() {
  if (touchedFieldCount.value === 0) return
  emit('submit', form.value)
}
</script>

<style scoped>
.modal-hint { margin-bottom: 20px; }

/* Modale un peu plus large que le gabarit standard (520px) : les lignes
   label + champ + pastille ont besoin de plus de place pour respirer. */
.bulk-edit-modal {
  width: 600px;
}

/*
 * Chaque groupe de champs est une "carte" en creux (fond ink-950, le fond de
 * page, plus sombre que le blanc de la modale) plutôt qu'une simple liste
 * séparée par des traits : lisibilité et cohérence avec le reste de l'appli
 * (cartes du tableau de bord, etc.), voir CLAUDE.md.
 */
.bulk-section {
  margin-bottom: 14px;
  padding: 14px 16px;
  background: var(--ink-950);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.bulk-section-title {
  font-size: 13px;
  font-weight: 650;
  color: var(--text-muted);
  margin-bottom: 10px;
}

/*
 * Modifier un champ (select ou autocomplete) suffit à le marquer « à
 * appliquer » (*Touched pour Catégorie/Tiers, valeur !== null pour les
 * select). bulk-field-active met alors la ligne en évidence (fond blanc +
 * liseré azur + pastille ✓), pour que l'utilisateur voie d'un coup d'œil ce
 * qui sera réellement appliqué avant de cliquer sur « Appliquer ».
 */
.bulk-field {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  margin-bottom: 6px;
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  transition: background 0.12s, border-color 0.12s;
}
.bulk-field:last-child {
  margin-bottom: 0;
}
.bulk-field-active {
  background: var(--ink-900);
  border-color: var(--azure);
  box-shadow: var(--shadow-sm);
}
.bulk-field-label {
  flex: 0 0 150px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.bulk-field-active .bulk-field-label {
  color: var(--azure);
}
.bulk-field-control {
  flex: 1;
  min-width: 0;
}
/* Listes déroulantes alignées sur la largeur des champs texte de la modale. */
.bulk-field-control select {
  width: 100%;
}
.bulk-field-check {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--azure);
  color: var(--on-accent);
}
.bulk-field-check .icon { width: 12px; height: 12px; stroke-width: 2.6; }

.bulk-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 20px;
}
.bulk-footer-actions {
  display: flex;
  gap: 10px;
}
.bulk-clear-link {
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  padding: 4px 0;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.bulk-clear-link:hover {
  color: var(--text-primary);
}
</style>

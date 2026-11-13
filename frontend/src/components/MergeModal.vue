<template>
  <div class="modal-overlay" @click.self="$emit('cancel')">
    <div v-dialog="() => $emit('cancel')" class="modal" role="dialog" aria-modal="true">
      <h2>{{ title }}</h2>
      <p v-if="description" class="merge-description">{{ description }}</p>

      <div class="form-field">
        <label for="merge-target">{{ targetLabel }}</label>
        <select id="merge-target" :value="modelValue" @change="$emit('update:modelValue', Number($event.target.value))">
          <option v-for="item in items" :key="item.id" :value="item.id">
            {{ optionLabel(item) }}
          </option>
        </select>
      </div>

      <slot />

      <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
        <button class="btn secondary" @click="$emit('cancel')">Annuler</button>
        <button class="btn" :disabled="confirming" @click="$emit('confirm')">
          {{ confirming ? confirmingLabel : confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' },
  items: { type: Array, required: true },
  optionLabel: { type: Function, required: true },
  modelValue: { type: Number, default: null },
  targetLabel: { type: String, default: 'Élément à conserver' },
  confirming: { type: Boolean, default: false },
  confirmLabel: { type: String, default: 'Confirmer la fusion' },
  confirmingLabel: { type: String, default: 'Fusion en cours…' }
})

defineEmits(['update:modelValue', 'confirm', 'cancel'])
</script>

<style scoped>
.merge-description {
  color: var(--text-muted);
  font-size: 14px;
  margin-bottom: 16px;
}
</style>

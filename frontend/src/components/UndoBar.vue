<template>
  <div v-if="action" class="undo-bar" :class="{ raised }" role="status">
    <span>{{ action.label }}</span>
    <button class="btn secondary" :disabled="undoing" @click="emit('undo')">
      {{ undoing ? 'Annulation…' : 'Annuler' }}<kbd class="undo-kbd">Ctrl+Z</kbd>
    </button>
  </div>
</template>

<script setup>
/** Bandeau « Annuler » de la dernière action (voir services/lastAction.js), aussi déclenché par Ctrl+Z. */
import { onBeforeUnmount, onMounted } from 'vue'
import { isUndoShortcut } from '../services/lastAction'

const props = defineProps({
  // { label, undo } renvoyé par useLastAction, ou null (bandeau masqué).
  action: { type: Object, default: null },
  undoing: { type: Boolean, default: false },
  // Remonté au-dessus d'une barre flottante (ex : sélection multiple de Transactions).
  raised: { type: Boolean, default: false }
})

const emit = defineEmits(['undo'])

function onKeydown(event) {
  if (!props.action || props.undoing || !isUndoShortcut(event)) return
  event.preventDefault()
  emit('undo')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
/*
 * Position fixe en bas de l'écran pour rester visible quel que soit le
 * scroll de la page ou du tableau, au-dessus des modales (z-index supérieur à
 * .modal-overlay, voir style.css) au cas où une action serait annulée alors
 * qu'une modale est déjà ouverte.
 */
.undo-bar {
  position: fixed;
  left: 50%;
  bottom: 24px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  background: var(--text-primary);
  color: var(--ink-900);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 60;
}
.undo-bar.raised { bottom: 84px; }
.undo-bar .btn.secondary {
  background: transparent;
  border: 1px solid color-mix(in srgb, var(--ink-900) 35%, transparent);
  color: var(--ink-900);
}
.undo-kbd {
  margin-left: 8px;
  font: inherit;
  font-size: 11px;
  opacity: 0.65;
}
/* Pas de clavier physique sur écran tactile : indication inutile. */
@media (hover: none) {
  .undo-kbd { display: none; }
}
.undo-bar .btn.secondary:hover {
  background: color-mix(in srgb, var(--ink-900) 12%, transparent);
}
</style>

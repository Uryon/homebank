<template>
  <button
    type="button"
    class="section-toggle"
    :aria-expanded="!collapsed"
    :aria-label="(collapsed ? 'Déplier ' : 'Replier ') + label"
    :title="collapsed ? 'Déplier' : 'Replier'"
    @click="toggle"
  >
    <Icon :name="collapsed ? 'chevron-right' : 'chevron-down'" size="sm" />
  </button>
</template>

<script setup>
/** Bouton replier / déplier d'une carte du tableau de bord (voir services/collapsibleSections.js). */
import Icon from '../Icon.vue'
import { useCollapsibleSection } from '../../services/collapsibleSections'

const props = defineProps({
  /** Identifiant mémorisé de la section. */
  section: { type: String, required: true },
  /** Nom de la carte, pour les lecteurs d'écran. */
  label: { type: String, required: true }
})

const { collapsed, toggle } = useCollapsibleSection(props.section)
</script>

<style scoped>
.section-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin: -2px 4px -2px -4px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-faint);
  vertical-align: middle;
  cursor: pointer;
}
.section-toggle:hover {
  background: var(--ink-800);
  color: var(--text-primary);
}
@media (max-width: 640px) {
  .section-toggle { position: relative; }
  .section-toggle::after { content: ''; position: absolute; inset: -6px; }
}
@media print {
  .section-toggle { display: none; }
}
</style>

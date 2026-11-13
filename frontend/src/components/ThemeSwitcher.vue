<template>
  <div class="theme-switcher" role="group" aria-label="Thème de l'interface">
    <button
      v-for="option in THEME_OPTIONS"
      :key="option.value"
      type="button"
      class="theme-option"
      :class="{ active: themePreference === option.value }"
      :aria-pressed="themePreference === option.value"
      :title="option.label"
      @click="themePreference = option.value"
    >
      <Icon :name="option.icon" size="sm" />
      <span class="sr-only">{{ option.label }}</span>
    </button>
  </div>
</template>

<script setup>
import Icon from './Icon.vue'
import { themePreference } from '../services/theme'

const THEME_OPTIONS = [
  { value: 'system', icon: 'monitor', label: 'Thème du système' },
  { value: 'light', icon: 'sun', label: 'Thème clair' },
  { value: 'dark', icon: 'moon', label: 'Thème sombre' }
]
</script>

<style scoped>
.theme-switcher {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--sidebar-border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.03);
}
.theme-option {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 28px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--sidebar-text);
  transition: background var(--motion-fast) ease, color var(--motion-fast) ease;
}
.theme-option:hover { color: var(--sidebar-text-active); background: var(--sidebar-hover-bg); }
.theme-option.active { color: var(--sidebar-text-active); background: var(--sidebar-active-bg); }
.theme-option.active .icon { color: var(--sidebar-accent); }
</style>

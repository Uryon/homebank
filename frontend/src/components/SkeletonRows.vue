<template>
  <div class="skeleton-rows" role="status" aria-live="polite">
    <span class="sr-only">{{ label }}</span>
    <div v-for="row in rows" :key="row" class="skeleton-row" aria-hidden="true">
      <span
        v-for="(width, index) in columns"
        :key="index"
        class="skeleton-bar"
        :style="{ flex: `0 1 ${width}`, animationDelay: `${(row * 60) % 600}ms` }"
      />
    </div>
  </div>
</template>

<script setup>
/**
 * Lignes « fantômes » animées affichées pendant le premier chargement d'une
 * liste, à la place d'un message vide trompeur (« aucune transaction »…).
 */
defineProps({
  rows: { type: Number, default: 8 },
  /** Largeur relative de chaque barre (une par colonne simulée). */
  columns: { type: Array, default: () => ['12%', '14%', '22%', '26%', '12%'] },
  label: { type: String, default: 'Chargement…' }
})
</script>

<style scoped>
.skeleton-rows {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--ink-900);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
.skeleton-row {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 17px 20px;
  border-bottom: 1px solid var(--line);
}
.skeleton-row:last-child { border-bottom: 0; }
.skeleton-bar {
  height: 11px;
  border-radius: 6px;
  background: linear-gradient(90deg, var(--ink-800) 0%, var(--ink-700) 50%, var(--ink-800) 100%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.4s ease-in-out infinite;
}
@keyframes skeleton-shimmer {
  from { background-position: 100% 0; }
  to { background-position: -100% 0; }
}
@media (prefers-reduced-motion: reduce) {
  .skeleton-bar { animation: none; }
}
</style>

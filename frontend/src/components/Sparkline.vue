<template>
  <svg v-if="path" class="sparkline" :viewBox="`0 0 ${width} ${height}`" :width="width" :height="height" aria-hidden="true">
    <path :d="path" fill="none" :style="{ stroke: color }" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" />
    <circle :cx="last.x" :cy="last.y" r="2.6" :style="{ fill: color }" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

/**
 * Courbe miniature sans axes (« sparkline ») : montre la forme d'une série
 * (tendance sur 12 mois) à côté d'un chiffre clé. Décorative pour les
 * lecteurs d'écran : la valeur et la variation sont déjà écrites en texte.
 */
const props = defineProps({
  values: { type: Array, default: () => [] },
  // Appliquée en style (pas en attribut SVG) pour accepter aussi var(--...).
  color: { type: String, default: 'currentColor' },
  width: { type: Number, default: 104 },
  height: { type: Number, default: 32 }
})

const points = computed(() => {
  const v = props.values.map(Number).filter(Number.isFinite)
  if (v.length < 2) return []
  const min = Math.min(...v)
  const max = Math.max(...v)
  const span = max - min || 1
  const pad = 4
  return v.map((value, i) => ({
    x: pad + (i / (v.length - 1)) * (props.width - pad * 2),
    y: pad + (1 - (value - min) / span) * (props.height - pad * 2)
  }))
})
const path = computed(() =>
  points.value.length ? points.value.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ') : ''
)
const last = computed(() => points.value[points.value.length - 1] || { x: 0, y: 0 })
</script>

<style scoped>
.sparkline { display: block; overflow: visible; }
</style>

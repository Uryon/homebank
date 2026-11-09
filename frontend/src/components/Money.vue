<template>
  <span class="money"><span class="money-main">{{ main }}</span><span class="money-cents">{{ cents }}</span></span>
</template>

<script setup>
import { computed } from 'vue'

/**
 * Montant en euros avec les centimes (et le symbole) atténués : l'œil lit
 * d'abord l'ordre de grandeur, comme sur les apps bancaires récentes.
 * Même formatage fr-FR que formatAmount (services/format.js).
 */
const props = defineProps({ value: { type: Number, default: 0 } })

const formatter = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' })

const split = computed(() => {
  const parts = formatter.formatToParts(props.value || 0)
  const i = parts.findIndex(p => p.type === 'decimal')
  return {
    main: parts.slice(0, i).map(p => p.value).join(''),
    cents: parts.slice(i).map(p => p.value).join('')
  }
})
const main = computed(() => split.value.main)
const cents = computed(() => split.value.cents)
</script>

<style scoped>
.money-cents {
  opacity: 0.68;
  font-weight: 500;
}
</style>

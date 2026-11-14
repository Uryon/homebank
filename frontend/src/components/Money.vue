<template>
  <span class="money"><span class="money-main">{{ main }}</span><span class="money-cents">{{ cents }}</span></span>
</template>

<script setup>
import { computed } from 'vue'
import { DEFAULT_CURRENCY, currencyFormatter } from '../services/format'

/**
 * Montant avec les centimes (et le symbole) atténués : l'œil lit d'abord
 * l'ordre de grandeur, comme sur les apps bancaires récentes. Même formatage
 * fr-FR que formatAmount (services/format.js), dans la devise du compte.
 */
const props = defineProps({
  value: { type: Number, default: 0 },
  currency: { type: String, default: DEFAULT_CURRENCY }
})

const split = computed(() => {
  const parts = currencyFormatter(props.currency).formatToParts(props.value || 0)
  const i = parts.findIndex(p => p.type === 'decimal')
  // Devise sans centimes (ex : JPY) : pas de séparateur décimal, tout est « principal ».
  if (i < 0) return { main: parts.map(p => p.value).join(''), cents: '' }
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

<template>
  <div
    v-if="visible"
    :id="TOOLTIP_ID"
    ref="tooltipRef"
    role="tooltip"
    class="overflow-tooltip"
    :class="[`placement-${position.placement}`, { measured }]"
    :style="{ top: `${position.top}px`, left: `${position.left}px` }"
    @pointerenter="cancelHide"
    @pointerleave="scheduleHide"
  >{{ text }}</div>
</template>

<script setup>
/**
 * Infobulle globale pour les textes tronqués par « … » (un seul exemplaire,
 * monté dans App.vue). Conforme au critère WCAG 2.1 1.4.13 « Contenu au
 * survol ou au focus » :
 * - déclenchée au survol de la souris ET au focus clavier ;
 * - survolable : on peut déplacer la souris dessus sans qu'elle disparaisse ;
 * - refermable avec Échap sans déplacer le pointeur ni le focus ;
 * - persistante tant que le survol / le focus dure.
 * Reliée à l'élément par role="tooltip" + aria-describedby.
 * La logique (détection, texte, position) vit dans services/overflowTooltip.js.
 */
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { computeTooltipPosition, findOverflowTarget, tooltipTextFor } from '../services/overflowTooltip'

const TOOLTIP_ID = 'overflow-tooltip'
const SHOW_DELAY_MS = 350
const HIDE_DELAY_MS = 120

const visible = ref(false)
const measured = ref(false)
const text = ref('')
const position = reactive({ top: 0, left: 0, placement: 'bottom' })
const tooltipRef = ref(null)

let anchor = null
let showTimer = null
let hideTimer = null

const getStyle = (el) => getComputedStyle(el)

function clearTimers() {
  clearTimeout(showTimer)
  clearTimeout(hideTimer)
  showTimer = null
  hideTimer = null
}

function cancelHide() {
  clearTimeout(hideTimer)
}

function scheduleHide() {
  clearTimeout(showTimer)
  showTimer = null
  clearTimeout(hideTimer)
  hideTimer = setTimeout(hide, HIDE_DELAY_MS)
}

function linkAnchor(el) {
  const ids = (el.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean)
  if (!ids.includes(TOOLTIP_ID)) el.setAttribute('aria-describedby', [...ids, TOOLTIP_ID].join(' '))
}

function unlinkAnchor(el) {
  const ids = (el.getAttribute('aria-describedby') || '').split(/\s+/).filter(id => id && id !== TOOLTIP_ID)
  if (ids.length) el.setAttribute('aria-describedby', ids.join(' '))
  else el.removeAttribute('aria-describedby')
}

function hide() {
  clearTimers()
  if (anchor) unlinkAnchor(anchor)
  anchor = null
  visible.value = false
  measured.value = false
}

async function show(el) {
  const content = tooltipTextFor(el)
  if (!content) return
  if (anchor && anchor !== el) unlinkAnchor(anchor)
  anchor = el
  text.value = content
  measured.value = false
  visible.value = true
  linkAnchor(el)
  // Rendu invisible d'abord pour mesurer l'infobulle, puis positionnement.
  await nextTick()
  if (!tooltipRef.value || anchor !== el) return
  const tipRect = tooltipRef.value.getBoundingClientRect()
  Object.assign(position, computeTooltipPosition(
    el.getBoundingClientRect(),
    { width: tipRect.width, height: tipRect.height },
    { width: window.innerWidth, height: window.innerHeight }
  ))
  measured.value = true
}

function scheduleShow(el, delay) {
  cancelHide()
  if (el === anchor && visible.value) return
  clearTimeout(showTimer)
  showTimer = setTimeout(() => {
    showTimer = null
    show(el)
  }, delay)
}

function isInsideTooltip(node) {
  return tooltipRef.value && node instanceof Node && tooltipRef.value.contains(node)
}

function onPointerOver(event) {
  if (event.pointerType === 'touch' || isInsideTooltip(event.target)) return
  const target = findOverflowTarget(event.target, getStyle, document.body)
  if (target) scheduleShow(target, SHOW_DELAY_MS)
  else if (visible.value || showTimer) scheduleHide()
}

function onPointerOut(event) {
  // Sortie vers un élément qui n'est ni l'ancre ni l'infobulle : on masque.
  const to = event.relatedTarget
  if (anchor && to instanceof Node && (anchor.contains(to) || isInsideTooltip(to))) return
  if (anchor || showTimer) scheduleHide()
}

function onFocusIn(event) {
  const target = findOverflowTarget(event.target, getStyle, document.body)
  if (target) scheduleShow(target, 0)
}

function onKeydown(event) {
  if (event.key === 'Escape' && visible.value) hide()
}

onMounted(() => {
  document.addEventListener('pointerover', onPointerOver)
  document.addEventListener('pointerout', onPointerOut)
  document.addEventListener('focusin', onFocusIn)
  document.addEventListener('focusout', scheduleHide)
  document.addEventListener('keydown', onKeydown)
  // L'ancre bouge avec la page : on masque plutôt que de laisser l'infobulle flotter ailleurs.
  window.addEventListener('scroll', hide, true)
  window.addEventListener('resize', hide)
})

onBeforeUnmount(() => {
  clearTimers()
  document.removeEventListener('pointerover', onPointerOver)
  document.removeEventListener('pointerout', onPointerOut)
  document.removeEventListener('focusin', onFocusIn)
  document.removeEventListener('focusout', scheduleHide)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('scroll', hide, true)
  window.removeEventListener('resize', hide)
})
</script>

<style scoped>
.overflow-tooltip {
  position: fixed;
  z-index: 3100;
  max-width: min(420px, calc(100vw - 16px));
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--text-primary);
  color: var(--ink-900);
  box-shadow: var(--shadow-md);
  font-size: 12.5px;
  font-weight: 500;
  line-height: 1.45;
  white-space: normal;
  overflow-wrap: anywhere;
  visibility: hidden;
}
.overflow-tooltip.measured {
  visibility: visible;
  animation: overflow-tooltip-in 0.12s ease-out;
}
@keyframes overflow-tooltip-in {
  from { opacity: 0; transform: translateY(-2px); }
  to { opacity: 1; transform: none; }
}
.overflow-tooltip.placement-top.measured { animation-name: overflow-tooltip-in-top; }
@keyframes overflow-tooltip-in-top {
  from { opacity: 0; transform: translateY(2px); }
  to { opacity: 1; transform: none; }
}
@media (prefers-reduced-motion: reduce) {
  .overflow-tooltip.measured { animation: none; }
}
</style>

<template>
  <svg viewBox="0 0 32 32" class="app-logo" aria-hidden="true">
    <circle cx="20" cy="12.5" r="6.3" fill="url(#appLogoGradient)" stroke="var(--sidebar-bg)" stroke-width="0.6" />
    <circle cx="16" cy="17" r="6.8" fill="url(#appLogoGradient)" stroke="var(--sidebar-bg)" stroke-width="0.6" />
    <circle cx="12" cy="21.5" r="7.3" fill="url(#appLogoGradient)" stroke="var(--sidebar-bg)" stroke-width="0.6" />
    <circle cx="12" cy="21.5" r="4.4" fill="none" stroke="var(--sidebar-bg)" stroke-width="0.9" opacity="0.55" />
    <defs>
      <linearGradient id="appLogoGradient" x1="5" y1="6" x2="27" y2="29" gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="#FFE38A" />
        <stop offset="1" stop-color="#C8921A" />
      </linearGradient>
    </defs>
  </svg>
</template>

<script setup>
/**
 * Logomark de l'application : une pile de trois pieces de monnaie en
 * diagonale (degrade or), la plus proche avec sa tranche marquee
 * (double cercle) — evoque l'epargne qui s'accumule plutot qu'une piece ou
 * une maison isolee (essais precedents). Pas de badge carre autour : la
 * pile flotte directement sur le fond de la sidebar. Couleurs en dur (pas de
 * var()) : les stops d'un degrade SVG ne lisent pas les variables CSS de
 * maniere fiable ; l'or ressort sur le bleu de la sidebar (theme Abysse).
 */
</script>

<style scoped>
.app-logo {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  display: block;
}
</style>

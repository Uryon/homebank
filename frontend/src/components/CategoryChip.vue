<template>
  <span v-if="info" class="cat-chip" :style="{ '--h': info.hue }" :data-tooltip="info.path">
    <span class="cat-mark" aria-hidden="true">{{ info.icon }}</span>
    <span v-if="info.parent" class="cat-parent">{{ info.parent }} ›</span>
    <span class="cat-name">{{ info.name }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { categoryChipInfo } from '../services/categories'

/** Pastille colorée d'une catégorie, partagée par Transactions et Tiers. Rien n'est rendu si la catégorie est inconnue. */
const props = defineProps({
  categoryId: { type: Number, default: null },
  categoriesById: { type: Map, required: true }
})

const info = computed(() => categoryChipInfo(props.categoryId, props.categoriesById))
</script>

<style scoped>
/* Teinte dérivée de la catégorie racine (--h), saturation/luminosité par thème (style.css) */
.cat-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 2px 10px 2px 6px;
  border-radius: 999px;
  background: hsl(var(--h) var(--chip-bg-saturation) var(--chip-bg-lightness));
  color: hsl(var(--h) var(--chip-text-saturation) var(--chip-text-lightness));
  font-size: 13px;
  font-weight: 500;
  vertical-align: middle;
}
.cat-mark { font-size: 13px; line-height: 1; flex-shrink: 0; }
.cat-mark:empty { display: none; }
.cat-chip:has(.cat-mark:empty) { padding-left: 10px; }
/* Faute de place, c'est le parent qui se tronque en premier (forte valeur de
   flex-shrink) : la sous-catégorie, plus précise, reste lisible. Le chemin
   complet est dans l'infobulle. */
/* Hiérarchie parent / sous-catégorie par la graisse, pas par l'opacité :
   une opacité réduite faisait tomber le contraste du parent sous 3:1. */
.cat-parent {
  font-weight: 400;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 2.5em;
  flex: 0 1000 auto;
}
.cat-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 0 1 auto; }
</style>

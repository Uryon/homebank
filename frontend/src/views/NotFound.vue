<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Page introuvable</h1>
        <div class="subtitle">L'adresse <code>{{ route.fullPath }}</code> ne correspond à aucune page de HomeBank.</div>
      </div>
    </div>

    <div class="empty-state">
      <p>Le lien est peut-être ancien ou mal recopié. Tes données ne sont pas concernées.</p>
      <RouterLink to="/" class="btn">Revenir au tableau de bord</RouterLink>
    </div>
  </div>
</template>

<script setup>
/**
 * Page affichée pour une adresse inconnue (lien ancien, faute de frappe) :
 * sans elle, le routeur n'affiche rien et la zone principale reste vide.
 */
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<style scoped>
code {
  font-family: var(--font-mono);
  font-size: 0.95em;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
</style>

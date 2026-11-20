<template>
  <main class="single-window" aria-labelledby="single-window-title">
    <section class="single-window-card">
      <AppLogo class="single-window-logo" />
      <h1 id="single-window-title">HomeBank est déjà ouvert dans une autre fenêtre</h1>
      <p>
        Pour que deux fenêtres ne modifient pas vos données en même temps, HomeBank
        ne s'utilise que dans une seule. Cette fenêtre prendra le relais d'elle-même
        dès que l'autre sera fermée.
      </p>
      <button ref="takeOverButton" type="button" class="btn" @click="emit('take-over')">
        Utiliser cette fenêtre
      </button>
      <p class="single-window-hint">L'autre fenêtre passera alors en attente.</p>
    </section>
  </main>
</template>

<script setup>
/**
 * Écran d'une fenêtre HomeBank inactive (voir services/singleWindow.js) :
 * l'appli n'y est pas montée tant qu'une autre fenêtre est active.
 */
import { onMounted, ref } from 'vue'
import AppLogo from './AppLogo.vue'

const emit = defineEmits({
  'take-over': () => true
})

const takeOverButton = ref(null)

// Entrée suffit pour reprendre la main.
onMounted(() => takeOverButton.value?.focus())
</script>

<style scoped>
.single-window {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 16px;
  box-sizing: border-box;
  background: var(--ink-950);
}

.single-window-card {
  width: min(460px, 100%);
  box-sizing: border-box;
  padding: 32px 28px;
  border-radius: var(--radius-lg);
  background: var(--ink-900);
  box-shadow: var(--shadow-md);
  text-align: center;
  color: var(--text-primary);
}

.single-window-logo {
  display: block;
  width: 44px;
  height: 44px;
  margin: 0 auto;
  --sidebar-bg: var(--ink-900);
}

h1 {
  margin: 12px 0 8px;
  font-size: 19px;
  font-weight: 650;
  line-height: 1.35;
}

p {
  margin: 0 0 20px;
  color: var(--text-muted);
  line-height: 1.5;
}

.single-window-hint {
  margin: 12px 0 0;
  font-size: 13px;
}
</style>

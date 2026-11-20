<template>
  <p v-if="message" :id="id" class="field-error" role="alert">{{ message }}</p>
</template>

<script setup>
/** Message d'erreur sous un champ de formulaire (voir services/fieldErrors.js). Rien n'est rendu sans message. */
defineProps({
  id: { type: String, required: true },
  message: { type: String, default: '' }
})
</script>

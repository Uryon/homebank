import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { apiProxy, previewServer } from './vite.preview.config.mjs'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    // Modules de l'appli et du tableau de bord (page d'accueil) transformés
    // dès le démarrage de Vite, pendant que le serveur Spring démarre (voir
    // HomeBank.vbs) : premier affichage 709 -> 456 ms mesurés, au lieu de
    // les transformer à la première demande.
    warmup: {
      clientFiles: ['./src/main.js', './src/views/Dashboard.vue']
    },
    proxy: apiProxy
  },
  // `npm run preview` ; le raccourci HomeBank.vbs utilise directement
  // vite.preview.config.mjs, plus rapide à charger.
  preview: previewServer
})

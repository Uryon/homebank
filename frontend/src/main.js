import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'
import { vDialog } from './directives/dialog'
import { vGridNav } from './directives/gridNav'
import { vRowNav } from './directives/rowNav'
import SingleWindowNotice from './components/SingleWindowNotice.vue'
import { startSingleWindow } from './services/singleWindow'

// Une seule fenêtre active (voir services/singleWindow.js) : l'appli n'est
// montée que dans celle-ci ; une autre fenêtre affiche un écran d'attente.
let mounted = null
let appWasMounted = false

function mountApp() {
  mounted = createApp(App)
    .use(router)
    .directive('dialog', vDialog)
    .directive('grid-nav', vGridNav)
    .directive('row-nav', vRowNav)
  mounted.mount('#app')
  appWasMounted = true
}

function unmountCurrent() {
  mounted?.unmount()
  mounted = null
}

const singleWindow = startSingleWindow({
  onActive() {
    // Main reprise après l'avoir perdue : rechargement, pour repartir de
    // données à jour (l'autre fenêtre a pu les modifier entre-temps).
    if (appWasMounted) {
      location.reload()
      return
    }
    unmountCurrent()
    mountApp()
  },
  onInactive() {
    unmountCurrent()
    mounted = createApp(SingleWindowNotice, { onTakeOver: () => singleWindow.takeOver() })
    mounted.mount('#app')
  }
})

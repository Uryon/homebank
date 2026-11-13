import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'
import { vDialog } from './directives/dialog'
import { vGridNav } from './directives/gridNav'

createApp(App)
  .use(router)
  .directive('dialog', vDialog)
  .directive('grid-nav', vGridNav)
  .mount('#app')

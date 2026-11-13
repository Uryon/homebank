<template>
  <div v-if="open" class="modal-overlay" @click.self="emit('close')">
    <div v-dialog="() => emit('close')" class="modal shortcuts-modal" role="dialog" aria-modal="true">
      <div class="shortcuts-header">
        <h2>Raccourcis clavier</h2>
        <button type="button" class="icon-btn" aria-label="Fermer" title="Fermer (Échap)" @click="emit('close')">
          <Icon name="x" />
        </button>
      </div>
      <p class="shortcuts-intro">
        Les raccourcis à une touche (<kbd>/</kbd>, <kbd>N</kbd>, <kbd>?</kbd>) sont ignorés pendant une saisie
        ou quand une fenêtre est ouverte.
      </p>

      <div class="shortcuts-groups">
        <section v-for="group in SHORTCUT_GROUPS" :key="group.title" class="shortcuts-group">
          <h3>{{ group.title }}</h3>
          <dl>
            <div v-for="item in group.items" :key="group.title + item.label" class="shortcut-row">
              <dt>
                <template v-for="(combo, comboIndex) in item.keys" :key="comboIndex">
                  <span v-if="comboIndex > 0" class="shortcut-or">ou</span>
                  <span class="shortcut-combo">
                    <template v-for="(key, keyIndex) in combo" :key="key">
                      <span v-if="keyIndex > 0" class="shortcut-plus" aria-hidden="true">+</span>
                      <kbd>{{ displayKey(key, apple) }}</kbd>
                    </template>
                  </span>
                </template>
              </dt>
              <dd>{{ item.label }}</dd>
            </div>
          </dl>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * Aide des raccourcis clavier (touche « ? », bouton de la barre latérale ou
 * commande de la palette). Liste et libellés dans services/shortcuts.js.
 */
import Icon from './Icon.vue'
import { SHORTCUT_GROUPS, displayKey, isApplePlatform } from '../services/shortcuts'

defineProps({
  open: { type: Boolean, required: true }
})
const emit = defineEmits(['close'])

// Plateforme fixe pendant la session : calculée une seule fois.
const apple = isApplePlatform()
</script>

<style scoped>
.shortcuts-modal {
  width: min(900px, 100%);
}
.shortcuts-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.shortcuts-header h2 { margin-bottom: 0; }
.shortcuts-intro {
  margin: 8px 0 18px;
  color: var(--text-muted);
  font-size: 13px;
}
.shortcuts-groups {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 28px;
}
.shortcuts-group h3 {
  margin: 0 0 8px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 650;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.shortcuts-group dl { margin: 0; }
.shortcut-row {
  display: grid;
  grid-template-columns: 118px 1fr;
  gap: 12px;
  align-items: baseline;
  padding: 6px 0;
  border-bottom: 1px solid var(--line);
}
.shortcut-row:last-child { border-bottom: 0; }
.shortcut-row dt {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}
.shortcut-row dd {
  margin: 0;
  color: var(--text-primary);
  font-size: 13px;
  line-height: 1.4;
}
.shortcut-combo { display: inline-flex; align-items: center; gap: 2px; }
.shortcut-plus, .shortcut-or { color: var(--text-faint); font-size: 11px; }
kbd {
  display: inline-block;
  min-width: 22px;
  padding: 1px 6px;
  border: 1px solid var(--line-strong);
  border-bottom-width: 2px;
  border-radius: 5px;
  background: var(--surface-sunken);
  color: var(--text-primary);
  font-family: var(--font-ui);
  font-size: 11.5px;
  font-weight: 600;
  text-align: center;
}
@media (max-width: 680px) {
  .shortcuts-groups { grid-template-columns: 1fr; }
}
</style>

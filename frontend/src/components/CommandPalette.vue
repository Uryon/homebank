<template>
  <Transition name="palette">
    <div v-if="open" class="palette-overlay" @mousedown.self="emit('close')">
      <div class="palette" role="dialog" aria-modal="true" aria-label="Palette de commandes">
        <div class="palette-search">
          <Icon name="search" />
          <input
            ref="inputRef"
            v-model="query"
            type="text"
            role="combobox"
            aria-expanded="true"
            aria-controls="palette-results"
            :aria-activedescendant="activeOptionId"
            aria-autocomplete="list"
            aria-label="Aller à une page, un tiers, ou rechercher une transaction"
            placeholder="Aller à une page, un tiers, rechercher une transaction…"
            autocomplete="off"
            spellcheck="false"
            @keydown="onKeydown"
          />
          <kbd>Échap</kbd>
        </div>

        <ul id="palette-results" class="palette-results" role="listbox" aria-label="Résultats">
          <template v-for="(item, index) in results" :key="item.id">
            <li v-if="index === 0 || results[index - 1].group !== item.group" class="palette-group" role="presentation">
              {{ item.group }}
            </li>
            <li
              :id="`palette-option-${index}`"
              class="palette-option"
              :class="{ active: index === activeIndex }"
              role="option"
              :aria-selected="index === activeIndex"
              @mousemove="activeIndex = index"
              @click="run(item)"
            >
              <Icon :name="item.icon" size="sm" />
              <span class="palette-label">{{ item.label }}</span>
              <kbd v-if="item.hint">{{ item.hint }}</kbd>
              <Icon v-if="index === activeIndex" name="chevronRight" size="sm" />
            </li>
          </template>
          <li v-if="!results.length" class="palette-empty" role="presentation">Aucun résultat</li>
        </ul>

        <div class="palette-footer" aria-hidden="true">
          <span><kbd>↑</kbd><kbd>↓</kbd> naviguer</span>
          <span><kbd>Entrée</kbd> ouvrir</span>
          <span><kbd>Ctrl</kbd><kbd>K</kbd> ouvrir / fermer</span>
          <span><kbd>/</kbd> rechercher dans la page</span>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Icon from './Icon.vue'
import { dataStore } from '../services/dataStore'
import { themePreference } from '../services/theme'
import { ACTION_COMMANDS, PAGE_COMMANDS, buildResults, indexCommands, indexPayees } from '../services/commandPalette'

const props = defineProps({
  open: { type: Boolean, required: true }
})
const emit = defineEmits(['close', 'show-shortcuts'])

const router = useRouter()
const query = ref('')
const activeIndex = ref(0)
const inputRef = ref(null)
let focusBeforeOpen = null

// Index normalisés une seule fois (et non à chaque frappe), voir CLAUDE.md.
const staticIndex = indexCommands([...PAGE_COMMANDS, ...ACTION_COMMANDS])
const payeeIndex = computed(() => indexPayees(dataStore.state.payees))

const results = computed(() => buildResults({ staticIndex, payeeIndex: payeeIndex.value, query: query.value }))
const activeOptionId = computed(() => (results.value.length ? `palette-option-${activeIndex.value}` : undefined))

watch(query, () => { activeIndex.value = 0 })

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    focusBeforeOpen = document.activeElement
    query.value = ''
    activeIndex.value = 0
    await nextTick()
    inputRef.value?.focus()
  } else if (focusBeforeOpen instanceof HTMLElement) {
    focusBeforeOpen.focus()
  }
})

function moveActive(delta) {
  const count = results.value.length
  if (!count) return
  activeIndex.value = (activeIndex.value + delta + count) % count
  nextTick(() => document.getElementById(activeOptionId.value)?.scrollIntoView({ block: 'nearest' }))
}

function onKeydown(event) {
  if (event.key === 'ArrowDown') { event.preventDefault(); moveActive(1) }
  else if (event.key === 'ArrowUp') { event.preventDefault(); moveActive(-1) }
  else if (event.key === 'Enter') {
    event.preventDefault()
    const item = results.value[activeIndex.value]
    if (item) run(item)
  } else if (event.key === 'Escape') { event.preventDefault(); emit('close') }
  else if (event.key === 'Tab') event.preventDefault() // le focus reste dans la palette
}

function run(item) {
  emit('close')
  if (item.action === 'shortcuts') {
    emit('show-shortcuts')
  } else if (item.action?.startsWith('theme:')) {
    themePreference.value = item.action.slice('theme:'.length)
  } else if (item.to) {
    router.push(item.to)
  }
}
</script>

<style scoped>
.palette-overlay {
  position: fixed;
  inset: 0;
  z-index: 2995;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 12vh 16px 16px;
  background: rgba(12, 20, 34, 0.42);
  backdrop-filter: blur(3px);
}
.palette {
  display: flex;
  flex-direction: column;
  width: min(620px, 100%);
  max-height: min(520px, 76vh);
  overflow: hidden;
  background: var(--ink-900);
  border: 1px solid var(--line-strong);
  border-radius: 16px;
  box-shadow: var(--shadow-lg);
}
.palette-search {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  color: var(--text-faint);
}
.palette-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  font-size: 15px;
  outline: none;
}
.palette-search input::placeholder { color: var(--placeholder); }
.palette-results {
  flex: 1;
  margin: 0;
  padding: 6px;
  overflow-y: auto;
  list-style: none;
  overscroll-behavior: contain;
}
.palette-group {
  padding: 10px 10px 4px;
  color: var(--text-faint);
  font-size: 11.5px;
  font-weight: 600;
}
.palette-option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-primary);
  font-size: 14px;
  cursor: pointer;
}
.palette-option .icon { color: var(--text-muted); }
.palette-option.active { background: var(--azure-dim); }
.palette-option.active .icon { color: var(--azure); }
.palette-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.palette-empty { padding: 22px; text-align: center; color: var(--text-muted); font-size: 13.5px; }
.palette-footer {
  display: flex;
  gap: 16px;
  padding: 9px 16px;
  border-top: 1px solid var(--line);
  background: var(--surface-sunken);
  color: var(--text-faint);
  font-size: 12px;
}
kbd {
  display: inline-block;
  min-width: 20px;
  margin-right: 3px;
  padding: 1px 5px;
  border: 1px solid var(--line-strong);
  border-bottom-width: 2px;
  border-radius: 5px;
  background: var(--ink-900);
  color: var(--text-muted);
  font-family: var(--font-ui);
  font-size: 11px;
  text-align: center;
}
.palette-enter-active, .palette-leave-active { transition: opacity 0.12s ease; }
.palette-enter-active .palette, .palette-leave-active .palette { transition: transform 0.12s ease; }
.palette-enter-from, .palette-leave-to { opacity: 0; }
.palette-enter-from .palette, .palette-leave-to .palette { transform: translateY(-6px) scale(0.985); }
@media (max-width: 640px) {
  .palette-footer { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .palette-enter-active, .palette-leave-active,
  .palette-enter-active .palette, .palette-leave-active .palette { transition: none; }
}
</style>

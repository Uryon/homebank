<template>
  <div class="toast-region" aria-live="polite" aria-relevant="additions">
    <TransitionGroup name="toast" tag="div" class="toast-stack">
      <div v-for="toast in feedbackState.toasts" :key="toast.id" class="app-toast" :class="`is-${toast.type}`" role="status">
        <span class="toast-mark" aria-hidden="true">{{ iconFor(toast.type) }}</span>
        <span class="toast-message">{{ toast.message }}</span>
        <button class="toast-close" type="button" aria-label="Fermer la notification" @click="dismissToast(toast.id)">×</button>
      </div>
    </TransitionGroup>
  </div>

  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="feedbackState.confirmation" class="feedback-overlay" @click.self="resolveConfirm(false)" @keydown.esc="resolveConfirm(false)">
        <section ref="dialogElement" class="feedback-dialog" role="alertdialog" aria-modal="true" aria-labelledby="feedback-dialog-title" aria-describedby="feedback-dialog-message" @keydown="trapFocus">
          <div class="feedback-dialog-icon" :class="{ destructive: feedbackState.confirmation.destructive }" aria-hidden="true">{{ feedbackState.confirmation.destructive ? '!' : '?' }}</div>
          <h2 id="feedback-dialog-title">{{ feedbackState.confirmation.title }}</h2>
          <p id="feedback-dialog-message">{{ feedbackState.confirmation.message }}</p>
          <div class="feedback-dialog-actions">
            <button class="btn secondary" type="button" @click="resolveConfirm(false)">{{ feedbackState.confirmation.cancelLabel }}</button>
            <button class="btn" :class="feedbackState.confirmation.destructive ? 'danger' : ''" type="button" @click="resolveConfirm(true)">{{ feedbackState.confirmation.confirmLabel }}</button>
          </div>
        </section>
      </div>
    </Transition>

    <Transition name="dialog-fade">
      <div v-if="feedbackState.textPrompt" class="feedback-overlay" @click.self="resolveTextPrompt(null)" @keydown.esc="resolveTextPrompt(null)">
        <form ref="textDialogElement" class="feedback-dialog" role="dialog" aria-modal="true" aria-labelledby="text-dialog-title" aria-describedby="text-dialog-message" @submit.prevent="resolveTextPrompt(textValue)" @keydown="trapFocus">
          <h2 id="text-dialog-title">{{ feedbackState.textPrompt.title }}</h2>
          <p id="text-dialog-message">{{ feedbackState.textPrompt.message }}</p>
          <label class="feedback-input-label" for="feedback-text-value">Motif</label>
          <input id="feedback-text-value" v-model="textValue" class="feedback-text-input" type="text" :placeholder="feedbackState.textPrompt.placeholder" />
          <div class="feedback-dialog-actions">
            <button class="btn secondary" type="button" @click="resolveTextPrompt(null)">{{ feedbackState.textPrompt.cancelLabel }}</button>
            <button class="btn" type="submit">{{ feedbackState.textPrompt.confirmLabel }}</button>
          </div>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import { feedbackState, dismissToast, resolveConfirm, resolveTextPrompt } from '../services/feedback'
import { trapTabKey } from '../services/focusTrap'

const dialogElement = ref(null)
const textDialogElement = ref(null)
const textValue = ref('')
let previouslyFocused = null

watch(() => feedbackState.confirmation, async value => {
  if (value) {
    previouslyFocused = document.activeElement
    await nextTick()
    dialogElement.value?.querySelector('button')?.focus()
  } else {
    restoreFocus()
  }
})
watch(() => feedbackState.textPrompt, async value => {
  if (value) {
    previouslyFocused = document.activeElement
    textValue.value = value.value
    await nextTick()
    textDialogElement.value?.querySelector('input')?.focus()
  } else {
    restoreFocus()
  }
})

function restoreFocus() {
  if (previouslyFocused?.isConnected) previouslyFocused.focus()
  previouslyFocused = null
}
function trapFocus(event) {
  trapTabKey(event, event.currentTarget)
}
function iconFor(type) {
  return type === 'success' ? '✓' : type === 'error' ? '!' : type === 'warning' ? '!' : 'i'
}
</script>

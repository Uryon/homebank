<template>
  <MergeModal
    :title="`Fusionner ${payees.length} tiers`"
    description="Choisissez le tiers à conserver. Toutes les transactions des autres tiers sélectionnés lui seront réaffectées (elles porteront désormais son nom), puis les tiers fusionnés seront supprimés. Vous pourrez l'annuler juste après avec le bouton « Annuler » (ou Ctrl+Z)."
    target-label="Tiers à conserver"
    :items="payees"
    :option-label="payeeOptionLabel"
    v-model="targetId"
    :confirming="confirming"
    @confirm="submit"
    @cancel="emit('cancel')"
  >
    <div class="form-field">
      <span class="form-field-label">Motifs actuels des tiers sélectionnés</span>
      <ul class="merge-patterns-list">
        <li v-for="p in payees" :key="p.id">
          <strong>{{ p.name }}</strong> : <code>{{ p.pattern }}</code>
        </li>
      </ul>
    </div>

    <div class="form-field">
      <label for="payee-merge-pattern">Motif de reconnaissance final</label>
      <input id="payee-merge-pattern" v-model="pattern" placeholder="Ex : Carrefour" v-bind="invalidAttrs('mergePattern')" @input="clear('mergePattern')" />
      <FieldError id="payee-merge-pattern-error" :message="errors.mergePattern" />
      <div class="text-hint">
        Pré-rempli avec le motif du tiers conservé, ci-dessus pour référence.
        Modifiez-le comme vous le souhaitez : les motifs des tiers fusionnés ne
        sont plus ajoutés automatiquement.
      </div>
    </div>
  </MergeModal>
</template>

<script setup>
/**
 * Fenêtre de fusion de la page Tiers : tiers conservé et motif de
 * reconnaissance final ; la fusion est faite par la page (événement confirm).
 */
import { ref, watch } from 'vue'
import MergeModal from './MergeModal.vue'
import FieldError from './FieldError.vue'
import { useFieldErrors } from '../services/fieldErrors'
import { formatAmount } from '../services/format'

const props = defineProps({
  /** Tiers sélectionnés (au moins deux), dans l'ordre de la sélection. */
  payees: { type: Array, required: true },
  confirming: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'cancel'])

const targetId = ref(props.payees[0]?.id ?? null)
const pattern = ref(patternOf(targetId.value))
const { errors, check, clear, invalidAttrs } = useFieldErrors({ mergePattern: 'payee-merge-pattern' })

function patternOf(id) {
  return props.payees.find(p => p.id === id)?.pattern || ''
}

// Motif pré-rempli avec celui du tiers conservé à chaque changement de
// cible, pour partir d'une base cohérente, librement modifiable ensuite.
watch(targetId, (id) => {
  pattern.value = patternOf(id)
})

function payeeOptionLabel(p) {
  return p.name + (p.amount != null ? ' — ' + formatAmount(p.amount) : '')
}

function submit() {
  if (!targetId.value) return
  if (!check({ mergePattern: pattern.value.trim() ? '' : 'Saisissez le motif de reconnaissance final.' })) return
  emit('confirm', { targetId: targetId.value, pattern: pattern.value.trim() })
}
</script>

<style scoped>
.text-hint {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 4px;
  line-height: 1.4;
}
.merge-patterns-list {
  margin: 0;
  padding-left: 18px;
  max-height: 160px;
  overflow-y: auto;
  font-size: 13px;
}
.merge-patterns-list li {
  margin: 2px 0;
}
.merge-patterns-list code {
  background: var(--ink-800);
  padding: 3px 6px;
  border-radius: 5px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
</style>

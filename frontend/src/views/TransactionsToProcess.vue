<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">À traiter : {{ toProcess.length }} opération{{ toProcess.length > 1 ? 's' : '' }}</div>
      </div>
      <button
        class="btn"
        :class="{ secondary: toProcess.length === 0 }"
        :disabled="toProcess.length === 0 || validatingAll"
        @click="validateAllVisible"
      >
        {{ validatingAll ? 'Validation…' : `Tout valider (${toProcess.length})` }}
      </button>
    </div>

    <TransactionsSubNav />

    <div v-if="toProcess.length" class="process-list">
      <div v-for="t in toProcess" :key="t.id" class="process-row" :class="{ 'row-editing-inline': editingField.id === t.id }">
        <div class="process-main">
          <div class="process-date">{{ formatDate(t.date) }}</div>

          <!-- Tiers : cliquable pour éditer en ligne -->
          <div class="process-payee">
            <template v-if="editingField.id === t.id && editingField.field === 'payee'">
              <input
                v-model="editingField.value"
                list="atraiter-payee-options"
                autocomplete="off"
                ref="inlineInput"
                @keydown.enter="confirmInlineEdit(t)"
                @keydown.esc="cancelInlineEdit"
                @blur="confirmInlineEdit(t)"
              />
            </template>
            <template v-else>
              <button class="process-link" @click="startInlineEdit(t, 'payee')" title="Modifier le tiers">
                {{ t.payee || 'Sans tiers' }}
              </button>
            </template>
          </div>

          <div class="process-amount" :class="t.amount >= 0 ? 'amount positive' : 'amount negative'">
            {{ formatAmount(t.amount) }}
          </div>

          <!-- Catégorie : cliquable pour éditer en ligne, "?" si absente -->
          <div class="process-category">
            <template v-if="editingField.id === t.id && editingField.field === 'category'">
              <input
                v-model="editingField.value"
                list="atraiter-category-options"
                autocomplete="off"
                ref="inlineInput"
                @keydown.enter="confirmInlineEdit(t)"
                @keydown.esc="cancelInlineEdit"
                @blur="confirmInlineEdit(t)"
              />
            </template>
            <template v-else>
              <button
                class="process-link category-link"
                :class="{ unknown: !t.categoryId }"
                @click="startInlineEdit(t, 'category')"
                title="Modifier la catégorie"
              >
                {{ t.categoryId ? categoryLabel(t.categoryId) : '?' }}
              </button>
            </template>
          </div>
        </div>

        <div class="process-actions">
          <button class="btn secondary" title="Valider (Entrée)" @click="validateOne(t)">✓ Valider</button>
          <button class="btn secondary" title="Modifier la transaction en entier" @click="openFullEdit(t)">Modifier</button>
          <button class="btn secondary" title="Créer une règle de reconnaissance pour ce tiers" @click="createRule(t)">Créer une règle</button>
          <button class="btn secondary" title="Ignorer pour cette session" @click="ignoreOne(t)">Ignorer</button>
        </div>
      </div>
    </div>

    <div v-else class="empty-state">
      🎉 Rien à traiter pour l'instant — toutes les transactions sont validées.
    </div>

    <datalist id="atraiter-payee-options">
      <option v-for="p in payees" :key="p.id" :value="p.name" />
    </datalist>
    <datalist id="atraiter-category-options">
      <option v-for="c in flatCategories" :key="c.id" :value="c.label" />
    </datalist>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { TransactionsAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import TransactionsSubNav from './TransactionsSubNav.vue'

const router = useRouter()

const categories = computed(() => dataStore.state.categories)
const payees = computed(() => dataStore.state.payees)

// Transactions ignorees pour cette session de traitement uniquement (non
// persiste en base : elles reapparaitront a la prochaine visite tant
// qu'elles ne sont pas reellement validees ou traitees).
const ignoredIds = ref(new Set())

const toProcess = computed(() =>
  dataStore.state.transactions
    .filter(t => !t.validated && !ignoredIds.value.has(t.id))
    .slice()
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))
)

const flatCategories = computed(() => {
  const roots = categories.value.filter(c => !c.parentId)
  const result = []
  for (const root of roots) {
    result.push({ id: root.id, label: root.name })
    const children = categories.value.filter(c => c.parentId === root.id)
    for (const child of children) {
      result.push({ id: child.id, label: `${root.name} : ${child.name}` })
    }
  }
  return result
})

function categoryLabel(categoryId) {
  const category = categories.value.find(c => c.id === categoryId)
  if (!category) return '?'
  if (category.parentId) {
    const parent = categories.value.find(c => c.id === category.parentId)
    if (parent) return `${parent.name} : ${category.name}`
  }
  return category.name
}

function resolveCategoryId(typedLabel) {
  if (!typedLabel || !typedLabel.trim()) return null
  const trimmed = typedLabel.trim().toLowerCase()
  const match = flatCategories.value.find(c => c.label.toLowerCase() === trimmed)
  return match ? match.id : null
}

function formatAmount(value) {
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR' }).format(value || 0)
}

function formatDate(iso) {
  if (!iso) return ''
  const [y, m, d] = iso.split('-')
  return `${d}/${m}/${y}`
}

/* --- Édition rapide en ligne (tiers ou catégorie uniquement) --- */

const editingField = ref({ id: null, field: null, value: '' })
const inlineInput = ref(null)

function startInlineEdit(t, field) {
  editingField.value = {
    id: t.id,
    field,
    value: field === 'payee' ? (t.payee || '') : (t.categoryId ? categoryLabel(t.categoryId) : '')
  }
  nextTick(() => inlineInput.value?.[0]?.focus?.())
}

function cancelInlineEdit() {
  editingField.value = { id: null, field: null, value: '' }
}

async function confirmInlineEdit(t) {
  if (editingField.value.id !== t.id) return
  const field = editingField.value.field
  const value = editingField.value.value
  editingField.value = { id: null, field: null, value: '' }

  const payload = buildPayload(t)
  if (field === 'payee') payload.payee = value
  if (field === 'category') payload.categoryId = resolveCategoryId(value)

  await TransactionsAPI.update(t.id, payload)
  await dataStore.refreshTransactions()
}

function buildPayload(t) {
  return {
    accountId: t.accountId,
    date: t.date,
    payee: t.payee,
    amount: t.amount,
    categoryId: t.categoryId,
    paymentMode: t.paymentMode || 'NONE',
    status: t.status || 'NONE',
    memo: t.memo,
    tags: t.tags,
    transferTransactionId: t.transferTransactionId,
    monthlyExpense: !!t.monthlyExpense,
    validated: !!t.validated
  }
}

/* --- Actions rapides --- */

async function validateOne(t) {
  await TransactionsAPI.toggleValidated(t.id)
  await dataStore.refreshTransactions()
}

const validatingAll = ref(false)
async function validateAllVisible() {
  if (!toProcess.value.length) return
  if (!confirm(`Valider les ${toProcess.value.length} transaction(s) affichée(s) ?`)) return
  validatingAll.value = true
  try {
    await Promise.all(toProcess.value.map(t => TransactionsAPI.toggleValidated(t.id)))
    await dataStore.refreshTransactions()
  } finally {
    validatingAll.value = false
  }
}

function ignoreOne(t) {
  ignoredIds.value = new Set([...ignoredIds.value, t.id])
}

/** Ouvre la transaction dans l'onglet "Toutes", directement en édition complète. */
function openFullEdit(t) {
  router.push({ path: '/transactions', query: { edit: t.id } })
}

/** Ouvre l'onglet Tiers avec un nouveau tiers pré-rempli à partir de ce libellé. */
function createRule(t) {
  router.push({
    path: '/payees',
    query: { prefillName: t.payee || '', prefillPattern: t.payee || '' }
  })
}

onMounted(async () => {
  await dataStore.ensureLoaded()
  dataStore.refreshAll()
})
</script>

<style scoped>
.process-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.process-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}
.process-row.row-editing-inline {
  box-shadow: inset 3px 0 0 var(--azure), var(--shadow-sm);
}

.process-main {
  display: grid;
  grid-template-columns: 90px 1fr 110px 200px;
  align-items: center;
  gap: 16px;
  flex: 1;
  min-width: 0;
}

.process-date {
  font-size: 12px;
  color: var(--text-faint);
  font-family: var(--font-mono);
}

.process-payee {
  min-width: 0;
}

.process-link {
  background: none;
  border: none;
  padding: 3px 6px;
  margin: -3px -6px;
  border-radius: 6px;
  font: inherit;
  font-weight: 600;
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
.process-link:hover {
  background: var(--ink-800);
}

.process-amount {
  font-family: var(--font-mono);
  font-weight: 700;
  text-align: right;
}

.category-link {
  font-weight: 500;
  color: var(--text-muted);
  font-size: 13px;
}
.category-link.unknown {
  color: var(--amber);
  font-weight: 700;
  background: var(--amber-dim);
}

.process-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.process-payee input,
.process-category input {
  width: 100%;
  background: var(--ink-800);
  border: 1px solid var(--azure);
  border-radius: 6px;
  padding: 5px 8px;
  font: inherit;
  color: var(--text-primary);
}

@media (max-width: 960px) {
  .process-row {
    flex-direction: column;
    align-items: stretch;
  }
  .process-main {
    grid-template-columns: 1fr 1fr;
    grid-template-areas: "date amount" "payee category";
  }
  .process-actions {
    flex-wrap: wrap;
  }
}
</style>

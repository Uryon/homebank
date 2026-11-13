<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Import CSV/QIF</h1>
        <div class="subtitle">Importez vos relevés au format HomeBank (CSV) ou Quicken (QIF)</div>
      </div>
    </div>

    <!-- « Aucun compte » seulement si les comptes ont bien été chargés (sinon :
         serveur injoignable, signalé par le bandeau global d'App.vue). -->
    <div v-if="accountsLoaded && !accounts.length" class="empty-state" role="status">
      Aucun compte n'est encore créé. Crée d'abord un compte avant d'importer un relevé.
    </div>
    <div v-else-if="accounts.length" class="import-layout">
      <section class="card import-card">
        <div class="import-step">
          <label class="step-label" for="import-account"><span class="step-num">1</span> Compte de destination</label>
          <select id="import-account" v-model.number="accountId">
            <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </div>

        <div class="import-step">
          <div class="step-label" id="import-format-label"><span class="step-num">2</span> Format du fichier</div>
          <div class="format-options" role="radiogroup" aria-labelledby="import-format-label">
            <label class="format-option" :class="{ selected: format === 'csv' }">
              <input type="radio" name="import-format" value="csv" v-model="format" @change="resetSelectedFile" />
              <strong>CSV</strong>
              <span>Format HomeBank</span>
            </label>
            <label class="format-option" :class="{ selected: format === 'qif' }">
              <input type="radio" name="import-format" value="qif" v-model="format" @change="resetSelectedFile" />
              <strong>QIF</strong>
              <span>Quicken Interchange Format</span>
            </label>
          </div>
        </div>

        <div class="import-step">
          <div class="step-label"><span class="step-num">3</span> Fichier</div>
          <label
            class="dropzone"
            :class="{ dragging, filled: !!file }"
            for="import-file"
            @dragover.prevent="dragging = true"
            @dragleave="dragging = false"
            @drop.prevent="onDrop"
          >
            <Icon name="import" size="lg" />
            <template v-if="file">
              <strong>{{ file.name }}</strong>
              <span>{{ fileSize }} · cliquez pour changer de fichier</span>
            </template>
            <template v-else>
              <strong>Déposez votre fichier ici</strong>
              <span>ou cliquez pour parcourir (.{{ format }})</span>
            </template>
            <input id="import-file" class="dropzone-input" type="file" :accept="format === 'csv' ? '.csv' : '.qif'" @change="onFileChange" />
          </label>
        </div>

        <div class="import-actions">
          <button class="btn" :disabled="!file || !accountId || loading" @click="doImport">
            {{ loading ? 'Import en cours…' : 'Importer' }}
          </button>
        </div>
      </section>

      <aside class="card import-help">
        <h2>Avant d'importer</h2>
        <ul>
          <li>Les transactions déjà présentes sont détectées et ignorées : vous pouvez réimporter un relevé sans crainte.</li>
          <li>Les lignes en erreur sont listées avec leur détail après l'import.</li>
          <li v-if="format === 'csv'">
            Colonnes attendues, séparées par des points-virgules :
            <code>date;paymode;info;payee;memo;amount;category;tags</code>
          </li>
        </ul>
      </aside>
    </div>

    <div v-if="result" class="card import-result">
      <div class="card-title">Résultat de l'import</div>
      <div class="result-tiles">
        <div class="result-tile">
          <div class="result-value amount positive">{{ result.imported }}</div>
          <div class="result-label">importée(s)</div>
        </div>
        <div class="result-tile">
          <div class="result-value">{{ result.duplicates ?? 0 }}</div>
          <div class="result-label">déjà existante(s), ignorée(s)</div>
        </div>
        <div class="result-tile">
          <div class="result-value" :class="{ 'amount negative': result.skipped > 0 }">{{ result.skipped }}</div>
          <div class="result-label">en erreur</div>
        </div>
      </div>
      <!-- Suite logique d'un import : passer en revue ce qui vient d'arriver. -->
      <div v-if="result.imported > 0" class="result-next">
        <RouterLink class="btn" :to="{ path: '/transactions', query: { pending: '1' } }">
          Voir les transactions à traiter
        </RouterLink>
        <RouterLink v-if="uncategorizedCount" class="btn secondary" :to="{ path: '/transactions', query: { uncategorized: '1' } }">
          Catégoriser ({{ uncategorizedCount }})
        </RouterLink>
      </div>
      <div v-if="result.errors?.length" class="result-errors">
        <div class="step-label">Détails des erreurs ({{ result.errors.length }})</div>
        <ul>
          <li v-for="(e, i) in visibleErrors" :key="i">{{ e }}</li>
        </ul>
        <button v-if="hiddenErrorCount" type="button" class="btn secondary result-errors-more" @click="showAllErrors = true">
          Afficher les {{ hiddenErrorCount }} autres erreurs
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { notify } from '../services/feedback'
import { computed, onMounted, ref } from 'vue'
import { ImportAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { activeAccounts } from '../services/accounts'
import { alertApiError } from '../services/errors'
import { VISIBLE_ERRORS_LIMIT, formatFileSize, importFileProblem, importOutcome } from '../services/importResult'
import Icon from '../components/Icon.vue'

// Import impossible vers un compte archivé (refusé par le backend, voir
// ServiceCompte#ensureNotArchived) : seuls les comptes actifs sont proposés.
const accounts = computed(() => activeAccounts(dataStore.state.accounts))
const accountsLoaded = computed(() => dataStore.state.loaded.accounts)
/** Transactions encore sans catégorie (rafraîchies après l'import) : raccourci « Catégoriser ». */
const uncategorizedCount = computed(() => dataStore.state.transactions.filter(t => !t.categoryId).length)
const accountId = ref(null)
const format = ref('csv')
const file = ref(null)
const loading = ref(false)
const result = ref(null)
const dragging = ref(false)

// Liste d'erreurs repliée au-delà de VISIBLE_ERRORS_LIMIT : un fichier
// entièrement mal formé en produit une par ligne (des milliers).
const showAllErrors = ref(false)
const visibleErrors = computed(() => {
  const errors = result.value?.errors || []
  return showAllErrors.value ? errors : errors.slice(0, VISIBLE_ERRORS_LIMIT)
})
const hiddenErrorCount = computed(() => (result.value?.errors?.length || 0) - visibleErrors.value.length)

const fileSize = computed(() => formatFileSize(file.value?.size))

/** Dépôt par glisser-déposer : même chemin que le sélecteur de fichier (onFileChange). */
function onDrop(e) {
  dragging.value = false
  if (!e.dataTransfer?.files?.[0]) return
  const input = document.getElementById('import-file')
  if (input) input.files = e.dataTransfer.files
  onFileChange({ target: { files: e.dataTransfer.files } })
}

/**
 * Fichier choisi (sélecteur ou glisser-déposer) : refusé tout de suite s'il
 * ne peut pas être importé (extension, fichier vide, taille), plutôt qu'après
 * l'envoi au serveur.
 */
function onFileChange(e) {
  const chosen = e.target.files?.[0] || null
  const problem = importFileProblem(chosen, format.value)
  if (problem) {
    notify(problem, 'error', 6000)
    resetSelectedFile()
    return
  }
  file.value = chosen
  result.value = null
}

function resetSelectedFile() {
  file.value = null
  result.value = null
  const input = document.getElementById('import-file')
  if (input) input.value = ''
}

async function doImport() {
  loading.value = true
  result.value = null
  showAllErrors.value = false
  try {
    result.value = format.value === 'csv'
        ? await ImportAPI.csv(file.value, accountId.value)
        : await ImportAPI.qif(file.value, accountId.value)
    // Les transactions importées changent le solde du compte : rafraîchit le
    // cache partagé (sidebar, page Comptes, page Transactions...).
    await dataStore.refreshTransactions()
    const outcome = importOutcome(result.value)
    notify(outcome.message, outcome.type, outcome.type === 'success' ? undefined : 6000)
  } catch (e) {
    alertApiError("Erreur lors de l'import", e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    await dataStore.ensureLoaded()
  } catch (e) {
    alertApiError('Erreur lors du chargement des données', e)
    return
  }
  // Compte courant par defaut (le plus souvent le compte cible d'un import
  // de releve bancaire), sinon le premier compte de la liste — meme critere
  // de priorite que le tri des comptes dans la sidebar/Comptes.vue.
  const checkingAccount = accounts.value.find(a => a.type === 'CHECKING')
  if (checkingAccount) accountId.value = checkingAccount.id
  else if (accounts.value.length) accountId.value = accounts.value[0].id
})
</script>

<style scoped>
.import-layout {
  display: grid;
  grid-template-columns: minmax(0, 620px) minmax(0, 320px);
  gap: 20px;
  align-items: start;
}
@media (max-width: 1000px) { .import-layout { grid-template-columns: 1fr; } }

.import-card { display: flex; flex-direction: column; gap: 24px; padding: 24px; }
.import-step { display: flex; flex-direction: column; gap: 10px; }
.step-label { display: flex; align-items: center; gap: 10px; font-size: 14px; font-weight: 600; color: var(--text-primary); }
.step-num {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--ink-700); color: var(--text-muted);
  font-size: 12px; font-weight: 700;
}

.import-step select {
  background: var(--ink-900);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-md);
  padding: 9px 11px;
  color: var(--text-primary);
  font-size: 14px;
  box-shadow: var(--shadow-xs);
}
.import-step select:focus { outline: none; border-color: var(--azure); box-shadow: 0 0 0 3px var(--azure-dim); }

.format-options { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.format-option {
  position: relative; display: flex; flex-direction: column; gap: 2px;
  padding: 12px 14px; border: 1px solid var(--line-strong); border-radius: var(--radius-md);
  background: var(--ink-900); cursor: pointer; transition: border-color 0.12s, box-shadow 0.12s, background 0.12s;
}
.format-option:hover { background: var(--ink-800); }
.format-option input { position: absolute; opacity: 0; pointer-events: none; }
.format-option strong { font-size: 15px; }
.format-option span { font-size: 12.5px; color: var(--text-muted); }
.format-option.selected { border-color: var(--azure); box-shadow: 0 0 0 3px var(--azure-dim); background: var(--ink-900); }
.format-option:has(input:focus-visible) { outline: 2px solid var(--azure); outline-offset: 2px; }

.dropzone {
  position: relative; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 4px; min-height: 148px; padding: 20px; text-align: center;
  border: 1.5px dashed var(--ink-600); border-radius: var(--radius-lg);
  background: var(--surface-sunken); color: var(--text-muted); cursor: pointer;
  transition: border-color 0.12s, background 0.12s;
}
.dropzone .icon { margin-bottom: 6px; color: var(--text-faint); }
.dropzone strong { color: var(--text-primary); font-size: 14px; word-break: break-all; }
.dropzone span { font-size: 13px; }
.dropzone:hover, .dropzone.dragging { border-color: var(--azure); background: var(--azure-dim); }
.dropzone.filled { border-style: solid; border-color: var(--line-strong); background: var(--ink-900); }
.dropzone.filled .icon { color: var(--mint); }
.dropzone:focus-within { outline: 2px solid var(--azure); outline-offset: 2px; }
.dropzone-input { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }

.import-actions { display: flex; justify-content: flex-end; }

.import-help h2 { margin: 0 0 10px; font-size: 15px; font-weight: 600; letter-spacing: -0.01em; }
.import-help ul { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 10px; color: var(--text-muted); font-size: 13.5px; }
.import-help code { display: block; margin-top: 6px; padding: 6px 8px; white-space: normal; word-break: break-all; }

.import-result { margin-top: 20px; max-width: 960px; }
.card-title { font-size: 15px; font-weight: 600; letter-spacing: -0.01em; margin-bottom: 14px; }
.result-tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.result-tile { padding: 14px 16px; border-radius: var(--radius-md); background: var(--ink-800); }
.result-value { font-size: 26px; font-weight: 650; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; line-height: 1.2; }
.result-label { margin-top: 2px; font-size: 13px; color: var(--text-muted); }
.result-errors { margin-top: 18px; }
.result-errors-more { margin-top: 10px; }
.result-next { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
.result-errors ul { margin: 8px 0 0; padding-left: 18px; color: var(--text-muted); font-size: 13px; max-height: 200px; overflow-y: auto; }
</style>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Import CSV/QIF</h1>
        <div class="subtitle">Importez vos relevés au format HomeBank (CSV) ou Quicken (QIF)</div>
      </div>
    </div>

    <div class="card" style="max-width: 560px;">
      <div class="form-grid">
        <div class="form-field" style="grid-column: span 2;">
          <label>Compte de destination</label>
          <select v-model.number="accountId">
            <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </div>
        <div class="form-field" style="grid-column: span 2;">
          <label>Format du fichier</label>
          <select v-model="format">
            <option value="csv">CSV (format HomeBank : date;paymode;info;payee;memo;amount;category;tags)</option>
            <option value="qif">QIF (Quicken Interchange Format)</option>
          </select>
        </div>
        <div class="form-field" style="grid-column: span 2;">
          <label>Fichier</label>
          <input type="file" :accept="format === 'csv' ? '.csv' : '.qif'" @change="onFileChange" />
        </div>
      </div>
      <div style="margin-top: 20px;">
        <button class="btn" :disabled="!file || !accountId || loading" @click="doImport">
          {{ loading ? 'Import en cours…' : 'Importer' }}
        </button>
      </div>
    </div>

    <div v-if="result" class="card" style="max-width: 560px; margin-top: 20px;">
      <div class="stat-label">Résultat de l'import</div>
      <p style="margin: 10px 0;">
        <span class="amount positive">{{ result.imported }}</span> transaction(s) importée(s),
        <span class="amount negative">{{ result.duplicates ?? 0 }}</span> déjà existante(s) (ignorée(s)),
        <span class="amount negative">{{ result.skipped }}</span> en erreur.
      </p>
      <div v-if="result.errors?.length">
        <div class="stat-label" style="margin-top: 14px;">Détails</div>
        <ul style="color: var(--text-muted); font-size: 13px; max-height: 200px; overflow-y: auto;">
          <li v-for="(e, i) in result.errors" :key="i">{{ e }}</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { AccountsAPI, ImportAPI } from '../services/api'

const accounts = ref([])
const accountId = ref(null)
const format = ref('csv')
const file = ref(null)
const loading = ref(false)
const result = ref(null)

function onFileChange(e) {
  file.value = e.target.files[0] || null
}

async function doImport() {
  loading.value = true
  result.value = null
  try {
    result.value = format.value === 'csv'
        ? await ImportAPI.csv(file.value, accountId.value)
        : await ImportAPI.qif(file.value, accountId.value)
  } catch (e) {
    alert("Erreur lors de l'import : " + (e.response?.data?.error || e.message))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  accounts.value = await AccountsAPI.list()
  if (accounts.value.length) accountId.value = accounts.value[0].id
})
</script>

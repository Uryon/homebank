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
          <label for="import-account">Compte de destination</label>
          <select id="import-account" v-model.number="accountId">
            <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </div>
        <div class="form-field" style="grid-column: span 2;">
          <label for="import-format">Format du fichier</label>
          <select id="import-format" v-model="format">
            <option value="csv">CSV (format HomeBank : date;paymode;info;payee;memo;amount;category;tags)</option>
            <option value="qif">QIF (Quicken Interchange Format)</option>
          </select>
        </div>
        <div class="form-field" style="grid-column: span 2;">
          <label for="import-file">Fichier</label>
          <input id="import-file" type="file" :accept="format === 'csv' ? '.csv' : '.qif'" @change="onFileChange" />
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
import { computed, onMounted, ref } from 'vue'
import { ImportAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { alertApiError } from '../services/errors'

const accounts = computed(() => dataStore.state.accounts)
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
    // Les transactions importées changent le solde du compte : rafraîchit le
    // cache partagé (sidebar, page Comptes, page Transactions...).
    await dataStore.refreshTransactions()
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
    alertApiError('Erreur lors du chargement des donnees', e)
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

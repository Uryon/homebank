<template>
  <section class="card backup-card" aria-labelledby="backup-title">
    <h2 id="backup-title" class="backup-title">
      <Icon name="archive" size="sm" /> Sauvegarde et restauration
    </h2>
    <p class="backup-text">
      Une sauvegarde complète (comptes, catégories, tiers, transactions, prêts) est écrite
      automatiquement chaque jour au démarrage de l'appli.
    </p>
    <p v-if="info" class="backup-status" role="status">
      <template v-if="info.derniere">Dernière sauvegarde : {{ formatDateTime(info.derniere) }} · {{ pluralize(info.nombre, 'fichier conservé', 'fichiers conservés') }}</template>
      <template v-else>Aucune sauvegarde pour l'instant.</template>
      <span class="backup-folder" :title="info.dossier">{{ info.dossier }}</span>
    </p>

    <div class="backup-actions">
      <button type="button" class="btn" :disabled="downloading" @click="download">
        {{ downloading ? 'Préparation…' : 'Télécharger une sauvegarde' }}
      </button>

      <input id="backup-file" ref="fileInput" type="file" accept=".json,application/json" class="sr-only" @change="onFileChange" />
      <label class="btn secondary backup-file" for="backup-file">
        {{ file ? file.name : 'Choisir un fichier à restaurer…' }}
      </label>
      <button type="button" class="btn danger" :disabled="!file || restoring" @click="restore">
        {{ restoring ? 'Restauration…' : 'Restaurer' }}
      </button>
    </div>
    <p class="backup-hint">
      Restaurer remplace toutes les données actuelles par celles du fichier ; elles sont d'abord
      sauvegardées, et « Annuler » les remet en place.
    </p>

    <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
  </section>
</template>

<script setup>
/**
 * Sauvegarde complète des données en un fichier JSON (téléchargement), et
 * restauration d'un tel fichier (voir ServiceSauvegarde côté backend). La
 * restauration remplace tout : confirmation explicite, puis « Annuler »
 * revient aux données écrites par le serveur juste avant.
 */
import { onMounted, ref } from 'vue'
import { BackupAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { askConfirm, notify } from '../services/feedback'
import { alertApiError } from '../services/errors'
import { downloadBlob } from '../services/csvExport'
import { pluralize, toLocalIsoDate } from '../services/format'
import { useLastAction } from '../services/lastAction'
import { useRouteActions } from '../services/routeActions'
import Icon from './Icon.vue'
import UndoBar from './UndoBar.vue'

const info = ref(null)
const file = ref(null)
const fileInput = ref(null)
const downloading = ref(false)
const restoring = ref(false)
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

const dateTimeFormatter = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'long', timeStyle: 'short' })

function formatDateTime(isoDateTime) {
  return dateTimeFormatter.format(new Date(isoDateTime))
}

async function loadInfo() {
  try {
    info.value = await BackupAPI.info()
  } catch {
    info.value = null // secondaire : la carte reste utilisable sans ces indications
  }
}

async function download() {
  downloading.value = true
  try {
    downloadBlob(`homebank-${toLocalIsoDate()}.json`, await BackupAPI.download())
    notify('Sauvegarde téléchargée.', 'success')
  } catch (e) {
    alertApiError('Erreur lors de la sauvegarde', e)
  } finally {
    downloading.value = false
  }
}

function onFileChange(event) {
  file.value = event.target.files?.[0] ?? null
}

async function restore() {
  if (!file.value) return
  const confirmed = await askConfirm(
    `Remplacer toutes les données actuelles par celles de « ${file.value.name} » ? Les données actuelles sont d'abord sauvegardées.`,
    { title: 'Restaurer une sauvegarde', confirmLabel: 'Tout remplacer' }
  )
  if (!confirmed) return
  restoring.value = true
  try {
    const result = await BackupAPI.restore(file.value)
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
    await dataStore.refreshAll()
    notify(`Sauvegarde restaurée : ${pluralize(result.lignes.transactions, 'transaction', 'transactions')}.`, 'success')
    if (result.sauvegardeAvant) {
      setLastAction('Restauration', async () => {
        await BackupAPI.undoRestore(result.sauvegardeAvant)
        await dataStore.refreshAll()
      }, { retryable: true })
    }
    loadInfo()
  } catch (e) {
    alertApiError('Erreur lors de la restauration', e)
  } finally {
    restoring.value = false
  }
}

// Commande « Télécharger une sauvegarde des données » de la palette (Ctrl+K).
useRouteActions({ backup: download })

onMounted(loadInfo)
</script>

<style scoped>
.backup-card {
  margin-top: 24px;
}
.backup-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 16px;
}
.backup-text,
.backup-hint {
  margin: 0 0 10px;
  color: var(--text-muted);
  font-size: 13px;
}
.backup-hint {
  margin: 10px 0 0;
  color: var(--text-faint);
  font-size: 12px;
}
.backup-status {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0 0 14px;
  font-size: 13px;
  color: var(--text-primary);
}
.backup-folder {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-faint);
  font-size: 12px;
}
.backup-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.backup-file {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
/* Le champ fichier est masqué : son focus clavier se voit sur le bouton qui le représente. */
#backup-file:focus-visible + .backup-file {
  outline: 2px solid var(--azure);
  outline-offset: 2px;
}
</style>

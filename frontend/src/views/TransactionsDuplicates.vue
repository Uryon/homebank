<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Doublons potentiels détectés — même compte, date et montant</div>
      </div>
    </div>

    <TransactionsSubNav />

    <div v-if="loading" class="empty-state" role="status">Analyse des transactions en cours…</div>
    <div v-else-if="loadError" class="empty-state" role="alert">
      <p>Impossible de charger les doublons. Vos transactions n'ont pas été modifiées.</p>
      <button class="btn secondary" type="button" @click="load">Réessayer</button>
    </div>

    <template v-else>
    <div v-if="certainGroups.length" class="duplicate-section">
      <h2 class="duplicate-section-title">Doublons certains ({{ certainGroups.length }})</h2>
      <div class="duplicate-section-subtitle">Même compte, date, montant et tiers (ou même identifiant fourni par la banque) — les occurrences sont interchangeables.</div>

      <div class="duplicate-list">
        <div v-for="group in certainGroups" :key="group.transactions[0].id" class="duplicate-card">
          <div class="duplicate-header">
            <div class="duplicate-summary">
              <strong>{{ group.transactions[0].payee || 'Sans tiers' }}</strong>
              · {{ formatAmount(group.transactions[0].amount) }}
              · {{ formatDate(group.transactions[0].date) }}
            </div>
            <div class="duplicate-count">{{ group.transactions.length }} occurrences identiques</div>
          </div>

          <div class="duplicate-rows">
            <div
              v-for="t in group.transactions"
              :key="t.id"
              class="duplicate-row"
              :class="{ 'duplicate-row-keep': t.id === keeperId(group) }"
            >
              <div class="duplicate-row-info">
                <span class="duplicate-row-account">{{ accountName(t.accountId) }}</span>
                <span v-if="t.memo" class="duplicate-row-memo">{{ t.memo }}</span>
                <span class="duplicate-row-status" :class="t.validated ? 'validated' : 'pending'">
                  {{ t.validated ? 'Validée' : 'En attente' }}
                </span>
              </div>
              <span v-if="t.id === keeperId(group)" class="duplicate-keep-badge">Conservée</span>
            </div>
          </div>

          <!--
            Un seul bouton par groupe (pas un par ligne) : les occurrences d'un
            meme groupe sont, par construction, identiques (meme compte, date,
            montant, tiers) — supprimer l'une ou l'autre laisse le meme resultat.
            On conserve automatiquement une occurrence deja validee s'il y en a
            une (plus probablement la "vraie", deja rapprochee en banque), sinon
            la premiere. Pas de confirmation : l'action est peu risquee (il
            reste toujours au moins une occurrence identique) et se repete
            souvent sur cette page.
          -->
          <div class="duplicate-actions">
            <button
              class="btn danger duplicate-remove-btn"
              :disabled="removingGroupKey === group.transactions[0].id"
              @click="removeDuplicates(group)"
            >
              {{ removingGroupKey === group.transactions[0].id ? 'Suppression…' : deleteLabel(group) }}
            </button>
            <button
              class="btn secondary duplicate-not-duplicate-btn"
              :disabled="markingGroupKey === group.transactions[0].id"
              @click="markGroupNotDuplicate(group)"
            >
              {{ markingGroupKey === group.transactions[0].id ? 'Marquage…' : "Ce n'est pas un doublon" }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="probableGroups.length" class="duplicate-section">
      <h2 class="duplicate-section-title">Doublons probables ({{ probableGroups.length }})</h2>
      <div class="duplicate-section-subtitle">
        Même compte, date et montant, mais tiers différent — souvent le même paiement importé deux fois
        (une fois catégorisé/renommé à la main, une fois avec le libellé brut de la banque). Les occurrences
        ne sont pas interchangeables : vérifiez laquelle garder avant de supprimer.
      </div>

      <div class="duplicate-list">
        <div v-for="group in probableGroups" :key="'p-' + group.transactions[0].id" class="duplicate-card duplicate-card-probable">
          <div class="duplicate-header">
            <div class="duplicate-summary">
              {{ formatAmount(group.transactions[0].amount) }}
              · {{ formatDate(group.transactions[0].date) }}
            </div>
            <div class="duplicate-count">{{ group.transactions.length }} occurrences similaires</div>
          </div>

          <div class="duplicate-rows">
            <div v-for="t in group.transactions" :key="t.id" class="duplicate-row">
              <div class="duplicate-row-info">
                <span class="duplicate-row-account">{{ accountName(t.accountId) }}</span>
                <strong class="duplicate-row-payee">{{ t.payee || 'Sans tiers' }}</strong>
                <span class="duplicate-row-category" :class="{ uncategorized: !t.categoryId }">
                  {{ categoryName(t.categoryId) }}
                </span>
                <span v-if="t.memo" class="duplicate-row-memo">{{ t.memo }}</span>
                <span class="duplicate-row-status" :class="t.validated ? 'validated' : 'pending'">
                  {{ t.validated ? 'Validée' : 'En attente' }}
                </span>
              </div>
              <div class="duplicate-row-actions">
                <button
                  class="btn secondary small"
                  :disabled="markingId === t.id"
                  @click="markSingleNotDuplicate(t)"
                >
                  {{ markingId === t.id ? 'Marquage…' : 'Non doublon' }}
                </button>
                <button
                  class="btn danger small"
                  :disabled="removingId === t.id"
                  @click="removeSingle(t, group)"
                >
                  {{ removingId === t.id ? 'Suppression…' : 'Supprimer' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="reimbursementGroups.length" class="duplicate-section">
      <h2 class="duplicate-section-title">Dépenses remboursées ({{ reimbursementGroups.length }})</h2>
      <div class="duplicate-section-subtitle">
        Même tiers, montants opposés de même valeur — probablement une dépense déjà remboursée par ce tiers
        (achat annulé, note de frais avancée…). Si c'est bien le cas, vous pouvez supprimer les deux
        transactions (elles s'annulent) ; sinon, ignorez-les pour ne plus les voir ici.
      </div>

      <div class="duplicate-list">
        <div v-for="group in reimbursementGroups" :key="'r-' + group.transactions[0].id" class="duplicate-card duplicate-card-reimbursement">
          <div class="duplicate-header">
            <div class="duplicate-summary">
              <strong>{{ group.transactions[0].payee || 'Sans tiers' }}</strong>
              · {{ formatAmount(Math.abs(group.transactions[0].amount)) }}
            </div>
            <div class="duplicate-count">{{ group.transactions.length }} occurrences liées</div>
          </div>

          <div class="duplicate-rows">
            <div v-for="t in group.transactions" :key="t.id" class="duplicate-row">
              <div class="duplicate-row-info">
                <span class="duplicate-row-account">{{ accountName(t.accountId) }}</span>
                <span class="amount" :class="t.amount >= 0 ? 'positive' : 'negative'">{{ formatAmount(t.amount) }}</span>
                <span>{{ formatDate(t.date) }}</span>
                <span v-if="t.memo" class="duplicate-row-memo">{{ t.memo }}</span>
              </div>
            </div>
          </div>

          <!--
            Un seul jeu de boutons par groupe (pas par ligne) : les
            transactions du groupe forment une paire depense/remboursement
            indissociable — supprimer ou ignorer l'une sans l'autre n'a pas
            de sens, donc l'action porte sur le groupe entier.
          -->
          <div class="duplicate-actions">
            <button
              class="btn danger duplicate-remove-btn"
              :disabled="removingGroupKey === group.transactions[0].id"
              @click="removeReimbursementGroup(group)"
            >
              {{ removingGroupKey === group.transactions[0].id ? 'Suppression…' : `Supprimer les ${group.transactions.length} transactions` }}
            </button>
            <button
              class="btn secondary duplicate-not-duplicate-btn"
              :disabled="markingGroupKey === group.transactions[0].id"
              @click="markGroupNotDuplicate(group)"
            >
              {{ markingGroupKey === group.transactions[0].id ? 'Marquage…' : 'Ignorer' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="!certainGroups.length && !probableGroups.length && !reimbursementGroups.length" class="empty-state">
      Aucun doublon détecté pour l'instant.
    </div>
    </template>

    <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
  </div>
</template>

<script setup>
/**
 * Onglet Doublons : groupes calculés par le backend (voir
 * ServiceDoublon#findDuplicateGroups et services/duplicateGroups.js).
 * - certains : occurrences interchangeables, un seul bouton par groupe garde
 *   une occurrence (voir keeperId) et supprime les autres ;
 * - probables : occurrences PAS interchangeables (tiers différent, l'une peut
 *   déjà être catégorisée) : suppression ligne par ligne, l'utilisateur
 *   identifie lui-même laquelle garder ;
 * - remboursements : paire dépense/remboursement, supprimée entière ou ignorée.
 * On ne décide jamais automatiquement laquelle garder. Pas de confirmation :
 * le bandeau « Annuler » rattrape un clic sur le mauvais groupe ou la mauvaise ligne.
 */
import { ref, computed, onMounted } from 'vue'
import { TransactionsAPI, PayeesAPI } from '../services/api'
import { notify, askText } from '../services/feedback'
import { alertApiError } from '../services/errors'
import { dataStore } from '../services/dataStore'
import { formatAmount, formatDate, pluralize } from '../services/format'
import { deleteLabel, keeperId, patternSuggestionFor, patternWithLabel, splitDuplicateGroups } from '../services/duplicateGroups'
import { useLastAction } from '../services/lastAction'
import { restorationSnapshots } from '../services/undoRestore'
import TransactionsSubNav from './TransactionsSubNav.vue'
import UndoBar from '../components/UndoBar.vue'

const groups = ref([])

/**
 * Annulation de la derniere suppression (voir services/lastAction.js) : les
 * suppressions de cette page se font sans confirmation, le bandeau
 * « Annuler » rattrape un clic sur le mauvais groupe ou la mauvaise ligne.
 */
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

/**
 * Prepare la restauration des transactions supprimees. Le groupe est ajoute
 * aux transactions du cache pour retrouver la jumelle d'un virement meme si
 * le cache n'est pas encore charge.
 */
function prepareRestore(transactionsToDelete) {
  const snapshots = restorationSnapshots(transactionsToDelete, [...transactionsToDelete, ...dataStore.state.transactions])
  return async () => {
    await TransactionsAPI.restore(snapshots)
    await dataStore.refreshTransactions()
    await load()
  }
}
const loading = ref(false)
const loadError = ref(false)
const splitGroups = computed(() => splitDuplicateGroups(groups.value))
const certainGroups = computed(() => splitGroups.value.certain)
const probableGroups = computed(() => splitGroups.value.probable)
const reimbursementGroups = computed(() => splitGroups.value.reimbursement)

// Cle de groupe (id de sa premiere transaction) actuellement en cours de
// nettoyage, pour desactiver/etiqueter uniquement le bouton concerne.
const removingGroupKey = ref(null)
// Id de la transaction en cours de suppression individuelle (doublons probables).
const removingId = ref(null)
// Memes principes que removingGroupKey/removingId, pour le marquage "non doublon".
const markingGroupKey = ref(null)
const markingId = ref(null)

// Map (id -> compte) precalculee, pour eviter un scan lineaire de accounts
// par ligne affichee (meme principe que accountsById dans Transactions.vue).
const accountsById = computed(() => new Map(dataStore.state.accounts.map(a => [a.id, a])))

function accountName(accountId) {
  return accountsById.value.get(accountId)?.name ?? '—'
}

// Idem pour les categories : affichees sur les doublons "probables" pour
// aider a distinguer les deux occurrences (voir duplicate-row-category).
const categoriesById = computed(() => new Map(dataStore.state.categories.map(c => [c.id, c])))

function categoryName(categoryId) {
  return categoriesById.value.get(categoryId)?.name ?? 'Non catégorisé'
}

// Idem pour les tiers (voir suggestPatternUpdate ci-dessous).
const payeesById = computed(() => new Map(dataStore.state.payees.map(p => [p.id, p])))

async function load() {
  loading.value = true
  loadError.value = false
  try {
    groups.value = await TransactionsAPI.duplicates()
  } catch (error) {
    loadError.value = true
    alertApiError('Erreur lors du chargement des doublons', error)
  } finally {
    loading.value = false
  }
}

/**
 * Supprime des transactions d'un groupe en un clic, annulable. Si une
 * suppression échoue après d'autres (Promise.all), l'écran est resynchronisé
 * malgré tout pour ne pas afficher un état périmé.
 */
async function removeFromGroup(group, toRemove, label, errorMessage) {
  const undo = prepareRestore(toRemove)
  removingGroupKey.value = group.transactions[0].id
  try {
    await Promise.all(toRemove.map(t => TransactionsAPI.remove(t.id)))
    setLastAction(label, undo, { retryable: true })
    await dataStore.refreshTransactions()
    notify(`${label}.`)
    await load()
  } catch (error) {
    alertApiError(errorMessage, error)
    await dataStore.refreshTransactions().catch(() => {})
    await load()
  } finally {
    removingGroupKey.value = null
  }
}

/**
 * Doublons certains : supprime toutes les occurrences sauf celle conservée
 * (voir keeperId). Les occurrences sont identiques (compte, date, montant,
 * tiers) : inutile de faire choisir laquelle supprimer.
 */
function removeDuplicates(group) {
  const keepId = keeperId(group)
  const toRemove = group.transactions.filter(t => t.id !== keepId)
  const label = pluralize(toRemove.length, 'doublon supprimé', 'doublons supprimés')
  return removeFromGroup(group, toRemove, label.charAt(0).toUpperCase() + label.slice(1), 'Erreur lors de la suppression des doublons')
}

/** Annulation d'un marquage « pas un doublon » : le groupe réapparaît. */
async function unmarkAndReload(ids) {
  await TransactionsAPI.unmarkNotDuplicate(ids)
  await load()
}

/**
 * Marque toutes les occurrences d'un groupe "certain" comme n'etant pas des
 * doublons (voir ServiceDoublon#markAsNotDuplicate) : le groupe entier
 * disparait ensuite de cette page, sans supprimer aucune transaction.
 */
async function markGroupNotDuplicate(group) {
  markingGroupKey.value = group.transactions[0].id
  const ids = group.transactions.map(t => t.id)
  try {
    await TransactionsAPI.markNotDuplicate(ids)
    notify('Groupe marqué comme non doublon.')
    setLastAction('Groupe ignoré', () => unmarkAndReload(ids))
    await load()
  } catch (error) {
    alertApiError('Erreur lors du marquage', error)
  } finally {
    markingGroupKey.value = null
  }
}

/**
 * Marque une seule occurrence d'un doublon "probable" comme n'etant pas un
 * doublon : suffit a faire disparaitre tout le groupe (qui necessite au
 * moins 2 occurrences restantes, voir ServiceDoublon#findDuplicateGroups),
 * sans avoir a marquer chaque ligne separement.
 */
async function markSingleNotDuplicate(transaction) {
  markingId.value = transaction.id
  try {
    await TransactionsAPI.markNotDuplicate([transaction.id])
    notify('Transaction marquée comme non doublon.')
    setLastAction('Marquée comme non doublon', () => unmarkAndReload([transaction.id]))
    await load()
  } catch (error) {
    alertApiError('Erreur lors du marquage', error)
  } finally {
    markingId.value = null
  }
}

/**
 * Remboursements : supprime TOUT le groupe, paire dépense/remboursement
 * indissociable — supprimer l'une sans l'autre laisserait une dépense
 * fantôme (ou un remboursement sans dépense) dans l'historique. Rien à
 * apprendre pour le motif du tiers : la paire partage déjà le même tiers.
 */
function removeReimbursementGroup(group) {
  const count = group.transactions.length
  return removeFromGroup(group, group.transactions, `${count} transactions supprimées`, 'Erreur lors de la suppression')
}

/**
 * Suppression ligne par ligne pour un doublon "probable" (voir groups
 * ci-dessus) : contrairement a un groupe "certain", les occurrences ne sont
 * pas interchangeables (tiers different, l'une peut deja etre categorisee et
 * l'autre non) — c'est pourquoi il y a un bouton par ligne plutot qu'un seul
 * bouton par groupe. Pas de confirm() : l'utilisateur a deja choisi la bonne
 * ligne en la regardant (compte/tiers/categorie affiches), et cette page se
 * parcourt souvent d'une traite.
 */
async function removeSingle(transaction, group) {
  removingId.value = transaction.id
  const undo = prepareRestore([transaction])
  let deleted = false
  try {
    await TransactionsAPI.remove(transaction.id)
    deleted = true
    setLastAction('Transaction supprimée', undo, { retryable: true })
    notify('Transaction supprimée.')
    try {
      await suggestPatternUpdate(transaction, group)
    } catch (error) {
      alertApiError('Transaction supprimée, mais le motif du tiers n’a pas pu être mis à jour', error)
    }
  } catch (error) {
    alertApiError('Erreur lors de la suppression', error)
  } finally {
    if (deleted) {
      try {
        await dataStore.refreshTransactions()
        await load()
      } catch (error) {
        alertApiError('Transaction supprimée, mais le rafraîchissement a échoué', error)
      }
    }
    removingId.value = null
  }
}

/**
 * Après suppression d'une occurrence de doublon probable, propose d'ajouter
 * son libellé au motif du tiers de l'occurrence conservée (voir
 * patternSuggestionFor). La fenêtre affiche le motif actuel, modifiable (ex :
 * pour le raccourcir) ; le libellé est ajouté à la fin. Annuler ne modifie rien.
 */
async function suggestPatternUpdate(deletedTransaction, group) {
  const suggestion = patternSuggestionFor(deletedTransaction, group, payeesById.value)
  if (!suggestion) return
  const { payee, label } = suggestion

  const editedPattern = await askText(
    `Motif du tiers « ${payee.name} ». Le libellé de la transaction supprimée sera ajouté à la fin.`,
    payee.pattern || '',
    { title: 'Mettre à jour le motif du tiers', placeholder: 'Motif de reconnaissance' }
  )
  if (editedPattern === null) return // saisie annulée

  const newPattern = patternWithLabel(editedPattern, label)
  if (!newPattern) return

  await PayeesAPI.update(payee.id, {
    name: payee.name,
    pattern: newPattern,
    amount: payee.amount,
    categoryId: payee.categoryId
  })
  await dataStore.refreshPayees()
}

onMounted(load)
</script>

<style scoped>
.duplicate-section + .duplicate-section {
  margin-top: 28px;
}

.duplicate-section-title {
  font-size: 16px;
  margin: 0 0 4px;
}

.duplicate-section-subtitle {
  color: var(--text-muted);
  font-size: 13px;
  margin-bottom: 12px;
}

.duplicate-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.duplicate-card {
  padding: 14px 16px;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
}

/* Doublons "probables" : bordure ambre pour signaler visuellement que
   contrairement aux doublons "certains", les occurrences ne sont pas
   interchangeables (tiers different) et meritent une verification. */
.duplicate-card-probable {
  border-color: var(--amber-dim);
}

/* Groupes "remboursement" : bordure azur, distincte de l'amber des
   "probables" (pas la meme nature d'alerte — ici pas de doublon a nettoyer,
   juste un rapprochement depense/remboursement a reperer). */
.duplicate-card-reimbursement {
  border-color: var(--azure-dim);
}

.duplicate-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.duplicate-summary {
  font-size: 15px;
}

.duplicate-count {
  color: var(--text-muted);
  font-size: 13px;
  white-space: nowrap;
}

.duplicate-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.duplicate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  background: var(--ink-800);
  border-radius: var(--radius-sm);
}

.duplicate-row-info {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  font-size: 13px;
  color: var(--text-muted);
}

.duplicate-row-account {
  font-weight: 600;
  color: var(--text-primary);
}

.duplicate-row-payee {
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.duplicate-row-category {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--ink-900);
  border: 1px solid var(--line);
}
.duplicate-row-category.uncategorized {
  color: var(--amber);
  border-color: var(--amber-dim);
}

.duplicate-row-memo {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.duplicate-row-status {
  font-size: 11px;
  text-transform: none;
  letter-spacing: .04em;
  padding: 2px 8px;
  border-radius: 999px;
  flex-shrink: 0;
}

.duplicate-row-status.validated {
  color: var(--mint);
  background: var(--mint-dim);
}

.duplicate-row-status.pending {
  color: var(--amber);
  background: var(--amber-dim);
}

/* Occurrence conservee automatiquement par le bouton du groupe (voir
   keeperId ci-dessus) : légèrement mise en avant pour que l'utilisateur
   voie d'un coup d'œil laquelle restera avant de cliquer. */
.duplicate-row-keep {
  background: var(--azure-dim);
  box-shadow: inset 2px 0 0 var(--azure);
}
.duplicate-keep-badge {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  color: var(--azure);
}

.duplicate-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.duplicate-remove-btn {
  margin: 0;
}
.duplicate-not-duplicate-btn {
  margin: 0;
}

.duplicate-row-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

/* Bouton de suppression individuelle sur chaque ligne d'un doublon probable
   (voir removeSingle) : plus discret que .duplicate-remove-btn, qui agit sur
   tout un groupe. */
.btn.small {
  padding: 4px 10px;
  font-size: 12px;
  flex-shrink: 0;
}

@media (max-width: 720px) {
  .duplicate-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Doublons potentiels détectés — même compte, date et montant</div>
      </div>
    </div>

    <TransactionsSubNav />

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
        ne sont pas interchangeables : vérifie laquelle garder avant de supprimer.
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
        (achat annulé, note de frais avancée...). Si c'est bien le cas, tu peux supprimer les deux
        transactions (elles s'annulent) ; sinon, ignore-les pour ne plus les voir ici.
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
      🎉 Aucun doublon détecté pour l'instant.
    </div>
  </div>
</template>

<script setup>
/**
 * Groupes calcules cote backend (TransactionService#findDuplicateGroups), en
 * trois categories distinguees par group.certain / group.reimbursement :
 * - certains : meme compte, et soit meme identifiant externe fourni par la
 *   banque, soit meme date + montant + tiers canonique (insensible a la
 *   casse) — memes criteres qu'a la detection de doublons a l'import
 *   (ImportService#isDuplicate). Occurrences interchangeables : un seul
 *   bouton "supprimer" par groupe suffit.
 * - probables : meme compte + date + montant seulement, tiers different —
 *   ratees par la detection "certains" (import brut jamais categorise a
 *   cote d'une transaction deja categorisee/renommee pour le meme paiement,
 *   voir ServiceTransaction). Occurrences PAS interchangeables : suppression
 *   ligne par ligne, l'utilisateur doit lui-meme identifier laquelle garder.
 * - remboursements (group.reimbursement) : meme tiers, montants opposes de
 *   meme valeur absolue (ex : achat de 19€ puis remboursement de 19€ par ce
 *   meme tiers) — pas un import en double, donc pas de suppression proposee,
 *   juste un reperage a ignorer une fois verifie.
 * L'utilisateur choisit lui-meme quelle(s) occurrence(s) supprimer : on ne
 * decide jamais automatiquement laquelle garder.
 */
import { ref, computed, onMounted } from 'vue'
import { TransactionsAPI, PayeesAPI } from '../services/api'
import { dataStore } from '../services/dataStore'
import { formatAmount, formatDate } from '../services/format'
import TransactionsSubNav from './TransactionsSubNav.vue'

const groups = ref([])
const certainGroups = computed(() => groups.value.filter(g => g.certain))
const probableGroups = computed(() => groups.value.filter(g => !g.certain && !g.reimbursement))
const reimbursementGroups = computed(() => groups.value.filter(g => g.reimbursement))

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

/**
 * Occurrence conservee automatiquement dans un groupe : la premiere deja
 * validee (rapprochement bancaire, donc plus probablement la "vraie"
 * transaction) s'il y en a une, sinon simplement la premiere occurrence.
 */
function keeperId(group) {
  const validated = group.transactions.find(t => t.validated)
  return (validated ?? group.transactions[0]).id
}

function deleteLabel(group) {
  const extra = group.transactions.length - 1
  return extra === 1 ? 'Supprimer le doublon' : `Garder 1 occurrence, supprimer ${extra} doublons`
}

async function load() {
  groups.value = await TransactionsAPI.duplicates()
}

/**
 * Supprime en un clic toutes les occurrences du groupe sauf celle conservee
 * (voir keeperId) : les occurrences d'un meme groupe sont identiques (meme
 * compte, date, montant, tiers), donc un seul bouton par groupe suffit —
 * inutile de faire choisir laquelle supprimer parmi des lignes identiques.
 * Pas de confirm() : action peu risquee (une occurrence identique reste
 * toujours) et frequente sur cette page.
 */
async function removeDuplicates(group) {
  const keepId = keeperId(group)
  const idsToRemove = group.transactions.filter(t => t.id !== keepId).map(t => t.id)
  removingGroupKey.value = group.transactions[0].id
  try {
    await Promise.all(idsToRemove.map(id => TransactionsAPI.remove(id)))
    await dataStore.refreshTransactions()
    await load()
  } finally {
    removingGroupKey.value = null
  }
}

/**
 * Marque toutes les occurrences d'un groupe "certain" comme n'etant pas des
 * doublons (voir ServiceTransaction#markAsNotDuplicate) : le groupe entier
 * disparait ensuite de cette page, sans supprimer aucune transaction.
 */
async function markGroupNotDuplicate(group) {
  markingGroupKey.value = group.transactions[0].id
  try {
    await TransactionsAPI.markNotDuplicate(group.transactions.map(t => t.id))
    await load()
  } finally {
    markingGroupKey.value = null
  }
}

/**
 * Marque une seule occurrence d'un doublon "probable" comme n'etant pas un
 * doublon : suffit a faire disparaitre tout le groupe (qui necessite au
 * moins 2 occurrences restantes, voir ServiceTransaction#findDuplicateGroups),
 * sans avoir a marquer chaque ligne separement.
 */
async function markSingleNotDuplicate(transaction) {
  markingId.value = transaction.id
  try {
    await TransactionsAPI.markNotDuplicate([transaction.id])
    await load()
  } finally {
    markingId.value = null
  }
}

/**
 * Suppression de TOUT le groupe "remboursement" en un clic (voir groups
 * ci-dessus) : contrairement aux doublons "probables", les transactions ne
 * sont pas des occurrences interchangeables mais une paire depense/
 * remboursement indissociable — supprimer l'une sans l'autre laisserait une
 * depense fantome (ou un remboursement sans depense associee) dans
 * l'historique. Pas de suggestPatternUpdate ici (contrairement a
 * removeSingle ci-dessous) : les transactions du groupe partagent deja le
 * meme tiers, il n'y a rien a apprendre pour la reconnaissance automatique.
 */
async function removeReimbursementGroup(group) {
  removingGroupKey.value = group.transactions[0].id
  try {
    await Promise.all(group.transactions.map(t => TransactionsAPI.remove(t.id)))
    await dataStore.refreshTransactions()
    await load()
  } finally {
    removingGroupKey.value = null
  }
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
  try {
    await TransactionsAPI.remove(transaction.id)
    await suggestPatternUpdate(transaction, group)
    await dataStore.refreshTransactions()
    await load()
  } finally {
    removingId.value = null
  }
}

/** Echappe les caracteres speciaux regex, sauf l'etoile (gardee comme joker). */
function escapeRegexChars(s) {
  return s.replace(/[.+?^${}()|[\]\\]/g, String.raw`\$&`)
}

/** Convertit un seul motif "joker" (avec *) en expression reguliere JS equivalente (miroir de ServiceTiers#wildcardToRegex). */
function wildcardToRegex(wildcardPattern) {
  return wildcardPattern
    .split('*')
    .map(part => (part ? escapeRegexChars(part) : ''))
    .join('.*')
}

/** Miroir de ServiceTiers#normalizeWhitespace. */
function normalizeWhitespace(s) {
  return s == null ? s : s.trim().replace(/\s+/g, ' ')
}

/**
 * Teste si le champ "pattern" (un ou plusieurs motifs separes par ";")
 * correspond deja au texte donne — miroir cote frontend de
 * ServiceTiers#matchesPatternField, pour eviter de proposer d'ajouter un
 * libelle deja reconnu par le motif existant (ex : motif "SOCARES" reconnaissant
 * deja "SOCARES 10.06.2026").
 */
function matchesPatternField(patternField, text) {
  if (!patternField || !patternField.trim() || !text || !text.trim()) return false
  const normalizedText = normalizeWhitespace(text)
  for (const rawPart of patternField.split(';')) {
    const part = normalizeWhitespace(rawPart)
    if (!part) continue
    const wildcarded = part.includes('*') ? part : `*${part}*`
    try {
      const regex = new RegExp(wildcardToRegex(wildcarded), 'i')
      if (regex.test(normalizedText)) return true
    } catch {
      // motif invalide : ignore, comme cote backend
    }
  }
  return false
}

/**
 * Apres suppression d'une occurrence de doublon probable, propose de mettre
 * a jour le motif du tiers de l'occurrence CONSERVEE (voir
 * ServiceTiers#matchesPatternField) : la prochaine fois qu'un import ramene
 * le libelle de la transaction supprimee (ex : prelevement mensuel dont la
 * reference bancaire change chaque mois), il sera directement reconnu comme
 * ce tiers au lieu de recreer le meme doublon. Si le motif actuel reconnait
 * deja ce libelle (voir matchesPatternField), rien n'est propose : ce serait
 * une entree redondante (ex : motif "SOCARES" reconnaissant deja "SOCARES
 * 10.06.2026"). Sinon, le prompt affiche le motif actuel du tiers, modifiable
 * directement (ex : pour le raccourcir avant d'ajouter la nouvelle entree) ;
 * le libelle de la transaction supprimee est ensuite ajoute a la fin du
 * motif ainsi edite. Annuler le prompt ne modifie rien.
 */
async function suggestPatternUpdate(deletedTransaction, group) {
  const kept = group?.transactions.find(t => t.id !== deletedTransaction.id && t.payeeId)
  if (!kept) return
  const payee = payeesById.value.get(kept.payeeId)
  if (!payee) return

  const newEntry = (deletedTransaction.payee || '').trim()
  if (!newEntry || matchesPatternField(payee.pattern, newEntry)) return

  const editedPattern = window.prompt(
    `Motif du tiers "${payee.name}" — modifie-le si besoin (le libellé de la transaction supprimée sera ajouté à la fin) :`,
    payee.pattern || ''
  )
  if (editedPattern === null) return // prompt annulé

  const newPattern = [editedPattern.trim(), newEntry].filter(Boolean).join(';')
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
  text-transform: uppercase;
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
  color: var(--coral);
  background: var(--coral-dim);
}

/* Occurrence conservee automatiquement par le bouton du groupe (voir
   keeperId ci-dessus) : légèrement mise en avant pour que l'utilisateur
   voie d'un coup d'œil laquelle restera avant de cliquer. */
.duplicate-row-keep {
  background: var(--mint-dim);
  box-shadow: inset 2px 0 0 var(--mint);
}
.duplicate-keep-badge {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  color: var(--mint);
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

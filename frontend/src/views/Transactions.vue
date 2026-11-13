<template>
  <div class="transactions-page">
    <div class="page-header">
      <div>
        <h1>Transactions</h1>
        <div class="subtitle">Historique des opérations — cliquez sur une ligne pour la modifier</div>
      </div>

      <button class="btn" @click="openCreate" :disabled="!accounts.length">
        + Nouvelle transaction
      </button>
    </div>

    <TransactionsSubNav />

    <div class="toolbar transactions-toolbar">
      <div class="form-field">
        <label for="tx-filter-account">Compte</label>
        <select id="tx-filter-account" v-model="filterAccount" @change="load">
          <option :value="null">Tous les comptes</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ accountOptionLabel(a) }}</option>
        </select>
      </div>

      <div class="form-field">
        <span class="form-field-label">Période</span>
        <MonthYearFilter
          :month="filterMonth"
          :year="filterYear"
          :available-years="availableYears"
          @update:month="filterMonth = $event"
          @update:year="filterYear = $event"
        />
      </div>

      <div class="toolbar-divider" aria-hidden="true"></div>

      <div class="toolbar-actions">
        <button
          class="chip"
          :class="{ active: pendingOnly }"
          :aria-pressed="pendingOnly"
          @click="pendingOnly = !pendingOnly"
          :title="pendingOnly ? 'Revenir à toutes les transactions' : 'N\'afficher que les transactions à traiter'"
        >
          À traiter <span class="chip-count">{{ pendingCount }}</span>
        </button>

        <button
          class="chip"
          :class="{ active: uncategorizedOnly }"
          :aria-pressed="uncategorizedOnly"
          @click="uncategorizedOnly = !uncategorizedOnly"
          :title="uncategorizedOnly ? 'Revenir à toutes les transactions' : 'N\'afficher que les transactions sans catégorie'"
        >
          Sans catégorie <span class="chip-count">{{ uncategorizedCount }}</span>
        </button>

        <!-- Actions en masse affichées seulement s'il y a des transactions à traiter. -->
        <template v-if="pendingCount > 0">
          <button
            class="btn"
            :disabled="validating"
            @click="validateAll"
          >
            {{ validating ? 'Validation…' : `Tout valider (${pendingCount})` }}
          </button>

          <button
            class="btn danger"
            :disabled="deletingPending"
            @click="deletePending"
          >
            {{ deletingPending ? 'Suppression…' : `Supprimer les non validées` }}
          </button>
        </template>
      </div>

      <div class="form-field" style="margin-left: auto;">
        <label for="tx-search" title="Tiers, catégorie ou montant. Séparez plusieurs critères par point-virgule pour tous les exiger à la fois (ET), ex : Bouygues;29,99. Entre guillemets (&quot;CAF&quot;), le critère doit correspondre exactement au lieu de simplement le contenir. Ou &gt; / &lt; / = un montant seul.">Rechercher</label>
        <input id="tx-search" v-model="searchQuery" placeholder="Tiers, catégorie, montant… ex : > 100" style="min-width: 280px;" />
        <div v-if="amountQuery" class="text-hint">
          {{ amountSearchLoading ? 'Recherche…' : `${filteredTransactions.length} transaction(s) trouvée(s)` }}
        </div>
      </div>
    </div>

    <!-- Barre flottante en bas de l'écran : reste visible même quand on coche
         des lignes loin dans la liste (elle était auparavant au-dessus du tableau). -->
    <Transition name="bulk-bar">
      <div v-if="selectedIds.size" class="bulk-bar" role="region" aria-label="Actions sur la sélection">
        <span class="bulk-count" aria-live="polite">
          {{ selectedIds.size }} sélectionnée{{ selectedIds.size > 1 ? 's' : '' }}
        </span>
        <span class="bulk-total" :class="selectedTotal >= 0 ? 'positive' : 'negative'">
          {{ formatAmount(selectedTotal) }}
        </span>
        <span class="bulk-separator" aria-hidden="true" />
        <button class="btn" @click="openBulkEdit"><Icon name="edit" size="sm" /> Modifier</button>
        <button class="btn danger" :disabled="deletingSelection" @click="deleteSelected">
          <Icon name="trash" size="sm" /> {{ deletingSelection ? 'Suppression…' : 'Supprimer' }}
        </button>
        <button class="bulk-close" type="button" title="Désélectionner tout (Échap)" aria-label="Désélectionner tout" @click="clearSelection">
          <Icon name="x" size="sm" />
        </button>
      </div>
    </Transition>

    <div class="table-scroll" v-if="filteredTransactions.length">
      <table class="transactions-table">
        <colgroup>
          <col class="col-select" />
          <col class="col-status" />
          <col class="col-date" />
          <col class="col-account" />
          <col class="col-payee" />
          <col class="col-category" />
          <col class="col-memo" />
          <col class="col-amount" />
          <col class="col-amount" />
          <col class="col-monthly" />
          <col class="col-monthly" />
          <col class="col-actions" />
        </colgroup>
        <thead>
          <tr>
            <th class="num">
              <input
                type="checkbox"
                :checked="allPagedSelected"
                title="Sélectionner/désélectionner toutes les transactions actuellement affichées"
                aria-label="Sélectionner/désélectionner toutes les transactions actuellement affichées"
                @change="toggleSelectAllPaged"
              />
            </th>
            <th title="Statut de validation (rapprochement bancaire)"><span class="sr-only">Statut</span></th>
            <th>Date</th>
            <th>Compte</th>
            <th>Tiers</th>
            <th>Catégorie</th>
            <th>Mémo</th>
            <th class="num">Dépense</th>
            <th class="num">Revenu</th>
            <th class="num th-icon" title="Dépense périodique récurrente">
              <Icon name="refresh" size="sm" /><span class="sr-only">Récurrent</span>
            </th>
            <th class="num th-icon" title="Transaction exceptionnelle : exclue des tableaux de bord (ex : achat d'une voiture)">
              <Icon name="eyeOff" size="sm" /><span class="sr-only">Exception</span>
            </th>
            <th></th>
          </tr>
        </thead>

        <tbody>
          <template v-for="(t, index) in pagedTransactions" :key="t.id">
          <!-- Intertitre à chaque changement de mois (liste triée par date décroissante) -->
          <tr v-if="monthStartsAt(index)" class="month-row">
            <th colspan="12" scope="rowgroup">
              <span class="month-row-label">{{ monthHeading(t.date) }}</span>
              <span class="month-row-meta">
                {{ monthSummary(t.date).count }} transaction{{ monthSummary(t.date).count > 1 ? 's' : '' }}
                · solde
                <span class="amount" :class="monthSummary(t.date).total >= 0 ? 'positive' : 'negative'">{{ formatAmount(monthSummary(t.date).total) }}</span>
              </span>
            </th>
          </tr>
          <TransactionRow
            :transaction="t"
            :is-editing="editingId === t.id"
            :accounts="accounts"
            :payees="payees"
            :flat-categories="flatCategories"
            :accounts-by-id="accountsById"
            :categories-by-id="categoriesById"
            :payee-index="payeeByNormalizedName"
            :today-iso="todayIso"
            :selected="selectedIds.has(t.id)"
            @start-edit="startEdit(t)"
            @cancel-edit="cancelEdit"
            @confirm-edit="confirmEdit"
            @toggle-validated="toggleValidated"
            @toggle-periodic="togglePeriodic"
            @toggle-exceptional="toggleExceptional"
            @toggle-select="toggleSelect"
            @remove="remove"
            @split="openSplit"
          />
          </template>
        </tbody>
      </table>
    </div>

    <div v-if="filteredTransactions.length" class="scroll-status">
      <span class="text-hint">
        {{ pagedTransactions.length }} / {{ filteredTransactions.length }} transaction(s) affichée(s)
      </span>
      <!-- Sentinelle observée pour charger le lot suivant en approchant du bas (voir loadMoreObserver) : rootMargin la declenche avant d'etre reellement visible, pour un chargement fluide pendant le scroll. -->
      <div v-if="hasMoreToShow" ref="loadMoreSentinel" class="load-more-sentinel">Chargement…</div>
    </div>

    <div v-else-if="!transactionsLoaded && loadFailed" class="empty-state">
      Transactions indisponibles tant que le serveur ne répond pas.
    </div>
    <SkeletonRows v-else-if="!transactionsLoaded" label="Chargement des transactions…" :columns="['3%', '10%', '10%', '16%', '20%', '18%', '9%']" :rows="10" />
    <div v-else class="empty-state">
      {{
        !accounts.length
          ? "Créez d'abord un compte pour ajouter des transactions."
          : searchQuery.trim()
            ? "Aucune transaction ne correspond à cette recherche."
            : 'Aucune transaction pour ce filtre.'
      }}
    </div>

    <!-- Modal : création uniquement -->
    <div v-if="showModal" class="modal-overlay" @click.self="showModal = false">
      <div v-dialog="() => (showModal = false)" class="modal" role="dialog" aria-modal="true">
        <h2>Nouvelle transaction</h2>

        <div class="form-grid">
          <div class="form-field">
            <label for="new-tx-account">Compte</label>
            <select id="new-tx-account" v-model.number="form.accountId">
              <option v-for="a in entryAccounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>

          <div class="form-field">
            <label for="new-tx-date">Date</label>
            <input id="new-tx-date" v-model="form.date" type="date" />
          </div>

          <div class="form-field">
            <label for="new-tx-payee">Tiers (payee)</label>
            <PayeeAutocomplete id="new-tx-payee" v-model="form.payee" :payees="payees" placeholder="Ex : Supermarché" />
          </div>

          <div class="form-field">
            <label for="new-tx-amount">Montant (négatif = dépense)</label>
            <AmountInput id="new-tx-amount" v-model="form.amount" :allow-relative="false" />
          </div>

          <div class="form-field">
            <label for="new-tx-category">Catégorie</label>
            <CategoryAutocomplete
              id="new-tx-category"
              v-model="form.categoryId"
              :categories="flatCategories"
              :transaction-type="formAmountType"
              placeholder="Tapez pour rechercher, ou laissez vide"
            />
          </div>

          <div class="form-field">
            <label for="new-tx-payment-mode">Mode de paiement</label>
            <select id="new-tx-payment-mode" v-model="form.paymentMode">
              <option value="NONE">Aucun</option>
              <option value="CASH">Espèces</option>
              <option value="CHEQUE">Chèque</option>
              <option value="TRANSFER">Virement</option>
              <option value="CARD">Carte</option>
              <option value="DIRECT_DEBIT">Prélèvement</option>
              <option value="STANDING_ORDER">Virement permanent</option>
            </select>
          </div>

          <div class="form-field" style="grid-column: span 2;">
            <label for="new-tx-memo">Mémo</label>
            <input id="new-tx-memo" v-model="form.memo" placeholder="Note libre" />
          </div>

          <div class="form-field" style="grid-column: span 2;">
            <label for="new-tx-tags">Tags (séparés par des espaces)</label>
            <input id="new-tx-tags" v-model="form.tags" />
          </div>

          <div class="form-field" style="grid-column: span 2; flex-direction: row; align-items: center; gap: 8px;">
            <input type="checkbox" v-model="form.periodicExpense" id="new-periodic-expense" style="width: auto;" />
            <label for="new-periodic-expense" style="margin: 0;">Dépense périodique récurrente (loyer, abonnement...)</label>
          </div>

          <div class="form-field" style="grid-column: span 2; flex-direction: row; align-items: center; gap: 8px;">
            <input type="checkbox" v-model="form.exceptional" id="new-exceptional" style="width: auto;" />
            <label for="new-exceptional" style="margin: 0;">Transaction exceptionnelle (exclue des tableaux de bord, ex : achat d'une voiture)</label>
          </div>
        </div>

        <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px;">
          <button class="btn secondary" @click="showModal = false">Annuler</button>
          <button class="btn" @click="save">Enregistrer</button>
        </div>
      </div>
    </div>

    <!-- Modal : édition en masse des transactions sélectionnées -->
    <div v-if="showBulkEditModal" class="modal-overlay" @click.self="showBulkEditModal = false">
      <div v-dialog="() => (showBulkEditModal = false)" class="modal bulk-edit-modal" role="dialog" aria-modal="true">
        <h2>Modifier {{ selectedIds.size }} transaction(s)</h2>
        <div class="text-hint" style="margin-bottom: 20px;">
          Modifiez uniquement les champs à changer : un champ non touché reste inchangé sur chaque transaction.
        </div>

        <div class="bulk-section">
          <div class="bulk-section-title">Catégorisation</div>

          <div class="bulk-field" :class="{ 'bulk-field-active': bulkForm.categoryTouched }">
            <div class="bulk-field-label">Catégorie</div>
            <div class="bulk-field-control">
              <CategoryAutocomplete
                id="bulk-category"
                aria-label="Catégorie"
                :model-value="bulkForm.categoryId"
                @update:model-value="onBulkCategoryChange"
                :categories="flatCategories"
                placeholder="Ne pas modifier"
              />
            </div>
            <span v-if="bulkForm.categoryTouched" class="bulk-field-check">✓</span>
          </div>

          <div class="bulk-field" :class="{ 'bulk-field-active': bulkForm.payeeTouched }">
            <div class="bulk-field-label">Tiers</div>
            <div class="bulk-field-control">
              <PayeeAutocomplete
                id="bulk-payee"
                aria-label="Tiers"
                :model-value="bulkForm.payee"
                @update:model-value="onBulkPayeeChange"
                :payees="payees"
                placeholder="Ne pas modifier"
              />
            </div>
            <span v-if="bulkForm.payeeTouched" class="bulk-field-check">✓</span>
          </div>

          <div class="bulk-field" :class="{ 'bulk-field-active': bulkForm.paymentMode !== null }">
            <div class="bulk-field-label">Mode de paiement</div>
            <div class="bulk-field-control">
              <select v-model="bulkForm.paymentMode" aria-label="Mode de paiement">
                <option :value="null">Ne pas modifier</option>
                <option value="NONE">Aucun</option>
                <option value="CASH">Espèces</option>
                <option value="CHEQUE">Chèque</option>
                <option value="TRANSFER">Virement</option>
                <option value="CARD">Carte</option>
                <option value="DIRECT_DEBIT">Prélèvement</option>
                <option value="STANDING_ORDER">Virement permanent</option>
              </select>
            </div>
            <span v-if="bulkForm.paymentMode !== null" class="bulk-field-check">✓</span>
          </div>
        </div>

        <div class="bulk-section">
          <div class="bulk-section-title">Marqueurs</div>

          <div class="bulk-field" :class="{ 'bulk-field-active': bulkForm.periodicExpense !== null }">
            <div class="bulk-field-label">Dépense périodique</div>
            <div class="bulk-field-control">
              <select v-model="bulkForm.periodicExpense" aria-label="Dépense périodique">
                <option :value="null">Ne pas modifier</option>
                <option :value="true">Oui</option>
                <option :value="false">Non</option>
              </select>
            </div>
            <span v-if="bulkForm.periodicExpense !== null" class="bulk-field-check">✓</span>
          </div>

          <div class="bulk-field" :class="{ 'bulk-field-active': bulkForm.exceptional !== null }">
            <div class="bulk-field-label">Exceptionnelle</div>
            <div class="bulk-field-control">
              <select v-model="bulkForm.exceptional" aria-label="Exceptionnelle">
                <option :value="null">Ne pas modifier</option>
                <option :value="true">Oui</option>
                <option :value="false">Non</option>
              </select>
            </div>
            <span v-if="bulkForm.exceptional !== null" class="bulk-field-check">✓</span>
          </div>
        </div>

        <div class="bulk-section">
          <div class="bulk-section-title">Statut</div>

          <div class="bulk-field" :class="{ 'bulk-field-active': bulkForm.validated !== null }">
            <div class="bulk-field-label">Validée / en attente</div>
            <div class="bulk-field-control">
              <select v-model="bulkForm.validated" aria-label="Validée / en attente">
                <option :value="null">Ne pas modifier</option>
                <option :value="true">Validée</option>
                <option :value="false">En attente</option>
              </select>
            </div>
            <span v-if="bulkForm.validated !== null" class="bulk-field-check">✓</span>
          </div>
        </div>

        <div class="bulk-footer">
          <button
            v-if="bulkHasAnyFieldEnabled"
            type="button"
            class="bulk-clear-link"
            @click="resetBulkForm"
          >
            Réinitialiser ({{ bulkTouchedFieldCount }} champ{{ bulkTouchedFieldCount > 1 ? 's' : '' }} modifié{{ bulkTouchedFieldCount > 1 ? 's' : '' }})
          </button>
          <span v-else class="text-hint">Aucune modification pour l'instant</span>

          <div class="bulk-footer-actions">
            <button class="btn secondary" @click="showBulkEditModal = false">Annuler</button>
            <button class="btn" :disabled="!bulkHasAnyFieldEnabled || bulkSaving" @click="saveBulkEdit">
              {{ bulkSaving ? 'Application…' : `Appliquer à ${selectedIds.size} transaction(s)` }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Modal : division d'une transaction en deux -->
    <div v-if="showSplitModal" class="modal-overlay" @click.self="closeSplitModal">
      <div v-dialog="closeSplitModal" class="modal" role="dialog" aria-modal="true">
        <h2>Diviser la transaction</h2>
        <div class="text-hint" style="margin-bottom: 20px;">
          {{ splitTransaction?.payee || 'Sans tiers' }} · {{ formatDate(splitTransaction?.date) }} ·
          Montant total : {{ formatAmount(splitTransaction?.amount) }}
        </div>

        <div class="form-grid">
          <div class="form-field">
            <label for="split-first-amount">Montant — 1ère partie {{ splitTransaction?.amount < 0 ? '(négatif = dépense)' : '(positif = revenu)' }}</label>
            <AmountInput id="split-first-amount" v-model="splitForm.firstAmount" :allow-relative="false" />
          </div>
          <div class="form-field">
            <label for="split-first-category">Catégorie — 1ère partie</label>
            <CategoryAutocomplete
              id="split-first-category"
              v-model="splitForm.firstCategoryId"
              :categories="flatCategories"
              :transaction-type="splitAmountType"
              placeholder="Aucune"
            />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label for="split-first-memo">Mémo — 1ère partie</label>
            <input id="split-first-memo" v-model="splitForm.firstMemo" placeholder="Note libre" />
          </div>

          <div class="form-field">
            <span class="form-field-label">Montant — 2e partie</span>
            <div class="split-second-amount amount" :class="secondAmount >= 0 ? 'positive' : 'negative'">
              {{ formatAmount(secondAmount) }}
            </div>
          </div>
          <div class="form-field">
            <label for="split-second-category">Catégorie — 2e partie</label>
            <CategoryAutocomplete
              id="split-second-category"
              v-model="splitForm.secondCategoryId"
              :categories="flatCategories"
              :transaction-type="splitAmountType"
              placeholder="Aucune"
            />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label for="split-second-memo">Mémo — 2e partie</label>
            <input id="split-second-memo" v-model="splitForm.secondMemo" placeholder="Note libre" />
          </div>
        </div>

        <p v-if="splitError" class="split-error">{{ splitError }}</p>

        <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px;">
          <button class="btn secondary" @click="closeSplitModal">Annuler</button>
          <button class="btn" :disabled="splitSaving" @click="saveSplit">
            {{ splitSaving ? 'Division…' : 'Diviser' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="lastAction" class="undo-bar" :class="{ 'above-bulk-bar': selectedIds.size }" role="status">
      <span>{{ lastAction.label }}</span>
      <button class="btn secondary" :disabled="undoing" @click="runLastAction">
        {{ undoing ? 'Annulation…' : 'Annuler' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { notify, askConfirm } from '../services/feedback'
import { ref, onMounted, onBeforeUnmount, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  TransactionsAPI
} from '../services/api'
import { dataStore } from '../services/dataStore'
import { planRestore, transactionPayloadFrom } from '../services/undoRestore'
import { normalizeSearch, parseAmountQuery, parseSearchTerms, matchesSearchTerm } from '../services/search'
import { flattenCategories } from '../services/categories'
import PayeeAutocomplete from '../components/PayeeAutocomplete.vue'
import CategoryAutocomplete from '../components/CategoryAutocomplete.vue'
import TransactionRow from '../components/TransactionRow.vue'
import AmountInput from '../components/AmountInput.vue'
import MonthYearFilter from '../components/MonthYearFilter.vue'
import SkeletonRows from '../components/SkeletonRows.vue'
import { formatMonthHeading, monthKey, startsNewMonth, summarizeByMonth } from '../services/monthGroups'
import Icon from '../components/Icon.vue'
import TransactionsSubNav from './TransactionsSubNav.vue'
import { formatAmount, formatDate, toLocalIsoDate } from '../services/format'
import { findPayeeByName } from '../services/payee'
import { accountOptionLabel, activeAccounts } from '../services/accounts'
import { alertApiError, apiErrorMessage } from '../services/errors'
import { bulkTouchedFields, bulkUpdatePayload, countTouchedFields, emptyBulkForm } from '../services/bulkEdit'
import { useProgressiveList } from '../services/progressiveList'

const route = useRoute()
const router = useRouter()

const filterAccount = ref(null)
const filterMonth = ref(null)
const filterYear = ref(null)
const pendingOnly = ref(false)
const uncategorizedOnly = ref(false)
const searchQuery = ref('')

// Donnees issues du cache partage (deja precharge au demarrage de l'app,
// voir App.vue / dataStore.js) : s'affichent instantanement sans attendre
// de nouvel appel reseau.
const accounts = computed(() => dataStore.state.accounts)
// Comptes proposés pour une nouvelle transaction : jamais un compte archivé
// (refusé par le backend, voir ServiceCompte#ensureNotArchived).
const entryAccounts = computed(() => activeAccounts(accounts.value))
const transactionsLoaded = computed(() => dataStore.state.loaded.transactions && dataStore.state.loaded.accounts)
const loadFailed = computed(() => dataStore.state.loadFailed)
const categories = computed(() => dataStore.state.categories)
const payees = computed(() => dataStore.state.payees)

// Liste "par defaut" (tous comptes confondus) issue du cache. Quand un
// filtre par compte est actif, on utilise une liste chargee a la demande
// (non mise en cache, car specifique au filtre).
const filteredByAccount = ref(null)
const transactions = computed(() =>
  filterAccount.value !== null ? (filteredByAccount.value ?? []) : dataStore.state.transactions
)

/** Années distinctes présentes dans la liste courante (compte filtré ou non), pour peupler le filtre Année. Recalculé uniquement quand la liste change, pas à chaque frappe. */
const availableYears = computed(() => {
  const years = new Set()
  for (const t of transactions.value) {
    if (t.date) years.add(Number(t.date.slice(0, 4)))
  }
  return Array.from(years).sort((a, b) => b - a)
})

/**
 * Annulation de la derniere action destructive/de masse (creation, edition,
 * suppression, validation...) : un seul niveau (pas d'historique complet),
 * expose comme un bandeau "Annuler" tant que lastAction n'est pas null.
 * setLastAction remplace toujours l'action precedente (un seul "dernier" a
 * la fois) et se dissipe seule apres UNDO_TIMEOUT_MS pour ne pas rester
 * affichee indefiniment une fois qu'elle n'est plus pertinente.
 */
const UNDO_TIMEOUT_MS = 10000
const lastAction = ref(null) // { label, undo: () => Promise<void> }
const undoing = ref(false)
let lastActionTimer = null

function setLastAction(label, undo) {
  if (lastActionTimer) clearTimeout(lastActionTimer)
  lastAction.value = { label, undo }
  lastActionTimer = setTimeout(() => { lastAction.value = null }, UNDO_TIMEOUT_MS)
}

function clearLastAction() {
  if (lastActionTimer) clearTimeout(lastActionTimer)
  lastActionTimer = null
  lastAction.value = null
}

async function runLastAction() {
  if (!lastAction.value || undoing.value) return
  const { undo } = lastAction.value
  undoing.value = true
  try {
    await undo()
  } catch (e) {
    alertApiError("Erreur lors de l'annulation", e)
  } finally {
    undoing.value = false
    clearLastAction()
  }
}

onBeforeUnmount(clearLastAction)

/**
 * Actions de restauration d'un lot de transactions sur le point d'etre
 * supprimees (voir planRestore dans services/undoRestore.js pour le cas des
 * virements). Chaque action garde les ids qu'elle restaure, pour pouvoir
 * ignorer celles dont la suppression a echoue (voir deletePending).
 */
function buildRestoreActions(transactionsToDelete) {
  const { steps, orphanTransferIds } = planRestore(transactionsToDelete, dataStore.state.transactions)
  if (orphanTransferIds.length) {
    // Jumelle introuvable (cas limite, ex : deja supprimee par ailleurs) :
    // seul ce cote peut etre recree, sans appariement.
    console.warn(`Annulation : jumelle de virement introuvable pour ${orphanTransferIds.join(', ')}, restauration en transaction simple sans appariement.`)
  }
  return steps.map(step => ({
    ids: step.ids,
    run: () => (step.kind === 'transfer' ? TransactionsAPI.transfer(step.payload) : TransactionsAPI.create(step.payload))
  }))
}

async function restoreAll(actions) {
  await Promise.all(actions.map(action => action.run()))
}

const showModal = ref(false)
const form = ref(emptyForm())

// --- Édition inline ---
// L'etat du formulaire d'edition (editForm) vit desormais localement dans
// TransactionRow.vue : seul l'id de la ligne en cours d'edition reste ici,
// pour garantir qu'une seule ligne est editee a la fois. Voir le commentaire
// en tete de TransactionRow.vue pour le detail de l'optimisation.
const editingId = ref(null)

/**
 * Sélection multiple (checkboxes) pour l'édition en masse ci-dessous. Un
 * `Set` reactif (plutot qu'un tableau) : `selectedIds.has(id)` passe en O(1)
 * par transaction affichee, sans scan lineaire a chaque rendu, meme sur un
 * grand historique. Persiste volontairement a travers les changements de
 * page/filtre (l'utilisateur peut ainsi selectionner sur plusieurs pages) ;
 * seul un clic explicite sur "Désélectionner tout" ou une édition en masse
 * reussie la vide.
 */
const selectedIds = ref(new Set())

/**
 * Derniere transaction cochee (voir toggleSelect) : utilisee pour recentrer
 * la page dessus quand la recherche se ferme (voir le watcher de
 * searchQuery plus bas et goToSelectedTransactionPage), pour qu'on retrouve
 * une transaction reperee pendant une recherche avec son contexte (les
 * transactions voisines) une fois la recherche fermee.
 */
const lastSelectedId = ref(null)

function toggleSelect(transaction) {
  if (selectedIds.value.has(transaction.id)) {
    selectedIds.value.delete(transaction.id)
    if (lastSelectedId.value === transaction.id) lastSelectedId.value = null
  } else {
    selectedIds.value.add(transaction.id)
    lastSelectedId.value = transaction.id
  }
}

function clearSelection() {
  selectedIds.value.clear()
  lastSelectedId.value = null
}

/**
 * Échap vide la sélection, sauf si Échap sert déjà ailleurs : saisie en
 * cours, fenêtre ouverte, ligne en édition (Échap annule alors l'édition).
 */
function onSelectionEscape(event) {
  if (event.key !== 'Escape' || !selectedIds.value.size || editingId.value !== null) return
  const target = event.target
  const typing = target instanceof HTMLElement && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) && target.type !== 'checkbox'
  if (typing || document.querySelector('.modal-overlay, .feedback-overlay, .palette-overlay')) return
  clearSelection()
}

onMounted(() => window.addEventListener('keydown', onSelectionEscape))
onBeforeUnmount(() => window.removeEventListener('keydown', onSelectionEscape))

const allPagedSelected = computed(() =>
  pagedTransactions.value.length > 0 && pagedTransactions.value.every(t => selectedIds.value.has(t.id))
)

function toggleSelectAllPaged() {
  if (allPagedSelected.value) {
    for (const t of pagedTransactions.value) selectedIds.value.delete(t.id)
  } else {
    for (const t of pagedTransactions.value) selectedIds.value.add(t.id)
  }
}

/**
 * Montant par id sur la liste complete (tous comptes, voir dataStore) plutot
 * que sur `transactions` (potentiellement filtree par compte) : la selection
 * persiste a travers les filtres (voir selectedIds plus haut), le total doit
 * donc rester juste meme si une transaction selectionnee n'appartient plus
 * au compte actuellement filtre.
 */
const amountById = computed(() => {
  const map = new Map()
  for (const t of dataStore.state.transactions) map.set(t.id, Number(t.amount || 0))
  return map
})

/**
 * Transactions actuellement selectionnees, lues dans la liste globale (tous
 * comptes, voir dataStore) et non dans `transactions` (potentiellement
 * filtree par compte) : la selection persiste a travers les filtres, et
 * l'action de masse (backend) porte sur TOUS les ids selectionnes — un
 * instantane pris sur la seule liste filtree oubliait les autres comptes,
 * qui n'etaient alors jamais restaures par "Annuler".
 */
function selectedTransactionsSnapshot() {
  const byId = new Map(dataStore.state.transactions.map(t => [t.id, t]))
  for (const t of transactions.value) if (!byId.has(t.id)) byId.set(t.id, t)
  return Array.from(selectedIds.value, id => byId.get(id)).filter(Boolean)
}

/** Somme des transactions selectionnees, affichee dans la barre de selection multiple. */
const selectedTotal = computed(() => {
  const amounts = amountById.value
  let sum = 0
  for (const id of selectedIds.value) sum += amounts.get(id) || 0
  return sum
})

/**
 * Édition en masse : plus de case à cocher par champ (voir modal
 * correspondant) — modifier un champ suffit a le marquer comme "a appliquer"
 * (voir emptyBulkForm/bulkTouchedFields dans services/bulkEdit.js).
 */
const showBulkEditModal = ref(false)
const bulkForm = ref(emptyBulkForm())
const bulkSaving = ref(false)

const bulkTouchedFieldCount = computed(() => countTouchedFields(bulkTouchedFields(bulkForm.value)))

const bulkHasAnyFieldEnabled = computed(() => bulkTouchedFieldCount.value > 0)

function openBulkEdit() {
  bulkForm.value = emptyBulkForm()
  showBulkEditModal.value = true
}

/** Reinitialise tous les champs (revient a l'etat "aucune modification"), sans fermer la modale. */
function resetBulkForm() {
  bulkForm.value = emptyBulkForm()
}

function onBulkCategoryChange(value) {
  bulkForm.value.categoryId = value
  bulkForm.value.categoryTouched = true
}

function onBulkPayeeChange(value) {
  bulkForm.value.payee = value
  bulkForm.value.payeeTouched = true
}

async function saveBulkEdit() {
  if (!bulkHasAnyFieldEnabled.value || !selectedIds.value.size) return

  // Champs effectivement touches par cette modification en masse : reutilises
  // pour ne restaurer que ceux-la lors de l'annulation (voir plus bas), sans
  // quoi passer par TransactionsAPI.update() (qui reenverrait TOUS les champs,
  // y compris compte/date/montant) est systematiquement refuse par le backend
  // des qu'une des transactions modifiees est un cote de virement (voir
  // ServiceTransaction#update, qui interdit toute edition directe d'un
  // virement) alors que bulkUpdate, lui, autorise deja categorie/tiers/
  // marqueurs/statut sur un virement (voir ServiceTransaction#applyBulkFields).
  const touchedFields = bulkTouchedFields(bulkForm.value)
  const payload = bulkUpdatePayload(Array.from(selectedIds.value), touchedFields, bulkForm.value)

  const previousSnapshots = selectedTransactionsSnapshot()
    .map(t => ({ id: t.id, before: transactionPayloadFrom(t) }))

  bulkSaving.value = true
  try {
    await TransactionsAPI.bulkUpdate(payload)
    showBulkEditModal.value = false
    clearSelection()
    await load()
    setLastAction(`Modification de ${previousSnapshots.length} transaction(s)`, async () => {
      await Promise.all(previousSnapshots.map(({ id, before }) =>
        TransactionsAPI.bulkUpdate(bulkUpdatePayload([id], touchedFields, before))
      ))
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la modification en masse', e)
  } finally {
    bulkSaving.value = false
  }
}

function emptyForm() {
  return {
    accountId: null,
    date: toLocalIsoDate(),
    payee: '',
    amount: 0,
    categoryId: null,
    paymentMode: 'NONE',
    memo: '',
    tags: '',
    transferTransactionId: null,
    periodicExpense: false,
    exceptional: false,
    validated: true
  }
}

/**
 * Filtre les transactions par le texte de recherche : cherche à la fois
 * dans le tiers (payee) et dans le montant (dépense ou revenu), peu importe
 * le format saisi (avec virgule, point, signe, ou juste des chiffres).
 *
 * Cas particulier : si le texte est une expression de comparaison de montant
 * ("> 100", "< 50", "= 42.50"), la recherche bascule sur amountSearchResults,
 * deja filtre cote backend (voir watcher plus bas) plutot que de filtrer en
 * Javascript la liste complete des transactions.
 */
const pendingCount = computed(() =>
  transactions.value.filter(t => !t.validated).length
)

const uncategorizedCount = computed(() =>
  transactions.value.filter(t => !t.categoryId).length
)

// Categories a plat { id, label, type, selectable } (voir flattenCategories),
// partagees par les autocompletes et l'index de recherche ci-dessous.
const flatCategories = computed(() => flattenCategories(categories.value))

/**
 * Libelle categorie ("Parent : Enfant", meme format que CategoryAutocomplete
 * — voir flattenCategories) par id, pour que la recherche retrouve aussi une
 * transaction par sa categorie (ex: "Traitements et salaires : Salaire"),
 * pas seulement par tiers/montant.
 */
const categoryLabelById = computed(() => {
  const map = new Map()
  for (const c of flatCategories.value) map.set(c.id, c.label)
  return map
})

/**
 * Nom "brut" de la categorie (juste "Salaire", sans le prefixe parent ni
 * l'icone) par id — distinct de categoryLabelById ci-dessus : une recherche
 * exacte ("Salaire" entre guillemets) doit matcher ce nom precis, alors que
 * le libelle complet ("Traitements et salaires : Salaire") ne serait jamais
 * strictement egal a ce que l'utilisateur tape.
 */
const categoryNameById = computed(() => {
  const map = new Map()
  for (const c of categories.value) map.set(c.id, c.name)
  return map
})

/**
 * Champs de recherche normalises (tiers, categorie, montant formate) par
 * transaction, precalcules en Map une seule fois quand la liste de
 * transactions change (chargement, changement de filtre compte...), et non
 * recalcules a chaque frappe dans la recherche : formatAmount +
 * normalizeSearch sur plusieurs milliers de transactions a chaque caractere
 * tape est ce qui avait deja rendu la recherche lente une premiere fois sur
 * ce projet (voir CLAUDE.md). filteredTransactions ci-dessous ne fait plus
 * que des comparaisons de chaines deja normalisees.
 */
const searchIndexById = computed(() => {
  const index = new Map()
  const labels = categoryLabelById.value
  const names = categoryNameById.value
  for (const t of transactions.value) {
    index.set(t.id, {
      payeeNormalized: normalizeSearch(t.payee),
      categoryNormalized: normalizeSearch(labels.get(t.categoryId) || ''),
      categoryNameNormalized: normalizeSearch(names.get(t.categoryId) || ''),
      rawAmount: String(t.amount ?? '').toLowerCase(),
      absAmount: String(Math.abs(t.amount ?? 0)),
      // Montant formate ("0,60 €") avec la virgule decimale remplacee par un
      // point, pour que la recherche matche qu'on tape "0,60" ou "0.60" (voir
      // transactionMatchesTerm, qui convertit le terme tape de la meme facon).
      // Sans ca, un montant a 2 decimales tape avec un point (ex : "0.60")
      // ne matchait ni ce champ (virgule attendue) ni rawAmount/absAmount
      // (qui n'ont pas le zero final, ex : "0.6" pour 0,60€).
      amountFormattedNormalized: normalizeSearch(formatAmount(t.amount)).replace(',', '.')
    })
  }
  return index
})

/**
 * Statut + mois/année : etape memoisee separee de filteredTransactions
 * ci-dessous, pour ne pas repasser sur toute la liste a chaque frappe dans
 * la recherche (elle ne depend que du statut/mois/annee/compte, jamais de
 * searchQuery — meme principe que searchIndexById plus haut).
 */
const statusAndDateFiltered = computed(() => {
  const baseTransactions = amountQuery.value
    ? (amountSearchResults.value ?? [])
    : transactions.value

  let filtered = pendingOnly.value
    ? baseTransactions.filter(t => !t.validated)
    : baseTransactions

  if (uncategorizedOnly.value) {
    filtered = filtered.filter(t => !t.categoryId)
  }

  if (filterMonth.value == null && filterYear.value == null) return filtered

  return filtered.filter(t => {
    if (!t.date) return false
    if (filterYear.value != null && Number(t.date.slice(0, 4)) !== filterYear.value) return false
    if (filterMonth.value != null && Number(t.date.slice(5, 7)) !== filterMonth.value) return false
    return true
  })
})

/**
 * Plusieurs critères séparés par ";" sont tous exigés à la fois (ET), ex :
 * "Bouygues;29,99" pour retrouver la transaction Bouygues de ce montant
 * précis parmi plusieurs. Un critère pris seul est un "contient" (OR entre
 * tiers/catégorie/montant) sauf s'il est entre guillemets ("CAF"), auquel
 * cas seule une correspondance exacte est retenue — voir parseSearchTerms
 * et matchesSearchTerm dans services/search.js.
 */
const filteredTransactions = computed(() => {
  if (amountQuery.value) return statusAndDateFiltered.value

  const terms = parseSearchTerms(searchQuery.value)
  if (!terms.length) return statusAndDateFiltered.value

  const index = searchIndexById.value

  return statusAndDateFiltered.value.filter(t => {
    const idx = index.get(t.id)
    return idx && terms.every(criterion => matchesSearchTerm(idx, criterion))
  })
})

/**
 * Chargement progressif au scroll cote client (voir services/progressiveList.js) :
 * avec un historique de plusieurs milliers de transactions, rendre TOUTES
 * les lignes filtrees a chaque frappe (un composant TransactionRow par
 * ligne) rendait la recherche inutilisable. Lots de 50 lignes (~2-3 ecrans) :
 * 100 lignes rendaient encore la frappe perceptiblement lente (50-100 ms
 * mesures sur ~4 000 transactions).
 */
const {
  visibleItems: pagedTransactions,
  hasMore: hasMoreToShow,
  sentinel: loadMoreSentinel,
  reset: resetVisibleTransactions,
  revealIndex: revealTransactionIndex
} = useProgressiveList(filteredTransactions, { pageSize: 50 })

/**
 * Intertitres de mois : totaux calculés sur TOUTE la liste filtrée (pas
 * seulement le lot affiché), une fois par changement de filtre — jamais
 * recalculés ligne par ligne au rendu.
 */
const monthSummaries = computed(() => summarizeByMonth(filteredTransactions.value))

function monthStartsAt(index) {
  return startsNewMonth(pagedTransactions.value, index)
}

function monthSummary(isoDate) {
  return monthSummaries.value.get(monthKey(isoDate)) || { count: 0, total: 0 }
}

const monthHeading = formatMonthHeading

/**
 * Recherche par montant avec operateur (">", "<", "=") : filtree directement
 * cote backend (voir TransactionController/TransactionService) pour eviter
 * de charger inutilement toutes les transactions cote frontend. Un leger
 * debounce evite un appel reseau a chaque frappe pendant la saisie du nombre.
 */
const amountQuery = computed(() => parseAmountQuery(searchQuery.value))
const amountSearchResults = ref(null)
const amountSearchLoading = ref(false)
let amountSearchTimer = null
// Jeton de la derniere recherche lancee : une reponse plus ancienne arrivee
// apres coup (requete lente) ne doit jamais ecraser le resultat courant.
let amountSearchToken = 0

// En quittant la page : annule une recherche pas encore partie et ignore
// celle deja en vol (pas de notification d'erreur affichee sur une autre page).
onBeforeUnmount(() => {
  clearTimeout(amountSearchTimer)
  amountSearchToken++
})

/**
 * Etend le lot rendu pour que la transaction cochee `id` (voir
 * lastSelectedId) fasse partie du lot actuellement rendu, puis fait defiler
 * la ligne correspondante jusqu'au centre de l'ecran une fois le DOM a jour
 * — pour la retrouver avec les transactions voisines visibles autour d'elle.
 * Si la transaction n'est plus dans la liste filtree courante (ex : filtre
 * "À traiter" actif et transaction validee), retombe simplement sur le
 * premier lot.
 */
function scrollToSelectedTransaction(id) {
  const index = filteredTransactions.value.findIndex(t => t.id === id)
  if (!revealTransactionIndex(index)) {
    resetVisibleTransactions()
    return
  }
  nextTick(() => {
    document.querySelector(`tr[data-transaction-id="${id}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

// Revenir au premier lot des qu'un filtre change le resultat, pour ne jamais
// rester bloque sur un decompte devenu incoherent avec la nouvelle liste —
// sauf quand la recherche vient tout juste de se fermer (texte redevenu
// vide) alors qu'une transaction est cochee : on se repositionne alors sur
// elle plutot que de revenir au debut (voir scrollToSelectedTransaction
// ci-dessus).
watch(
  [searchQuery, filterAccount, filterMonth, filterYear, pendingOnly, uncategorizedOnly, amountSearchResults],
  ([newSearch], [oldSearch]) => {
    const searchJustClosed = !!(oldSearch && oldSearch.trim()) && !(newSearch && newSearch.trim())
    if (searchJustClosed && lastSelectedId.value) {
      scrollToSelectedTransaction(lastSelectedId.value)
      return
    }
    resetVisibleTransactions()
  }
)

watch([amountQuery, filterAccount], ([query, accountId]) => {
  clearTimeout(amountSearchTimer)
  const token = ++amountSearchToken
  if (!query) {
    amountSearchResults.value = null
    amountSearchLoading.value = false
    return
  }
  amountSearchLoading.value = true
  amountSearchTimer = setTimeout(async () => {
    try {
      const results = await TransactionsAPI.list({
        accountId: accountId ?? undefined,
        amountOperator: query.operator,
        amountValue: query.value
      })
      if (token === amountSearchToken) amountSearchResults.value = results
    } catch (e) {
      if (token !== amountSearchToken) return
      amountSearchResults.value = []
      alertApiError('Erreur lors de la recherche par montant', e)
    } finally {
      if (token === amountSearchToken) amountSearchLoading.value = false
    }
  }, 250)
})

// Meme logique que TransactionRow.vue : ne proposer dans CategoryAutocomplete
// que des categories du type correspondant au signe du montant saisi.
const formAmountType = computed(() => {
  const amount = form.value.amount
  if (amount == null || amount === 0 || amount === '') return null
  return amount < 0 ? 'EXPENSE' : 'INCOME'
})

// Index Map (id -> objet) pour accounts/categories, precalcules une seule
// fois quand ces listes changent (rarement), et passes en prop a chaque
// TransactionRow : evite un scan lineaire de accounts/categories pour
// chaque ligne affichee (categoryLabel/accountName vivent desormais dans
// TransactionRow.vue et consomment ces Map en O(1)).
const accountsById = computed(() => new Map(accounts.value.map(a => [a.id, a])))
const categoriesById = computed(() => new Map(categories.value.map(c => [c.id, c])))

/**
 * Une transaction est "future" si sa date est strictement postérieure à
 * aujourd'hui : elle est prévue mais n'a pas encore réellement eu lieu
 * (comparaison de chaînes ISO "AAAA-MM-JJ", fiable sans passer par Date()).
 */
const todayIso = toLocalIsoDate()

async function load() {
  if (filterAccount.value !== null) {
    filteredByAccount.value = await TransactionsAPI.list({ accountId: filterAccount.value })
    // Solde(s) affiché(s) dans la sidebar et sur la page Comptes : à
    // rafraîchir même quand la liste filtrée par compte ne passe pas par le
    // cache partagé ci-dessus.
    await dataStore.refreshAccounts()
  } else {
    // Revalide le cache partage en arriere-plan (l'affichage courant du
    // cache reste visible pendant ce temps, aucun blocage / clignotement).
    // Rafraîchit aussi les soldes des comptes (voir dataStore.js).
    await dataStore.refreshTransactions()
  }
}

/**
 * Categorisation automatique par tiers : un tiers connu (page "Tiers") peut
 * avoir une seule categorie par defaut (Payee#category cote backend). Quand
 * l'utilisateur saisit/selectionne un tiers reconnu dans le formulaire, ce
 * champ est rempli avec la categorie par defaut du tiers — y COMPRIS si une
 * categorie etait deja renseignee (ex : deja pre-remplie a tort par un tiers
 * homonyme avant la desambiguisation par montant, voir
 * services/payee.js#findPayeeByName) : c'est le fait de choisir/changer explicitement le tiers qui
 * declenche la mise a jour, jamais un rafraichissement en arriere-plan.
 * Ce pre-remplissage est desormais la SEULE source de la categorie par
 * defaut du tiers : le backend (TransactionService#applyPayload) applique la
 * categorie du payload telle quelle, y compris null (transaction rendue non
 * categorisee) — il ne la reapplique plus depuis le tiers, sans quoi effacer
 * volontairement la categorie d'une transaction deja categorisee (bouton ✕ de
 * CategoryAutocomplete) etait silencieusement annule des que son tiers avait
 * une categorie par defaut.
 */
// Indexe les tiers par nom normalise, memoise (recalcule uniquement quand
// la liste des tiers change, pas a chaque frappe) : evite un scan lineaire
// de tous les tiers a chaque caractere tape dans le champ Tiers. Un nom peut
// correspondre a PLUSIEURS tiers (voir Tiers#amount : "Bouygues" a 10€ et a
// 20€ sont deux tiers distincts avec le meme nom affiche), d'ou le tableau
// plutot qu'un seul tiers par nom — voir services/payee.js#findPayeeByName,
// qui les departage par montant.
const payeeByNormalizedName = computed(() => {
  const map = new Map()
  for (const p of payees.value) {
    if (!p.name) continue
    const key = p.name.trim().toLowerCase()
    const list = map.get(key)
    if (list) list.push(p)
    else map.set(key, [p])
  }
  return map
})

// L'edition inline applique la meme logique localement via TransactionRow.vue
// (elle recoit payeeByNormalizedName en prop, editForm n'etant pas accessible
// depuis ce composant).
function applyDefaultCategoryFromPayee(payeeName) {
  const payee = findPayeeByName(payeeByNormalizedName.value, payeeName, form.value.amount)
  if (payee?.categoryId) form.value.categoryId = payee.categoryId
}

watch(() => form.value.payee, applyDefaultCategoryFromPayee)

function openCreate() {
  form.value = emptyForm()
  if (entryAccounts.value.length) form.value.accountId = entryAccounts.value[0].id
  showModal.value = true
}

async function save() {
  if (!form.value.accountId) {
    notify('Veuillez sélectionner un compte.', 'warning')
    return
  }

  const payload = transactionPayloadFrom(form.value)

  try {
    const created = await TransactionsAPI.create(payload)
    notify('Transaction créée.', 'success')
    showModal.value = false
    await load()
    setLastAction('Transaction créée', async () => {
      await TransactionsAPI.remove(created.id)
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la création de la transaction', e)
  }
}

/* --- Édition inline : clic sur une ligne --- */

function startEdit(t) {
  // Meme garde-fou que openSplit : un virement ne peut pas etre modifie
  // directement (voir ServiceTransaction#update), qui rejette la requete
  // apres coup si on laisse l'edition s'ouvrir.
  if (t.paymentMode === 'TRANSFER' || t.transferTransactionId) {
    notify('Un virement ne peut pas être modifié directement : supprimez-le et recréez-le si besoin.', 'warning')
    return
  }
  editingId.value = t.id
}

function cancelEdit() {
  editingId.value = null
}

async function confirmEdit(payload) {
  if (!editingId.value) return
  const id = editingId.value
  const before = transactions.value.find(t => t.id === id)
  // editingId n'est efface qu'apres le succes de l'appel : sinon, un echec
  // (validation, reseau) fermait silencieusement l'edition sans que rien ne
  // soit enregistre ni explique a l'utilisateur, qui devait tout retaper
  // sans comprendre pourquoi. En laissant l'edition ouverte, TransactionRow
  // (qui garde editForm en etat local tant que isEditing reste vrai) ne
  // perd pas la saisie en cours.
  try {
    await TransactionsAPI.update(id, payload)
  } catch (e) {
    alertApiError('Erreur lors de la modification', e)
    return
  }
  editingId.value = null
  await load()
  if (before) {
    const previousPayload = transactionPayloadFrom(before)
    setLastAction('Transaction modifiée', async () => {
      await TransactionsAPI.update(id, previousPayload)
      await load()
    })
  }
}

async function remove(transaction) {
  if (!(await askConfirm('Supprimer cette transaction ?'))) return
  const restoreActions = buildRestoreActions([transaction])
  try {
    await TransactionsAPI.remove(transaction.id)
    await load()
    notify('Transaction supprimée.', 'success')
    setLastAction('Transaction supprimée', async () => {
      await restoreAll(restoreActions)
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la suppression de la transaction', e)
  }
}

/* --- Division d'une transaction en deux --- */

const showSplitModal = ref(false)
const splitTransaction = ref(null)
const splitForm = ref({ firstAmount: 0, firstCategoryId: null, firstMemo: '', secondCategoryId: null, secondMemo: '' })
const splitSaving = ref(false)
const splitError = ref('')

/** Montant de la 2e partie, calculé côté client pour l'affichage seul (la valeur qui compte est recalculée côté backend, voir ServiceTransaction#splitTransaction). */
const secondAmount = computed(() => {
  if (!splitTransaction.value) return 0
  return Number(splitTransaction.value.amount) - Number(splitForm.value.firstAmount || 0)
})

// Même logique que formAmountType/editAmountType : ne proposer que des
// catégories du bon sens (dépense/revenu) selon le signe du montant d'origine.
const splitAmountType = computed(() => {
  const amount = splitTransaction.value?.amount
  if (amount == null || amount === 0) return null
  return amount < 0 ? 'EXPENSE' : 'INCOME'
})

function openSplit(transaction) {
  if (transaction.paymentMode === 'TRANSFER' || transaction.transferTransactionId) {
    notify('Un virement ne peut pas être divisé.', 'warning')
    return
  }
  splitTransaction.value = transaction
  // Moitié par défaut, arrondie au centime, modifiable avant validation.
  const half = Math.round((Number(transaction.amount) / 2) * 100) / 100
  splitForm.value = {
    firstAmount: half,
    firstCategoryId: transaction.categoryId ?? null,
    firstMemo: transaction.memo || '',
    secondCategoryId: transaction.categoryId ?? null,
    secondMemo: ''
  }
  splitError.value = ''
  showSplitModal.value = true
}

function closeSplitModal() {
  showSplitModal.value = false
  splitTransaction.value = null
}

async function saveSplit() {
  splitError.value = ''
  const total = Number(splitTransaction.value.amount)
  const first = Number(splitForm.value.firstAmount)
  const sameSign = first !== 0 && (total >= 0) === (first >= 0)
  if (!first || !sameSign || Math.abs(first) >= Math.abs(total)) {
    splitError.value = 'Le montant de la 1ère partie doit être non nul, de même signe que le montant total, et inférieur à lui en valeur absolue.'
    return
  }

  splitSaving.value = true
  try {
    await TransactionsAPI.split(splitTransaction.value.id, {
      firstAmount: first,
      firstCategoryId: splitForm.value.firstCategoryId,
      firstMemo: splitForm.value.firstMemo,
      secondCategoryId: splitForm.value.secondCategoryId,
      secondMemo: splitForm.value.secondMemo
    })
    closeSplitModal()
    await load()
  } catch (e) {
    splitError.value = apiErrorMessage(e)
  } finally {
    splitSaving.value = false
  }
}

/**
 * Bascule d'un drapeau booleen d'une transaction (une bascule etant sa propre
 * inverse, l'annulation rejoue simplement le meme appel).
 */
async function toggleFlag(apiCall, transaction, label) {
  try {
    await apiCall(transaction.id)
    await load()
    setLastAction(label, async () => {
      await apiCall(transaction.id)
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la modification de la transaction', e)
  }
}

/** Bascule directement le statut "dépense périodique" sans passer par l'édition complète. */
function togglePeriodic(transaction) {
  return toggleFlag(TransactionsAPI.togglePeriodicExpense, transaction, 'Statut "dépense périodique" modifié')
}

/** Bascule directement le statut "exceptionnelle" sans passer par l'édition complète. */
function toggleExceptional(transaction) {
  return toggleFlag(TransactionsAPI.toggleExceptional, transaction, 'Statut "exceptionnelle" modifié')
}

/* --- Validation / rapprochement (façon "reconcile" YNAB) --- */

const validating = ref(false)

/** Bascule le statut validée/en attente d'une seule transaction. */
function toggleValidated(transaction) {
  return toggleFlag(TransactionsAPI.toggleValidated, transaction, 'Statut "validée" modifié')
}

/**
 * Valide en une fois toutes les transactions en attente : à utiliser une fois
 * que le solde du compte dans l'app correspond au solde réel de la banque,
 * comme le "reconcile" de YNAB. Permet ensuite de repérer d'un coup d'œil
 * les transactions ajoutées depuis la dernière validation (pastille orange).
 */
async function validateAll() {
  if (pendingCount.value === 0) return
  if (!(await askConfirm(`Valider les ${pendingCount.value} transaction(s) en attente ?`))) return
  validating.value = true
  // Valide TOUTES les transactions en attente cote backend (pas seulement
  // celles du compte/filtre courant, voir ServiceTransaction#validateAllPending)
  // : le snapshot pour l'annulation doit donc venir de la liste globale
  // (dataStore.state.transactions), pas de `transactions` qui peut etre
  // restreinte par filterAccount.
  const pendingIds = dataStore.state.transactions.filter(t => !t.validated).map(t => t.id)
  try {
    await TransactionsAPI.validateAll()
    await load()
    notify(`${pendingIds.length} transaction(s) validée(s).`, 'success')
    setLastAction(`Validation de ${pendingIds.length} transaction(s)`, async () => {
      await Promise.all(pendingIds.map(id => TransactionsAPI.toggleValidated(id)))
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la validation des transactions', e)
  } finally {
    validating.value = false
  }
}

const deletingPending = ref(false)

/** Supprime d'un coup toutes les transactions en attente de validation, sans passer par la selection manuelle. */
async function deletePending() {
  if (pendingCount.value === 0) return
  if (!(await askConfirm(`Supprimer définitivement les ${pendingCount.value} transaction(s) non validée(s) ?`))) return
  deletingPending.value = true
  // Supprime TOUTES les transactions en attente cote backend (voir
  // ServiceTransaction#deleteAllPending, pas filtre par compte) : meme
  // remarque que validateAll ci-dessus pour le snapshot d'annulation.
  const pendingTransactions = dataStore.state.transactions.filter(t => !t.validated)
  const candidateActions = buildRestoreActions(pendingTransactions)
  try {
    await TransactionsAPI.removePending()
    await load()
    notify('Transactions en attente supprimées.', 'success')
    // Un cote de virement en attente dont la jumelle est deja validee est
    // ignore par le backend (voir ServiceTransaction#deleteAllPending) : il
    // est donc toujours present apres le rechargement, et ne doit pas etre
    // "restaure" (il n'a jamais ete supprime, ca creerait un doublon).
    const stillPresentIds = new Set(dataStore.state.transactions.map(t => t.id))
    const restoreActions = candidateActions.filter(a => !a.ids.some(id => stillPresentIds.has(id)))
    const actuallyDeletedCount = pendingTransactions.filter(t => !stillPresentIds.has(t.id)).length
    setLastAction(`Suppression de ${actuallyDeletedCount} transaction(s)`, async () => {
      await restoreAll(restoreActions)
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la suppression des transactions en attente', e)
  } finally {
    deletingPending.value = false
  }
}

const deletingSelection = ref(false)

/** Supprime d'un coup toutes les transactions actuellement cochees (voir selectedIds). */
async function deleteSelected() {
  if (!selectedIds.value.size) return
  if (!(await askConfirm(`Supprimer définitivement les ${selectedIds.value.size} transaction(s) sélectionnée(s) ?`))) return
  deletingSelection.value = true
  const ids = Array.from(selectedIds.value)
  const selectedTransactions = selectedTransactionsSnapshot()
  const restoreActions = buildRestoreActions(selectedTransactions)
  try {
    await TransactionsAPI.removeBulk(ids)
    notify(`${selectedTransactions.length} transaction(s) supprimée(s).`, 'success')
    clearSelection()
    await load()
    setLastAction(`Suppression de ${selectedTransactions.length} transaction(s)`, async () => {
      await restoreAll(restoreActions)
      await load()
    })
  } catch (e) {
    alertApiError('Erreur lors de la suppression des transactions sélectionnées', e)
  } finally {
    deletingSelection.value = false
  }
}

onMounted(async () => {
  // Si le prechargement lance au demarrage de l'app est deja termine (cas
  // courant), ceci est instantane et n'attend rien. Sinon (premier
  // chargement de l'app directement sur /transactions), on attend juste
  // cette premiere fois.
  try {
    await dataStore.ensureLoaded()
  } catch (e) {
    alertApiError('Erreur lors du chargement des données', e)
    return
  }
  // Revalidation silencieuse en arriere-plan a chaque visite de la page,
  // au cas ou des comptes/categories/tiers/transactions ont change depuis
  // le prechargement (cree ailleurs, autre onglet...).
  dataStore.refreshAll()
  if (filterAccount.value !== null) await load()

  applyRouteQuery(route.query)
})

/**
 * Paramètres d'URL reconnus (liens internes, palette de commandes Ctrl+K) :
 * - edit=<id>   : ouvre la transaction en édition (« À traiter » > « Modifier ») ;
 * - search=<t>  : pré-remplit la recherche ;
 * - pending=1 / uncategorized=1 : active le filtre « À traiter » / « Sans catégorie » ;
 * - new=1       : ouvre la fenêtre de création.
 * Réappliqués à chaque changement de query : la page n'est pas remontée
 * quand on y navigue alors qu'elle est déjà affichée.
 */
function applyRouteQuery(query) {
  const editId = query.edit ? Number(query.edit) : null
  if (editId) {
    const target = transactions.value.find(t => t.id === editId)
    if (target) startEdit(target)
  }
  if (typeof query.search === 'string') searchQuery.value = query.search
  if (query.uncategorized) uncategorizedOnly.value = true
  if (query.pending) pendingOnly.value = true
  if (query.new && entryAccounts.value.length) {
    openCreate()
    // Retire "new" de l'URL : un retour arrière ou un nouveau raccourci
    // (touche N, palette) doit pouvoir rouvrir la création.
    const rest = { ...query }
    delete rest.new
    router.replace({ query: rest })
  }
}

watch(() => route.query, (query) => {
  if (dataStore.state.loaded.transactions) applyRouteQuery(query)
})
</script>

<style scoped>
/*
 * Bandeau d'annulation de la derniere action (voir setLastAction/runLastAction) :
 * position fixe en bas de l'ecran pour rester visible quel que soit le
 * scroll de la page/du tableau, au-dessus des modales (z-index superieur a
 * .modal-overlay, voir style.css) au cas ou une action serait annulee alors
 * qu'une modale est deja ouverte.
 */
.undo-bar {
  position: fixed;
  left: 50%;
  bottom: 24px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  background: var(--text-primary);
  color: var(--ink-900);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  z-index: 60;
}
.undo-bar .btn.secondary {
  background: transparent;
  border: 1px solid color-mix(in srgb, var(--ink-900) 35%, transparent);
  color: var(--ink-900);
}
.undo-bar .btn.secondary:hover {
  background: color-mix(in srgb, var(--ink-900) 12%, transparent);
}

/*
 * Volontairement pas d'overflow-x ici : un position:sticky sur le thead
 * (ci-dessous) ne colle au scroll de la PAGE que si aucun ancêtre entre lui
 * et le viewport n'a d'overflow non-visible (même overflow-x seul suffit à
 * casser la stickiness, voir memoire du projet). Si le tableau est trop
 * large pour l'ecran, c'est donc toute la page qui scrolle horizontalement
 * plutot qu'un scroll confine au tableau.
 */
.table-scroll {
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--ink-900);
  box-shadow: var(--shadow-sm);
}
/* Largeurs calibrées pour tenir sans défilement horizontal sur un écran de
   1440 px (sidebar comprise) ; en dessous, c'est la page qui défile. */
.transactions-table {
  min-width: 1130px;
  width: 100%;
  table-layout: fixed;
}
/* Tiers et Catégorie sont les colonnes qu'on lit : elles prennent la place
   gagnée sur les cases à cocher (en-têtes en icônes) et sur le compte. */
.col-select { width: 36px; }
.col-date { width: 110px; }
.col-status { width: 40px; }
.col-account { width: 84px; }
.col-payee { width: 200px; }
/* La catégorie prend toute la place restante ; le mémo, peu rempli, reste étroit. */
.col-category { width: auto; }
.col-memo { width: 56px; }
/* Assez large pour « -100 000,00 € » : jamais de troncature sur un montant. */
.col-amount { width: 104px; }
.col-monthly { width: 46px; }
.col-actions { width: 72px; }

/* Intertitre de mois : reste collé sous l'en-tête du tableau (43 px) pendant
   le défilement, pour toujours savoir quel mois on est en train de lire. */
.transactions-table .month-row th {
  position: sticky;
  top: 43px;
  z-index: 5;
  padding: 7px 12px;
  background: var(--surface-sunken);
  border-top: 1px solid var(--line-strong);
  border-bottom: 1px solid var(--line);
  text-align: left;
  white-space: nowrap;
  overflow: visible;
}
.month-row-label {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 650;
  letter-spacing: -0.01em;
}
.month-row-meta {
  margin-left: 10px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}
.month-row-meta .amount { font-size: 12px; }

.transactions-table th.th-icon {
  text-align: center;
  color: var(--text-faint);
}

.transactions-table th {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.transactions-table thead th {
  position: sticky;
  top: 0;
  z-index: 6;
}

/*
 * La colonne Actions reste toujours visible même si le tableau déborde
 * horizontalement (beaucoup de colonnes) : on la fixe à droite plutôt que
 * de forcer l'utilisateur à scroller jusqu'au bout pour supprimer/valider
 * une ligne. Le style des cellules (td) correspondantes vit desormais dans
 * TransactionRow.vue, qui gere sa propre ligne. z-index plus eleve que le
 * thead sticky "simple" ci-dessus car cette cellule cumule top:0 ET right:0
 * (coin haut-droit) et doit rester au-dessus des td sticky (right:0) du
 * corps du tableau pendant le scroll.
 */
.transactions-table th:last-child {
  position: sticky;
  right: 0;
  background: var(--surface-sunken);
  z-index: 7;
  box-shadow: var(--sticky-edge-shadow);
}

/*
 * Separe visuellement les filtres (Compte, Periode) des actions en masse
 * (À traiter / Non categorisees / Tout valider) dans la barre d'outils :
 * sans ca, les 6 champs/boutons se suivaient avec le meme poids visuel et le
 * groupe "actions" se noyait parmi les filtres.
 */
.toolbar-divider {
  align-self: stretch;
  width: 1px;
  margin: 2px 2px;
  background: var(--line);
}
.toolbar-actions {
  display: flex;
  align-items: center;
  align-self: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.text-hint {
  font-size: 12px;
  color: var(--text-faint);
  margin-top: 4px;
  line-height: 1.4;
}

/* Barre d'actions sur la sélection : flottante, centrée en bas de l'écran,
   sombre dans les deux thèmes (même teinte que la carte « Solde total »). */
.bulk-bar {
  position: fixed;
  left: calc(50% + 126px); /* centrée sur la zone de contenu (sidebar de 252 px) */
  bottom: 22px;
  z-index: 55;
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: calc(100vw - 32px);
  padding: 8px 8px 8px 16px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 14px;
  background: var(--hero-bg);
  color: #FFFFFF;
  box-shadow: 0 14px 40px rgba(8, 15, 30, 0.35);
  transform: translateX(-50%);
}
.bulk-count { font-size: 13.5px; font-weight: 650; white-space: nowrap; }
.bulk-total {
  padding: 3px 9px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.08);
  font-size: 13px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.bulk-total.positive { color: var(--hero-positive); }
.bulk-total.negative { color: var(--hero-negative); }
.bulk-separator { width: 1px; align-self: stretch; margin: 2px 2px; background: rgba(255, 255, 255, 0.14); }
.bulk-bar .btn {
  min-height: 34px;
  padding: 6px 12px;
  border-color: rgba(255, 255, 255, 0.16);
  background: rgba(255, 255, 255, 0.1);
  color: #FFFFFF;
  box-shadow: none;
  white-space: nowrap;
}
.bulk-bar .btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.18); }
.bulk-bar .btn.danger { color: var(--hero-negative); }
.bulk-bar .btn.danger:hover:not(:disabled) { background: rgba(255, 155, 145, 0.16); }
.bulk-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: rgba(255, 255, 255, 0.7);
}
.bulk-close:hover { background: rgba(255, 255, 255, 0.12); color: #FFFFFF; }
.bulk-bar-enter-active, .bulk-bar-leave-active { transition: opacity 0.16s ease, transform 0.16s ease; }
.bulk-bar-enter-from, .bulk-bar-leave-to { opacity: 0; transform: translate(-50%, 10px); }
@media (prefers-reduced-motion: reduce) {
  .bulk-bar-enter-active, .bulk-bar-leave-active { transition: none; }
}
@media (max-width: 1250px) { .bulk-bar { left: calc(50% + 112px); } } /* sidebar de 224 px */
@media (max-width: 760px) { .bulk-bar { left: 50%; } }                /* sidebar devenue barre du haut */
/* Place sous la barre flottante pour ne pas masquer les dernières lignes. */
.transactions-page:has(.bulk-bar) { padding-bottom: 72px; }
/* Bandeau « Annuler » affiché au-dessus de la barre de sélection quand les deux coexistent. */
.undo-bar.above-bulk-bar { bottom: 84px; }

/* Modale un peu plus large que le gabarit standard (520px) : les lignes
   label + champ + pastille ont besoin de plus de place pour respirer. */
.bulk-edit-modal {
  width: 600px;
}

/*
 * Chaque groupe de champs est une "carte" en creux (fond ink-950, le fond de
 * page, plus sombre que le blanc de la modale) plutot qu'une simple liste
 * separee par des traits : lisibilite et coherence avec le reste de l'appli
 * (cartes du tableau de bord, etc.), voir CLAUDE.md.
 */
.bulk-section {
  margin-bottom: 14px;
  padding: 14px 16px;
  background: var(--ink-950);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
}
.bulk-section-title {
  font-size: 13px;
  font-weight: 650;
  color: var(--text-muted);
  margin-bottom: 10px;
}

/*
 * Plus de case a cocher : modifier un champ (select ou autocomplete) suffit
 * a le marquer "a appliquer" (voir bulkForm dans le script — *Touched pour
 * Categorie/Tiers, valeur !== null pour les select). bulk-field-active
 * met alors la ligne en evidence (fond blanc + liseré azur + pastille ✓),
 * pour que l'utilisateur voie d'un coup d'œil ce qui sera reellement
 * applique avant de cliquer sur "Appliquer".
 */
.bulk-field {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 10px;
  margin-bottom: 6px;
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  transition: background 0.12s, border-color 0.12s;
}
.bulk-field:last-child {
  margin-bottom: 0;
}
.bulk-field-active {
  background: var(--ink-900);
  border-color: var(--azure);
  box-shadow: var(--shadow-sm);
}
.bulk-field-label {
  flex: 0 0 150px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.bulk-field-active .bulk-field-label {
  color: var(--azure);
}
.bulk-field-control {
  flex: 1;
  min-width: 0;
}
.bulk-field-check {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--azure);
  color: var(--on-accent);
  font-size: 11px;
  font-weight: 700;
}

.bulk-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 20px;
}
.bulk-footer-actions {
  display: flex;
  gap: 10px;
}
.bulk-clear-link {
  border: none;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  padding: 4px 0;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.bulk-clear-link:hover {
  color: var(--text-primary);
}

.scroll-status {
  margin-top: 12px;
}

/* Montant de la 2e partie (modale de division) : lecture seule, calcule a
   partir du montant total et de la 1ere partie (voir secondAmount) — meme
   gabarit qu'un champ input pour rester aligne avec les autres champs du
   formulaire, mais non editable directement. */
.split-second-amount {
  display: flex;
  align-items: center;
  height: 38px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--ink-950);
  font-weight: 700;
}

.split-error {
  color: var(--coral);
  font-size: 13px;
  margin-top: 12px;
}

/* -----------------------------------------------------------------------
   HomeBank — itération 2 : écran Transactions
   Refonte de présentation uniquement : aucun changement aux filtres,
   événements, appels API, édition inline ou pagination.
   ----------------------------------------------------------------------- */
.transactions-page .page-header {
  align-items: center;
  margin-bottom: 18px;
}

.transactions-page .page-header h1 {
  letter-spacing: -0.035em;
}

.transactions-toolbar {
  align-items: flex-end;
  gap: 14px;
  padding: 16px;
  margin: 0 0 14px;
  background: var(--ink-900);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.transactions-toolbar .form-field {
  min-width: 150px;
}

.transactions-toolbar .form-field select,
.transactions-toolbar .form-field input {
  min-height: 38px;
}

.transactions-toolbar .form-field:has(#tx-search) {
  min-width: 230px;
  flex: 1 1 260px;
}

.transactions-toolbar #tx-search {
  width: 100%;
  min-width: 0 !important;
}

.transactions-toolbar .toolbar-divider {
  align-self: stretch;
  margin: 2px 3px 0;
  background: var(--line-strong);
}

.transactions-toolbar .toolbar-actions {
  gap: 7px;
}

.transactions-toolbar .toolbar-actions .btn {
  min-height: 36px;
  white-space: nowrap;
}

.transactions-toolbar .chip {
  min-height: 34px;
  background: var(--surface-sunken);
}

.transactions-toolbar .chip.active {
  background: var(--brand);
  color: var(--brand-text);
}

.transactions-page .table-scroll {
  border-color: var(--line-strong);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
}

.transactions-page .transactions-table {
  font-size: 13px;
  border-spacing: 0;
}

.transactions-page .transactions-table thead th {
  height: 43px;
  padding: 10px 8px;
  background: var(--surface-sunken);
  color: var(--text-muted);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: .055em;
  text-transform: none;
  border-bottom: 1px solid var(--line-strong);
}

.transactions-page .transactions-table tbody td {
  padding: 10px 11px;
  border-bottom: 1px solid var(--line);
  font-size: 12.5px;
  line-height: 1.45;
  vertical-align: middle;
}

.transactions-page .transactions-table tbody tr:last-child td {
  border-bottom: 0;
}

.transactions-page .transactions-table tbody tr:hover td {
  background: var(--row-hover);
}

.transactions-page .transactions-table tbody tr.row-selected td,
.transactions-page .transactions-table tbody tr.row-selected:hover td {
  background: var(--row-selected);
}

.transactions-page .transactions-table tbody tr.row-editing td {
  background: var(--row-editing);
}

.transactions-page .transactions-table td.amount {
  font-size: 12.5px;
  font-weight: 650;
  letter-spacing: -.015em;
}


.transactions-page .scroll-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
  padding: 0 4px;
}

.transactions-page .text-hint {
  color: var(--text-muted);
}

@media (max-width: 1180px) {
  .transactions-toolbar {
    align-items: stretch;
  }
  .transactions-toolbar .toolbar-divider {
    display: none;
  }
  .transactions-toolbar .toolbar-actions {
    flex: 1 1 100%;
    align-self: auto;
  }
  .transactions-toolbar .form-field:has(#tx-search) {
    flex: 1 1 240px;
  }
}

@media (max-width: 700px) {
  .transactions-page .page-header {
    align-items: flex-start;
    gap: 12px;
  }
  .transactions-toolbar {
    padding: 12px;
    gap: 10px;
  }
  .transactions-toolbar .form-field,
  .transactions-toolbar .form-field:has(#tx-search) {
    flex: 1 1 100%;
    min-width: 0;
  }
  .transactions-toolbar .toolbar-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .transactions-toolbar .toolbar-actions .btn {
    white-space: normal;
  }
  .bulk-bar {
    left: 50%;
    bottom: 12px;
    flex-wrap: wrap;
    justify-content: center;
  }
  .bulk-separator { display: none; }
}
</style>
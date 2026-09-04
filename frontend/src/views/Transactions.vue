<template>
  <div>
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

    <div class="toolbar">
      <div class="form-field">
        <label for="tx-filter-account">Compte</label>
        <select id="tx-filter-account" v-model="filterAccount" @change="load">
          <option :value="null">Tous les comptes</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
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
          class="btn"
          :class="{ secondary: !pendingOnly }"
          @click="pendingOnly = !pendingOnly"
          :title="pendingOnly ? 'Revenir à toutes les transactions' : 'N\'afficher que les transactions à traiter'"
        >
          À traiter ({{ pendingCount }})
        </button>

        <button
          class="btn"
          :class="{ secondary: !uncategorizedOnly }"
          @click="uncategorizedOnly = !uncategorizedOnly"
          :title="uncategorizedOnly ? 'Revenir à toutes les transactions' : 'N\'afficher que les transactions sans catégorie'"
        >
          Non catégorisées ({{ uncategorizedCount }})
        </button>

        <button
          class="btn"
          :class="{ secondary: pendingCount === 0 }"
          :disabled="pendingCount === 0 || validating"
          @click="validateAll"
        >
          {{ validating ? 'Validation…' : `Tout valider (${pendingCount} en attente)` }}
        </button>
      </div>

      <div class="form-field" style="margin-left: auto;">
        <label for="tx-search" title="Tiers, catégorie ou montant. Séparez plusieurs critères par point-virgule pour tous les exiger à la fois (ET), ex : Bouygues;29,99. Ou &gt; / &lt; / = un montant seul.">Recherche (tiers, catégorie, montant, ou &gt; / &lt; / = un montant)</label>
        <input id="tx-search" v-model="searchQuery" placeholder="Ex : Bouygues;29,99 ou > 100" style="min-width: 260px;" />
        <div v-if="amountQuery" class="text-hint">
          {{ amountSearchLoading ? 'Recherche…' : `${filteredTransactions.length} transaction(s) trouvée(s)` }}
        </div>
      </div>
    </div>

    <div v-if="selectedIds.size" class="bulk-bar">
      <span>{{ selectedIds.size }} transaction(s) sélectionnée(s)</span>
      <span class="bulk-total amount" :class="selectedTotal >= 0 ? 'positive' : 'negative'">
        Total : {{ formatAmount(selectedTotal) }}
      </span>
      <button class="btn" @click="openBulkEdit">Modifier en masse</button>
      <button class="btn secondary" @click="clearSelection">Désélectionner tout</button>
    </div>

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
            <th title="Statut de validation (rapprochement bancaire)">Statut</th>
            <th>Date</th>
            <th>Compte</th>
            <th>Tiers</th>
            <th>Catégorie</th>
            <th>Mémo</th>
            <th class="num">Dépense</th>
            <th class="num">Revenu</th>
            <th class="num" title="Dépense périodique récurrente">Périodique</th>
            <th class="num" title="Transaction exceptionnelle : exclue des tableaux de bord (ex : achat d'une voiture)">Except.</th>
            <th></th>
          </tr>
        </thead>

        <tbody>
          <TransactionRow
            v-for="t in pagedTransactions"
            :key="t.id"
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
      <div class="modal">
        <h2>Nouvelle transaction</h2>

        <div class="form-grid">
          <div class="form-field">
            <label for="new-tx-account">Compte</label>
            <select id="new-tx-account" v-model.number="form.accountId">
              <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
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
            <AmountInput id="new-tx-amount" v-model="form.amount" />
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
      <div class="modal bulk-edit-modal">
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
      <div class="modal">
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
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, computed, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import {
  TransactionsAPI
} from '../services/api'
import { dataStore } from '../services/dataStore'
import { normalizeSearch, parseAmountQuery } from '../services/search'
import { flattenCategories } from '../services/categories'
import PayeeAutocomplete from '../components/PayeeAutocomplete.vue'
import CategoryAutocomplete from '../components/CategoryAutocomplete.vue'
import TransactionRow from '../components/TransactionRow.vue'
import AmountInput from '../components/AmountInput.vue'
import MonthYearFilter from '../components/MonthYearFilter.vue'
import TransactionsSubNav from './TransactionsSubNav.vue'
import { formatAmount, formatDate } from '../services/format'
import { findPayeeByName } from '../services/payee'

const route = useRoute()

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

/** Somme des transactions selectionnees, affichee dans la barre de selection multiple. */
const selectedTotal = computed(() => {
  const amounts = amountById.value
  let sum = 0
  for (const id of selectedIds.value) sum += amounts.get(id) || 0
  return sum
})

/**
 * État du formulaire d'édition en masse : plus de case à cocher par champ
 * (voir modal correspondant) — modifier un champ suffit a le marquer comme
 * "a appliquer". Categorie/Tiers utilisent un drapeau *Touched explicite
 * (mis a true par onBulkCategoryChange/onBulkPayeeChange ci-dessous), car
 * leur valeur "non renseignee" (null / chaine vide) est aussi une valeur
 * metier legitime ("aucune categorie", tiers vide) qu'on doit pouvoir
 * distinguer de "champ pas touche". Les select simples (mode de paiement,
 * marqueurs, statut) n'ont pas ce probleme : ils demarrent a `null`, une
 * valeur qui n'existe dans aucune de leurs <option> reelles, donc "valeur
 * !== null" suffit a la fois a savoir si le champ est actif ET a construire
 * le payload, sans etat separe a synchroniser.
 */
function emptyBulkForm() {
  return {
    categoryTouched: false,
    categoryId: null,
    payeeTouched: false,
    payee: '',
    paymentMode: null,
    periodicExpense: null,
    exceptional: null,
    validated: null
  }
}

const showBulkEditModal = ref(false)
const bulkForm = ref(emptyBulkForm())
const bulkSaving = ref(false)

const bulkTouchedFieldCount = computed(() => [
  bulkForm.value.categoryTouched,
  bulkForm.value.payeeTouched,
  bulkForm.value.paymentMode !== null,
  bulkForm.value.periodicExpense !== null,
  bulkForm.value.exceptional !== null,
  bulkForm.value.validated !== null
].filter(Boolean).length)

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

  const payload = { ids: Array.from(selectedIds.value) }
  if (bulkForm.value.categoryTouched) {
    payload.categoryIdProvided = true
    payload.categoryId = bulkForm.value.categoryId
  }
  if (bulkForm.value.payeeTouched) {
    payload.payeeProvided = true
    payload.payee = bulkForm.value.payee
  }
  if (bulkForm.value.paymentMode !== null) {
    payload.paymentModeProvided = true
    payload.paymentMode = bulkForm.value.paymentMode
  }
  if (bulkForm.value.periodicExpense !== null) {
    payload.periodicExpenseProvided = true
    payload.periodicExpense = bulkForm.value.periodicExpense
  }
  if (bulkForm.value.exceptional !== null) {
    payload.exceptionalProvided = true
    payload.exceptional = bulkForm.value.exceptional
  }
  if (bulkForm.value.validated !== null) {
    payload.validatedProvided = true
    payload.validated = bulkForm.value.validated
  }

  bulkSaving.value = true
  try {
    await TransactionsAPI.bulkUpdate(payload)
    showBulkEditModal.value = false
    clearSelection()
    await load()
  } finally {
    bulkSaving.value = false
  }
}

function emptyForm() {
  return {
    accountId: null,
    date: new Date().toISOString().slice(0, 10),
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

/**
 * Libelle categorie ("Parent : Enfant", meme format que CategoryAutocomplete
 * — voir flattenCategories) par id, pour que la recherche retrouve aussi une
 * transaction par sa categorie (ex: "Traitements et salaires : Salaire"),
 * pas seulement par tiers/montant.
 */
const categoryLabelById = computed(() => {
  const map = new Map()
  for (const c of flattenCategories(categories.value)) map.set(c.id, c.label)
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
  for (const t of transactions.value) {
    index.set(t.id, {
      payeeNormalized: normalizeSearch(t.payee),
      categoryNormalized: normalizeSearch(labels.get(t.categoryId) || ''),
      rawAmount: String(t.amount ?? '').toLowerCase(),
      absAmount: String(Math.abs(t.amount ?? 0)),
      amountFormattedNormalized: normalizeSearch(formatAmount(t.amount))
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
 * Un critère (un des termes séparés par ";", déjà normalisé) matche cette
 * transaction s'il est contenu dans le tiers, la catégorie, ou le montant
 * (brut, en valeur absolue, ou formaté — virgule ou point acceptés).
 */
function transactionMatchesTerm(idx, term) {
  if (idx.payeeNormalized.includes(term)) return true
  if (idx.categoryNormalized.includes(term)) return true
  const termAsAmount = term.replace(',', '.')
  return (
    idx.rawAmount.includes(termAsAmount) ||
    idx.absAmount.includes(termAsAmount) ||
    idx.amountFormattedNormalized.includes(term)
  )
}

/**
 * Plusieurs critères séparés par ";" sont tous exigés à la fois (ET), ex :
 * "Bouygues;29,99" pour retrouver la transaction Bouygues de ce montant
 * précis parmi plusieurs — chaque critère pris seul reste un simple
 * "contient" (OR entre tiers/catégorie/montant), comme avant.
 */
const filteredTransactions = computed(() => {
  if (amountQuery.value) return statusAndDateFiltered.value

  const terms = searchQuery.value.split(';').map(normalizeSearch).filter(Boolean)
  if (!terms.length) return statusAndDateFiltered.value

  const index = searchIndexById.value

  return statusAndDateFiltered.value.filter(t => {
    const idx = index.get(t.id)
    return idx && terms.every(term => transactionMatchesTerm(idx, term))
  })
})

/**
 * Chargement progressif au scroll cote client : avec un historique de
 * plusieurs milliers de transactions, rendre TOUTES les lignes filtrees dans
 * le DOM a chaque frappe (un composant TransactionRow par ligne) rend la
 * recherche inutilisable (reconciliation de milliers de composants a chaque
 * caractere). On ne rend donc au depart que PAGE_SIZE lignes, puis on
 * augmente ce nombre par paliers de PAGE_SIZE au fil du scroll (voir
 * loadMoreSentinel/loadMoreObserver plus bas) plutot que d'exposer une
 * pagination Precedent/Suivant.
 */
const PAGE_SIZE = 100
const visibleCount = ref(PAGE_SIZE)

const pagedTransactions = computed(() => filteredTransactions.value.slice(0, visibleCount.value))

const hasMoreToShow = computed(() => visibleCount.value < filteredTransactions.value.length)

function loadMore() {
  if (!hasMoreToShow.value) return
  visibleCount.value += PAGE_SIZE
}

/**
 * Sentinelle placee juste apres le tableau : des qu'elle entre dans le
 * viewport (ou approche, voir rootMargin), on charge le lot suivant. Le
 * ref change d'element a chaque fois que la sentinelle apparait/disparait du
 * DOM (v-if="hasMoreToShow"), d'ou le watch qui reobserve a chaque fois
 * plutot qu'un observe() unique au montage.
 */
const loadMoreSentinel = ref(null)
const loadMoreObserver = new IntersectionObserver((entries) => {
  if (entries[0]?.isIntersecting) loadMore()
}, { rootMargin: '600px' })

watch(loadMoreSentinel, (el, oldEl) => {
  if (oldEl) loadMoreObserver.unobserve(oldEl)
  if (el) loadMoreObserver.observe(el)
})

onBeforeUnmount(() => loadMoreObserver.disconnect())

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

/**
 * Etend visibleCount pour que la transaction cochee `id` (voir
 * lastSelectedId) fasse partie du lot actuellement rendu, puis fait defiler
 * la ligne correspondante jusqu'au centre de l'ecran une fois le DOM a jour
 * — pour la retrouver avec les transactions voisines visibles autour d'elle.
 * Si la transaction n'est plus dans la liste filtree courante (ex : filtre
 * "À traiter" actif et transaction validee), retombe simplement sur le
 * premier lot.
 */
function scrollToSelectedTransaction(id) {
  const index = filteredTransactions.value.findIndex(t => t.id === id)
  if (index === -1) {
    visibleCount.value = PAGE_SIZE
    return
  }
  visibleCount.value = Math.max(visibleCount.value, Math.ceil((index + 1) / PAGE_SIZE) * PAGE_SIZE)
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
    visibleCount.value = PAGE_SIZE
  }
)

watch([amountQuery, filterAccount], ([query, accountId]) => {
  clearTimeout(amountSearchTimer)
  if (!query) {
    amountSearchResults.value = null
    amountSearchLoading.value = false
    return
  }
  amountSearchLoading.value = true
  amountSearchTimer = setTimeout(async () => {
    try {
      amountSearchResults.value = await TransactionsAPI.list({
        accountId: accountId ?? undefined,
        amountOperator: query.operator,
        amountValue: query.value
      })
    } finally {
      amountSearchLoading.value = false
    }
  }, 250)
})

const flatCategories = computed(() => flattenCategories(categories.value))

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
const todayIso = new Date().toISOString().slice(0, 10)

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
  if (accounts.value.length) form.value.accountId = accounts.value[0].id
  showModal.value = true
}

async function save() {
  if (!form.value.accountId) {
    alert('Veuillez sélectionner un compte.')
    return
  }

  const payload = {
    accountId: form.value.accountId,
    date: form.value.date,
    payee: form.value.payee,
    amount: form.value.amount,
    categoryId: form.value.categoryId,
    paymentMode: form.value.paymentMode,
    memo: form.value.memo,
    tags: form.value.tags,
    transferTransactionId: form.value.transferTransactionId,
    periodicExpense: form.value.periodicExpense,
    exceptional: form.value.exceptional,
    validated: form.value.validated
  }

  await TransactionsAPI.create(payload)
  showModal.value = false
  await load()
}

/* --- Édition inline : clic sur une ligne --- */

function startEdit(t) {
  editingId.value = t.id
}

function cancelEdit() {
  editingId.value = null
}

async function confirmEdit(payload) {
  if (!editingId.value) return
  const id = editingId.value
  editingId.value = null

  await TransactionsAPI.update(id, payload)
  await load()
}

async function remove(transaction) {
  if (!confirm('Supprimer cette transaction ?')) return
  await TransactionsAPI.remove(transaction.id)
  await load()
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
    alert('Un virement ne peut pas être divisé.')
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
  } finally {
    splitSaving.value = false
  }
}

/** Bascule directement le statut "dépense périodique" sans passer par l'édition complète. */
async function togglePeriodic(transaction) {
  await TransactionsAPI.togglePeriodicExpense(transaction.id)
  await load()
}

/** Bascule directement le statut "exceptionnelle" sans passer par l'édition complète. */
async function toggleExceptional(transaction) {
  await TransactionsAPI.toggleExceptional(transaction.id)
  await load()
}

/* --- Validation / rapprochement (façon "reconcile" YNAB) --- */

const validating = ref(false)

/** Bascule le statut validée/en attente d'une seule transaction. */
async function toggleValidated(transaction) {
  await TransactionsAPI.toggleValidated(transaction.id)
  await load()
}

/**
 * Valide en une fois toutes les transactions en attente : à utiliser une fois
 * que le solde du compte dans l'app correspond au solde réel de la banque,
 * comme le "reconcile" de YNAB. Permet ensuite de repérer d'un coup d'œil
 * les transactions ajoutées depuis la dernière validation (pastille orange).
 */
async function validateAll() {
  if (pendingCount.value === 0) return
  if (!confirm(`Valider les ${pendingCount.value} transaction(s) en attente ?`)) return
  validating.value = true
  try {
    await TransactionsAPI.validateAll()
    await load()
  } finally {
    validating.value = false
  }
}

onMounted(async () => {
  // Si le prechargement lance au demarrage de l'app est deja termine (cas
  // courant), ceci est instantane et n'attend rien. Sinon (premier
  // chargement de l'app directement sur /transactions), on attend juste
  // cette premiere fois.
  await dataStore.ensureLoaded()
  // Revalidation silencieuse en arriere-plan a chaque visite de la page,
  // au cas ou des comptes/categories/tiers/transactions ont change depuis
  // le prechargement (cree ailleurs, autre onglet...).
  dataStore.refreshAll()
  if (filterAccount.value !== null) await load()

  // Arrivee depuis "À traiter" > "Modifier" : ouvre directement la
  // transaction concernee en edition complete, sans action supplementaire.
  const editId = route.query.edit ? Number(route.query.edit) : null
  if (editId) {
    const target = transactions.value.find(t => t.id === editId)
    if (target) startEdit(target)
  }
})
</script>

<style scoped>
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
}
.transactions-table {
  min-width: 1200px;
  width: 100%;
  table-layout: fixed;
}
.col-select { width: 36px; }
.col-date { width: 120px; }
.col-status { width: 50px; }
.col-account { width: 100px; }
.col-payee { width: 280px; }
.col-category { width: auto; }
.col-memo { width: 140px; }
.col-amount { width: 110px; }
.col-monthly { width: 70px; }
.col-actions { width: 68px; }

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
  background: var(--ink-800);
  z-index: 7;
  box-shadow: -6px 0 8px -6px rgba(23, 32, 46, 0.12);
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

.bulk-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 10px;
  background: var(--azure-dim);
  border: 1px solid var(--azure);
  border-radius: var(--radius-md);
}

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
  font-size: 11px;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.07em;
  color: var(--text-faint);
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
  color: #FFFFFF;
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

/* Sentinelle invisible (juste un peu de hauteur pour que l'IntersectionObserver
   ait quelque chose a observer) : le texte n'est qu'un repli si jamais elle
   reste visible plus que le temps d'un rendu (chargement quasi instantane,
   les donnees sont deja en memoire cote client, voir loadMore). */
.load-more-sentinel {
  padding: 14px 0;
  text-align: center;
  color: var(--text-faint);
  font-size: 12px;
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
</style>
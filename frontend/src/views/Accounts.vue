<template>
  <div class="accounts-page">
    <div class="page-header">
      <div>
        <h1>Comptes</h1>
        <div class="subtitle">Gérez vos comptes bancaires</div>
      </div>
      <div style="display:flex; gap:10px;">
        <button class="btn secondary" @click="openTransfer" :disabled="transferableAccounts.length < 2">
          Transfert entre comptes
        </button>
        <button class="btn" @click="openCreate"><Icon name="plus" size="sm" /> Nouveau compte</button>
      </div>
    </div>

    <section v-if="allocation.length" class="card allocation-card" aria-labelledby="allocation-title">
      <div class="allocation-header">
        <div>
          <div id="allocation-title" class="stat-label">Patrimoine net</div>
          <div class="stat-value"><Money :value="netWorth" /></div>
        </div>
        <div class="allocation-count">{{ accountsCountLabel }}</div>
      </div>
      <div class="allocation-bar" role="img" :aria-label="allocationDescription">
        <span
          v-for="part in allocation"
          :key="part.id"
          class="allocation-segment"
          :class="part.type.toLowerCase()"
          :style="{ flexGrow: part.share }"
          :title="`${part.name} : ${formatShare(part.share)}`"
        />
      </div>
      <ul class="allocation-legend">
        <li v-for="part in allocation" :key="part.id">
          <span class="allocation-dot" :class="part.type.toLowerCase()" aria-hidden="true" />
          <span class="allocation-name">{{ part.name }}</span>
          <span class="allocation-share">{{ formatShare(part.share) }}</span>
        </li>
      </ul>
    </section>

    <div v-if="accounts.length" class="table-scroll accounts-table-panel">
    <table class="accounts-table">
      <thead>
        <tr>
          <th>Nom</th>
          <th>Type</th>
          <th class="num">Solde courant</th>
          <th>Devise</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="{ account: a, typeClass, balanceClass, formattedBalance } in accountRows" :key="a.id" :class="{ 'account-archived': a.archived }">
          <td class="account-name">
            {{ a.name }}
            <span v-if="a.archived" class="archived-tag">Archivé</span>
          </td>
          <td><span class="badge" :class="typeClass">{{ accountTypeLabel(a.type) }}</span></td>
          <td class="num amount" :class="balanceClass">
            {{ formattedBalance }}
          </td>
          <td>{{ a.currency }}</td>
          <td>
            <div class="hover-actions">
              <button class="icon-btn" title="Rapprocher avec un relevé bancaire" :aria-label="'Rapprocher ' + a.name" @click="reconciling = a"><Icon name="check" /></button>
              <button
                v-if="!a.archived"
                class="icon-btn"
                title="Actualiser le solde (valeur réelle du compte)"
                :aria-label="'Actualiser le solde de ' + a.name"
                @click="adjusting = a"
              ><Icon name="refresh" /></button>
              <button class="icon-btn" title="Modifier le compte" :aria-label="'Modifier ' + a.name" @click="openEdit(a)"><Icon name="edit" /></button>
              <button
                class="icon-btn"
                :title="a.archived ? 'Désarchiver le compte' : 'Archiver le compte'"
                :aria-label="(a.archived ? 'Désarchiver ' : 'Archiver ') + a.name"
                :disabled="archivingId === a.id"
                @click="toggleArchived(a)"
              ><Icon name="archive" /></button>
              <button class="icon-btn danger" title="Supprimer le compte" :aria-label="'Supprimer ' + a.name" @click="remove(a)"><Icon name="trash" /></button>
            </div>
          </td>
        </tr>
      </tbody>
      <tfoot v-if="accounts.length > 1">
        <tr>
          <td colspan="2">Total</td>
          <td class="num amount" :class="netWorthClass">{{ formattedNetWorth }}</td>
          <td colspan="2"></td>
        </tr>
      </tfoot>
    </table>
    </div>
    <div v-else-if="accountsLoaded" class="empty-state">Aucun compte. Créez-en un pour commencer à suivre vos transactions.</div>

    <AccountModal
      v-if="showModal"
      :account="editing"
      @close="showModal = false"
      @saved="onSaved"
      @deleted="onDeleted"
    />

    <ReconciliationModal
      v-if="reconciling"
      :account="reconciling"
      @close="reconciling = null"
      @validated="onReconciled"
    />

    <BalanceAdjustmentModal
      v-if="adjusting"
      :account="adjusting"
      :current-balance="adjustingBalance"
      @close="adjusting = null"
      @saved="onBalanceAdjusted"
    />

    <!-- Modal : virement entre comptes -->
    <div v-if="showTransferModal" class="modal-overlay" @click.self="showTransferModal = false">
      <div v-dialog="() => (showTransferModal = false)" class="modal" role="dialog" aria-modal="true">
        <h2>Transfert entre comptes</h2>
        <p style="color: var(--text-muted); font-size: 14px; margin-bottom: 16px;">
          Débite le compte source et crédite le compte destination du même montant.
          Ce mouvement n'est jamais compté comme une dépense ou un revenu dans les rapports.
        </p>
        <div class="form-grid">
          <div class="form-field">
            <label for="transfer-from">Compte source</label>
            <select id="transfer-from" v-model.number="transferForm.fromAccountId">
              <option v-for="a in transferableAccounts" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
          <div class="form-field">
            <label for="transfer-to">Compte destination</label>
            <select id="transfer-to" v-model.number="transferForm.toAccountId">
              <option
                v-for="a in transferableAccounts"
                :key="a.id"
                :value="a.id"
                :disabled="a.id === transferForm.fromAccountId"
              >
                {{ a.name }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label for="transfer-amount">Montant</label>
            <AmountInput id="transfer-amount" v-model="transferForm.amount" :allow-relative="false" />
          </div>
          <div class="form-field">
            <label for="transfer-date">Date</label>
            <input id="transfer-date" v-model="transferForm.date" type="date" />
          </div>
          <div class="form-field" style="grid-column: span 2;">
            <label for="transfer-memo">Mémo (facultatif)</label>
            <input id="transfer-memo" v-model="transferForm.memo" placeholder="Ex : Épargne du mois" />
          </div>
        </div>
        <div style="display:flex; justify-content:flex-end; gap:10px; margin-top:20px;">
          <button class="btn secondary" @click="showTransferModal = false">Annuler</button>
          <button class="btn" :disabled="transferring" @click="submitTransfer">
            {{ transferring ? 'Transfert en cours…' : 'Confirmer le transfert' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { notify, askConfirm } from '../services/feedback'
import { ref, computed, onMounted } from 'vue'
import { AccountsAPI, TransactionsAPI } from '../services/api'
import { formatAmount, toLocalIsoDate } from '../services/format'
import { dataStore } from '../services/dataStore'
import { alertApiError } from '../services/errors'
import AmountInput from '../components/AmountInput.vue'
import AccountModal from '../components/AccountModal.vue'
import ReconciliationModal from '../components/ReconciliationModal.vue'
import BalanceAdjustmentModal from '../components/BalanceAdjustmentModal.vue'
import Icon from '../components/Icon.vue'
import Money from '../components/Money.vue'
import { accountBalanceRows, accountCountLabel, accountTypeLabel, activeAccounts, amountToneClass, computeAllocation, computeNetWorth, sortAccountsForDisplay } from '../services/accounts'

// Comptes et soldes partagés avec la sidebar (voir App.vue / dataStore.js) :
// une modification ici (création, édition, suppression, virement) se
// répercute automatiquement dans la sidebar sans code supplémentaire.
const accounts = computed(() => dataStore.state.accounts)
const balances = computed(() => dataStore.state.balances)
// « Aucun compte » seulement après un chargement réussi (sinon : bandeau « serveur injoignable » d'App.vue).
const accountsLoaded = computed(() => dataStore.state.loaded.accounts)
const showModal = ref(false)
const editing = ref(null)

const showTransferModal = ref(false)
const transferring = ref(false)
const transferForm = ref(emptyTransferForm())

function emptyTransferForm() {
  return {
    fromAccountId: null,
    toAccountId: null,
    amount: 0,
    date: toLocalIsoDate(),
    memo: ''
  }
}

/** Comptes courants en tête de liste, comptes archivés en fin, le reste dans l'ordre reçu de l'API. */
const accountRows = computed(() => accountBalanceRows(sortAccountsForDisplay(accounts.value), balances.value))
const accountsCountLabel = computed(() => accountCountLabel(accounts.value.length))

/** Seuls les comptes non archivés peuvent recevoir un virement (voir ServiceCompte#ensureNotArchived). */
const transferableAccounts = computed(() => activeAccounts(accounts.value))

const netWorth = computed(() => computeNetWorth(accounts.value, balances.value))
const netWorthClass = computed(() => amountToneClass(netWorth.value))
const formattedNetWorth = computed(() => formatAmount(netWorth.value))
const allocation = computed(() => computeAllocation(accounts.value, balances.value))

const shareFormatter = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 0 })
function formatShare(share) {
  return shareFormatter.format(share)
}

/** Description textuelle de la barre de répartition, pour les lecteurs d'écran. */
const allocationDescription = computed(() =>
  'Répartition du patrimoine : ' + allocation.value.map(p => `${p.name} ${formatShare(p.share)}`).join(', ')
)

function openCreate() {
  editing.value = null
  showModal.value = true
}

function openEdit(account) {
  editing.value = account
  showModal.value = true
}

async function onSaved() {
  showModal.value = false
  await dataStore.refreshAccounts()
}

async function onDeleted() {
  showModal.value = false
  await dataStore.refreshAccounts()
}

/** Compte en cours de rapprochement avec un relevé bancaire (voir ReconciliationModal). */
const reconciling = ref(null)

async function onReconciled() {
  reconciling.value = null
  // Statuts « validé » modifiés côté serveur : rafraîchit le cache partagé des transactions.
  await dataStore.refreshTransactions()
}

/** Compte dont on actualise le solde (voir BalanceAdjustmentModal). */
const adjusting = ref(null)
const adjustingBalance = computed(() => Number(balances.value[adjusting.value?.id] ?? 0))

async function onBalanceAdjusted() {
  adjusting.value = null
  // Nouvelle transaction « Ajustement de solde » : rafraîchit transactions et soldes.
  await dataStore.refreshTransactions()
}

/** Archivage/désarchivage direct depuis la ligne du tableau, sans passer par la modale. */
const archivingId = ref(null)

async function toggleArchived(account) {
  const archived = !account.archived
  archivingId.value = account.id
  try {
    await AccountsAPI.update(account.id, { ...account, archived })
    await dataStore.refreshAccounts()
    notify(archived ? `Compte « ${account.name} » archivé.` : `Compte « ${account.name} » désarchivé.`, 'success')
  } catch (e) {
    alertApiError("Erreur lors de l'archivage du compte", e)
  } finally {
    archivingId.value = null
  }
}

/** Suppression directe depuis la ligne du tableau (raccourci sans passer par la modale). */
async function remove(account) {
  // Le backend refuse (409) la suppression d'un compte qui contient des
  // transactions (voir ServiceCompte#delete) : proposer plutôt l'archivage.
  if (!(await askConfirm(`Supprimer le compte "${account.name}" ? Seul un compte sans transaction peut être supprimé ; sinon, archivez-le.`))) return
  try {
    await AccountsAPI.remove(account.id)
    await dataStore.refreshAccounts()
    notify('Compte supprimé.', 'success')
  } catch (e) {
    alertApiError('Suppression du compte impossible', e)
  }
}

/* --- Virement entre comptes --- */

function openTransfer() {
  transferForm.value = emptyTransferForm()
  const candidates = transferableAccounts.value
  if (candidates.length >= 2) {
    transferForm.value.fromAccountId = candidates[0].id
    transferForm.value.toAccountId = candidates[1].id
  }
  showTransferModal.value = true
}

async function submitTransfer() {
  const f = transferForm.value
  if (!f.fromAccountId || !f.toAccountId) {
    notify('Veuillez sélectionner les deux comptes.', 'warning')
    return
  }
  if (f.fromAccountId === f.toAccountId) {
    notify('Le compte source et le compte destination doivent être différents.', 'warning')
    return
  }
  if (!f.amount || f.amount <= 0) {
    notify('Le montant du transfert doit être positif.', 'warning')
    return
  }
  transferring.value = true
  try {
    await TransactionsAPI.transfer({
      fromAccountId: f.fromAccountId,
      toAccountId: f.toAccountId,
      amount: f.amount,
      date: f.date,
      memo: f.memo
    })
    showTransferModal.value = false
    // Un virement crée deux transactions : rafraîchit aussi la liste des
    // transactions du cache partagé, pas seulement les soldes.
    await dataStore.refreshTransactions()
    notify('Virement effectué.', 'success')
  } catch (e) {
    alertApiError('Erreur lors du transfert', e)
  } finally {
    transferring.value = false
  }
}

onMounted(() => {
  // Un échec est déjà signalé par le bandeau « serveur injoignable » (App.vue) :
  // on évite seulement qu'il remonte en erreur non gérée.
  dataStore.ensureLoaded().catch(() => {})
})
</script>


<style scoped>
.accounts-page .page-header { margin-bottom: 22px; }
.accounts-table-panel { background: var(--ink-900); border: 1px solid var(--line); border-radius: var(--radius-lg); overflow: auto; box-shadow: var(--shadow-sm); }
.accounts-table tfoot td { padding: 14px 18px; border-top: 1px solid var(--line-strong); background: var(--surface-sunken); font-weight: 650; }

/* Répartition du patrimoine : barre empilée + légende */
.allocation-card { margin-bottom: 18px; }
.allocation-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.allocation-count { color: var(--text-muted); font-size: 13px; }
.allocation-bar { display: flex; gap: 3px; height: 12px; border-radius: 999px; overflow: hidden; background: var(--ink-800); }
.allocation-segment { flex-basis: 0; min-width: 6px; transition: flex-grow 0.4s ease; }
.allocation-legend { display: flex; flex-wrap: wrap; gap: 8px 22px; margin: 14px 0 0; padding: 0; list-style: none; font-size: 13px; }
.allocation-legend li { display: flex; align-items: center; gap: 8px; }
.allocation-dot { width: 9px; height: 9px; border-radius: 3px; }
.allocation-name { color: var(--text-primary); font-weight: 500; }
.allocation-share { color: var(--text-muted); font-variant-numeric: tabular-nums; }
/* Mêmes teintes que les pastilles de type (.badge dans style.css) */
.allocation-segment.checking, .allocation-dot.checking { background: var(--azure); }
.allocation-segment.savings, .allocation-dot.savings { background: var(--mint); }
.allocation-segment.cash, .allocation-dot.cash { background: var(--amber); }
.allocation-segment.credit_card, .allocation-dot.credit_card { background: var(--coral); }
.allocation-segment.investment, .allocation-dot.investment { background: var(--violet); }
@media (prefers-reduced-motion: reduce) { .allocation-segment { transition: none; } }
.accounts-table { width: 100%; border-collapse: separate; border-spacing: 0; }
.accounts-table thead th { background: var(--surface-sunken); color: var(--text-muted); font-size: 12.5px; font-weight: 600; text-transform: none; padding: 14px 18px; border-bottom: 1px solid var(--line); }
.accounts-table tbody td { padding: 15px 18px; border-bottom: 1px solid var(--line); vertical-align: middle; }
.accounts-table tbody tr:last-child td { border-bottom: 0; }
.accounts-table tbody tr { transition: background .15s ease; }
.accounts-table tbody tr:hover { background: var(--row-hover); }
.accounts-table tbody td:first-child { font-weight: 650; color: var(--text-primary); }
.accounts-table .amount { font-variant-numeric: tabular-nums; font-weight: 700; }
.accounts-table .btn { white-space: nowrap; }
.accounts-table tbody tr.account-archived td,
.accounts-table tbody tr.account-archived td:first-child { color: var(--text-muted); }
.accounts-table tbody tr.account-archived .badge,
.accounts-table tbody tr.account-archived .amount { opacity: .6; }
.archived-tag { margin-left: 8px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--line-strong); font-size: 11.5px; font-weight: 600; color: var(--text-muted); vertical-align: middle; }
@media (max-width: 680px) { .accounts-page .page-header { align-items: flex-start; flex-direction: column; } .accounts-table thead th, .accounts-table tbody td { padding: 11px 12px; } }
</style>

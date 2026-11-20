<template>
  <div class="accounts-page">
    <div class="page-header">
      <div>
        <h1>Comptes</h1>
        <div class="subtitle">Gérez vos comptes bancaires</div>
      </div>
      <div class="header-actions">
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
        <li v-for="debt in debts" :key="debt.id" class="allocation-debt" title="Solde négatif : déduit du patrimoine net, hors de la barre">
          <span class="allocation-dot" :class="debt.type.toLowerCase()" aria-hidden="true" />
          <span class="allocation-name">{{ debt.name }}</span>
          <Money :value="debt.balance" :currency="debt.currency" class="allocation-share negative" />
        </li>
      </ul>
    </section>

    <div v-if="accounts.length" class="table-scroll accounts-table-panel">
    <table class="accounts-table">
      <thead>
        <tr>
          <th>Nom</th>
          <th class="col-type">Type</th>
          <th class="num">Solde courant</th>
          <th class="col-currency">Devise</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="{ account: a, typeClass, balanceClass, formattedBalance } in accountRows" :key="a.id" :class="{ 'account-archived': a.archived }">
          <td class="account-name">
            <RouterLink :to="{ path: '/transactions', query: { account: String(a.id) } }" class="account-link" :title="'Voir les transactions de ' + a.name">{{ a.name }}</RouterLink>
            <span v-if="a.archived" class="archived-tag">Archivé</span>
            <RouterLink
              v-if="pendingCountByAccountId.get(a.id)"
              class="pending-tag"
              :to="{ path: '/transactions', query: { account: String(a.id), pending: '1' } }"
              :title="'Voir les opérations à traiter (non validées) de ' + a.name"
            >{{ pendingCountByAccountId.get(a.id) }} à traiter</RouterLink>
          </td>
          <td class="col-type"><span class="badge" :class="typeClass">{{ accountTypeLabel(a.type) }}</span></td>
          <td class="num amount" :class="balanceClass">
            {{ formattedBalance }}
          </td>
          <td class="col-currency">{{ a.currency }}</td>
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
          <!-- Une cellule par colonne (pas de colspan) : les colonnes masquées en mobile restent alignées. -->
          <td>Total</td>
          <td class="col-type"></td>
          <td class="num amount" :class="netWorthClass">{{ formattedNetWorth }}</td>
          <td class="col-currency"></td>
          <td></td>
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
    <TransferModal
      v-if="showTransferModal"
      :accounts="transferableAccounts"
      :balances="balances"
      @close="showTransferModal = false"
      @saved="onTransferred"
    />

    <UndoBar :action="lastAction" :undoing="undoing" @undo="runLastAction" />
  </div>
</template>

<script setup>
import { notify, askConfirm } from '../services/feedback'
import { ref, computed, onMounted } from 'vue'
import { useRouteActions } from '../services/routeActions'
import { AccountsAPI, TransactionsAPI } from '../services/api'
import { formatAmount } from '../services/format'
import { dataStore } from '../services/dataStore'
import { alertApiError } from '../services/errors'
import AccountModal from '../components/AccountModal.vue'
import ReconciliationModal from '../components/ReconciliationModal.vue'
import BalanceAdjustmentModal from '../components/BalanceAdjustmentModal.vue'
import TransferModal from '../components/TransferModal.vue'
import Icon from '../components/Icon.vue'
import Money from '../components/Money.vue'
import UndoBar from '../components/UndoBar.vue'
import { useLastAction } from '../services/lastAction'
import { accountBalanceRows, accountCountLabel, accountDeletionBlocker, accountPayloadFrom, accountTypeLabel, activeAccounts, amountToneClass, computeAllocation, computeDebts, computeNetWorth, sortAccountsForDisplay } from '../services/accounts'

// Comptes et soldes partagés avec la sidebar (voir App.vue / dataStore.js) :
// une modification ici (création, édition, suppression, virement) se
// répercute automatiquement dans la sidebar sans code supplémentaire.
const accounts = computed(() => dataStore.state.accounts)

/** Opérations en attente de validation (rapprochement) par compte, en un seul passage sur le cache. */
const pendingCountByAccountId = computed(() => {
  const counts = new Map()
  for (const t of dataStore.state.transactions) {
    if (t.validated === false) counts.set(t.accountId, (counts.get(t.accountId) ?? 0) + 1)
  }
  return counts
})
const balances = computed(() => dataStore.state.balances)
// « Aucun compte » seulement après un chargement réussi (sinon : bandeau « serveur injoignable » d'App.vue).
const accountsLoaded = computed(() => dataStore.state.loaded.accounts)
const showModal = ref(false)
const editing = ref(null)

const showTransferModal = ref(false)

/** Comptes courants en tête de liste, comptes archivés en fin, le reste dans l'ordre reçu de l'API. */
const accountRows = computed(() => accountBalanceRows(sortAccountsForDisplay(accounts.value), balances.value))
const accountsCountLabel = computed(() => accountCountLabel(accounts.value.length))

/** Seuls les comptes non archivés peuvent recevoir un virement (voir ServiceCompte#ensureNotArchived). */
const transferableAccounts = computed(() => activeAccounts(accounts.value))

const netWorth = computed(() => computeNetWorth(accounts.value, balances.value))
const netWorthClass = computed(() => amountToneClass(netWorth.value))
const formattedNetWorth = computed(() => formatAmount(netWorth.value))
const allocation = computed(() => computeAllocation(accounts.value, balances.value))
/** Comptes à solde négatif, listés après la répartition (voir computeDebts). */
const debts = computed(() => computeDebts(accounts.value, balances.value))

const shareFormatter = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 0 })
function formatShare(share) {
  return shareFormatter.format(share)
}

/** Description textuelle de la barre de répartition, pour les lecteurs d'écran. */
const allocationDescription = computed(() =>
  'Répartition du patrimoine : ' + allocation.value.map(p => `${p.name} ${formatShare(p.share)}`).join(', ')
  + debts.value.map(d => `, ${d.name} ${formatAmount(d.balance, d.currency)} déduit`).join('')
)

function openCreate() {
  editing.value = null
  showModal.value = true
}

function openEdit(account) {
  editing.value = account
  showModal.value = true
}

// Transactions rechargées avec les comptes (refreshTransactions inclut les
// soldes) : le libellé « Virement vers <compte> » d'une transaction suit le
// nom du compte (voir MappeurTransaction#payeeLabel).
async function onSaved(saved) {
  const before = editing.value
  showModal.value = false
  await dataStore.refreshTransactions()
  if (before) {
    const previous = accountPayloadFrom(before)
    setLastAction('Compte modifié', async () => {
      await AccountsAPI.update(before.id, previous)
      await dataStore.refreshTransactions()
    })
  } else if (saved?.id) {
    // Un compte tout juste créé est vide : sa suppression est toujours acceptée.
    setLastAction('Compte créé', async () => {
      await AccountsAPI.remove(saved.id)
      await dataStore.refreshAccounts()
    })
  }
}

// Seul un compte sans transaction peut être supprimé (voir ServiceCompte#delete).
async function onDeleted() {
  const deleted = editing.value
  showModal.value = false
  await dataStore.refreshAccounts()
  if (deleted) offerUndoDeletion(deleted)
}

/** Un compte supprimé est toujours vide : le recréer avec ses champs suffit à annuler. */
function offerUndoDeletion(account) {
  const payload = accountPayloadFrom(account)
  setLastAction('Compte supprimé', async () => {
    await AccountsAPI.create(payload)
    await dataStore.refreshAccounts()
  })
}

/** Compte en cours de rapprochement avec un relevé bancaire (voir ReconciliationModal). */
const reconciling = ref(null)

async function onReconciled(validatedIds = []) {
  reconciling.value = null
  // Statuts « validé » modifiés côté serveur : rafraîchit le cache partagé des transactions.
  await dataStore.refreshTransactions()
  if (!validatedIds.length) return
  setLastAction('Rapprochement validé', async () => {
    await TransactionsAPI.bulkUpdate({ ids: validatedIds, validatedProvided: true, validated: false })
    await dataStore.refreshTransactions()
  })
}

/** Compte dont on actualise le solde (voir BalanceAdjustmentModal). */
const adjusting = ref(null)
const adjustingBalance = computed(() => Number(balances.value[adjusting.value?.id] ?? 0))

async function onBalanceAdjusted(adjustment) {
  adjusting.value = null
  // Nouvelle transaction « Ajustement de solde » : rafraîchit transactions et soldes.
  await dataStore.refreshTransactions()
  // Annuler : supprime la transaction d'ajustement, le solde revient à l'ancien.
  if (adjustment?.id) {
    setLastAction('Solde actualisé', async () => {
      await TransactionsAPI.undoCreation([adjustment.id])
      await dataStore.refreshTransactions()
    })
  }
}

/** Annulation de la dernière action sur les comptes : virement, (dés)archivage, création, modification, suppression (voir services/lastAction.js). */
const { lastAction, undoing, setLastAction, runLastAction } = useLastAction()

/** Archivage/désarchivage direct depuis la ligne du tableau, sans passer par la modale. */
const archivingId = ref(null)

async function toggleArchived(account) {
  const archived = !account.archived
  archivingId.value = account.id
  try {
    await AccountsAPI.update(account.id, { ...account, archived })
    await dataStore.refreshAccounts()
    notify(archived ? `Compte « ${account.name} » archivé.` : `Compte « ${account.name} » désarchivé.`, 'success')
    setLastAction(archived ? 'Compte archivé' : 'Compte désarchivé', async () => {
      await AccountsAPI.update(account.id, { ...account, archived: !archived })
      await dataStore.refreshAccounts()
    })
  } catch (e) {
    alertApiError("Erreur lors de l'archivage du compte", e)
  } finally {
    archivingId.value = null
  }
}

/** Suppression directe depuis la ligne du tableau (raccourci sans passer par la modale). */
async function remove(account) {
  // Le backend refuse (409) la suppression d'un compte qui contient des
  // transactions (voir ServiceCompte#delete) : prévenu ici, sans demander
  // une confirmation qui aboutirait forcément à un refus.
  const blocker = accountDeletionBlocker(account, dataStore.state.transactions)
  if (blocker) {
    notify(blocker, 'warning', 6000)
    return
  }
  if (!(await askConfirm(`Supprimer le compte "${account.name}" ?`))) return
  try {
    await AccountsAPI.remove(account.id)
    await dataStore.refreshAccounts()
    notify('Compte supprimé.', 'success')
    offerUndoDeletion(account)
  } catch (e) {
    alertApiError('Suppression du compte impossible', e)
  }
}

/* --- Virement entre comptes (saisie : TransferModal.vue) --- */

function openTransfer() {
  showTransferModal.value = true
}

async function onTransferred(debit) {
  showTransferModal.value = false
  // Un virement crée deux transactions : rafraîchit aussi la liste des
  // transactions du cache partagé, pas seulement les soldes.
  await dataStore.refreshTransactions()
  notify('Virement effectué.', 'success')
  // Supprimer un côté du virement supprime aussi sa jumelle (voir ServiceSuppressionTransaction#undoCreation).
  setLastAction('Virement effectué', async () => {
    await TransactionsAPI.undoCreation([debit.id])
    await dataStore.refreshTransactions()
  })
}

onMounted(() => {
  // Un échec est déjà signalé par le bandeau « serveur injoignable » (App.vue) :
  // on évite seulement qu'il remonte en erreur non gérée.
  dataStore.ensureLoaded().catch(() => {})
})

// Actions lancées depuis la palette de commandes (Ctrl+K), voir services/routeActions.js.
useRouteActions({ 'new-account': openCreate, transfer: openTransfer })
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
/* Soldes négatifs : séparés de la répartition par un filet (ils n'ont pas de part dans la barre). */
.allocation-legend li:not(.allocation-debt) + .allocation-debt { border-left: 1px solid var(--line); padding-left: 22px; }
.allocation-share.negative { color: var(--coral); }
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
/* Nom du compte : lien vers ses transactions (filtre ?account= de la page Transactions) */
.account-link { color: inherit; text-decoration: none; }
.account-link:hover { color: var(--azure); text-decoration: underline; text-underline-offset: 3px; }
.accounts-table thead th { background: var(--table-head-bg); color: var(--table-head-text); font-size: 12.5px; font-weight: 600; text-transform: none; padding: 14px 18px; border-bottom: 1px solid var(--table-head-border); }
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
/* Opérations à traiter du compte : lien vers la liste filtrée (même teinte que le liseré « à traiter » des transactions). */
.pending-tag { margin-left: 8px; padding: 1px 8px; border-radius: 999px; background: var(--amber-dim); color: var(--amber); font-size: 11.5px; font-weight: 600; text-decoration: none; vertical-align: middle; white-space: nowrap; }
.pending-tag:hover { text-decoration: underline; }
.archived-tag { margin-left: 8px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--line-strong); font-size: 11.5px; font-weight: 600; color: var(--text-muted); vertical-align: middle; }
@media (max-width: 680px) { .accounts-page .page-header { align-items: flex-start; flex-direction: column; } .accounts-table thead th, .accounts-table tbody td, .accounts-table tfoot td { padding: 11px 12px; } }
/* Téléphone : le type (déjà signalé par la couleur de la pastille de la barre de
   navigation) et la devise laissent la place au solde, qui sinon sortait de l'écran. */
@media (max-width: 520px) {
  .accounts-table .col-type, .accounts-table .col-currency { display: none; }
  /* Tient dans l'écran sans défilement horizontal (la règle mobile générale de
     style.css impose 640 px aux tableaux) : actions sur deux lignes, marges réduites. */
  .accounts-table-panel .accounts-table { min-width: 0; }
  /* Chaque compte sur deux lignes : nom et solde, puis ses actions sur toute la largeur. */
  .accounts-table, .accounts-table thead, .accounts-table tbody, .accounts-table tfoot { display: block; }
  .accounts-table tr { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; }
  .accounts-table thead th:last-child, .accounts-table tfoot td:last-child { display: none; }
  .accounts-table tbody tr { border-bottom: 1px solid var(--line); }
  .accounts-table tbody tr:last-child { border-bottom: 0; }
  .accounts-table tbody td { border-bottom: 0; }
  .accounts-table tbody td:last-child { grid-column: 1 / -1; padding-top: 0; }
  .accounts-table .hover-actions { justify-content: flex-start; }
}
</style>

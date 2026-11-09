<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <AppLogo />
        <span class="brand-name"><strong>Home</strong>Bank</span>
      </div>
      <nav aria-label="Navigation principale">
        <div class="nav-group">
          <RouterLink to="/" class="nav-item" :class="{ active: $route.name === 'dashboard' }">
            <Icon name="dashboard" /> Tableau de bord
          </RouterLink>
          <RouterLink to="/transactions" class="nav-item" :class="{ active: $route.path.startsWith('/transactions') }">
            <Icon name="transactions" /> Transactions
          </RouterLink>
          <RouterLink to="/accounts" class="nav-item" :class="{ active: $route.name === 'accounts' }">
            <Icon name="accounts" /> Comptes
          </RouterLink>
        </div>
        <div class="nav-group">
          <RouterLink to="/categories" class="nav-item" :class="{ active: $route.name === 'categories' }">
            <Icon name="categories" /> Catégories
          </RouterLink>
          <RouterLink to="/payees" class="nav-item" :class="{ active: $route.name === 'payees' }">
            <Icon name="payees" /> Tiers
          </RouterLink>
          <RouterLink to="/loans" class="nav-item" :class="{ active: $route.name === 'loans' }">
            <Icon name="loans" /> Prêts
          </RouterLink>
        </div>
        <div class="nav-group">
          <RouterLink to="/import" class="nav-item" :class="{ active: $route.name === 'import' }">
            <Icon name="import" /> Importer
          </RouterLink>
        </div>
      </nav>

      <div v-if="sortedAccounts.length" class="sidebar-accounts">
        <div class="sidebar-networth">
          <div class="sidebar-networth-label">Patrimoine net</div>
          <div class="sidebar-networth-value"><Money :value="netWorth" /></div>
        </div>
        <button
          v-for="a in sortedAccounts"
          :key="a.id"
          type="button"
          class="sidebar-account"
          @click="openAccount(a)"
        >
          <span class="sidebar-account-dot" :class="a.type.toLowerCase()" aria-hidden="true"></span>
          <span class="sidebar-account-name">{{ a.name }}</span>
          <span
            class="sidebar-account-balance amount"
            :class="(balances[a.id] ?? 0) >= 0 ? 'positive' : 'negative'"
          >
            {{ formatAmount(balances[a.id] ?? 0) }}
          </span>
        </button>
      </div>
    </aside>
    <main class="main-content" :class="{ 'main-content-wide': $route.name === 'transactions' }">
      <RouterView />
    </main>

    <AccountModal
      v-if="editingAccount"
      :account="editingAccount"
      @close="editingAccount = null"
      @saved="onAccountSaved"
      @deleted="onAccountSaved"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { dataStore } from './services/dataStore'
import { formatAmount } from './services/format'
import AccountModal from './components/AccountModal.vue'
import AppLogo from './components/AppLogo.vue'
import Icon from './components/Icon.vue'
import Money from './components/Money.vue'

// Precharge comptes, categories, tiers et transactions des le demarrage de
// l'app, en arriere-plan, pour que la page Transactions (et les autres)
// s'affichent instantanement des que l'utilisateur y navigue.
onMounted(() => {
  // Erreur ignorée ici (pas d'UI à mettre à jour dans la sidebar pour ça) :
  // chaque page affichée ensuite retente le chargement et affiche sa propre
  // erreur via dataStore.ensureLoaded() (voir Transactions.vue, Payees.vue,
  // ImportView.vue) — sans ce .catch, un échec au démarrage remonterait ici
  // en rejet de promesse non gérée.
  dataStore.prefetch().catch(() => {})
})

const balances = computed(() => dataStore.state.balances)

/** Comptes courants toujours en tête de liste, comme sur la page Comptes. */
const sortedAccounts = computed(() =>
  [...dataStore.state.accounts].sort((a, b) => (a.type === 'CHECKING' ? 0 : 1) - (b.type === 'CHECKING' ? 0 : 1))
)

/** Somme de tous les soldes (cartes de crédit en négatif : patrimoine NET). */
const netWorth = computed(() =>
  dataStore.state.accounts.reduce((sum, a) => sum + (balances.value[a.id] ?? 0), 0)
)

const editingAccount = ref(null)

function openAccount(account) {
  editingAccount.value = account
}

async function onAccountSaved() {
  editingAccount.value = null
  await dataStore.refreshAccounts()
}
</script>

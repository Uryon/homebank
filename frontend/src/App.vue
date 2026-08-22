<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">◆</span> HomeBank</div>
      <nav>
        <RouterLink to="/" class="nav-item" :class="{ active: $route.name === 'dashboard' }">
          <span class="dot"></span> Tableau de bord
        </RouterLink>
        <RouterLink to="/accounts" class="nav-item" :class="{ active: $route.name === 'accounts' }">
          <span class="dot"></span> Comptes
        </RouterLink>
        <RouterLink to="/transactions" class="nav-item" :class="{ active: $route.name === 'transactions' }">
          <span class="dot"></span> Transactions
        </RouterLink>
        <RouterLink to="/categories" class="nav-item" :class="{ active: $route.name === 'categories' }">
          <span class="dot"></span> Catégories
        </RouterLink>
        <RouterLink to="/payees" class="nav-item" :class="{ active: $route.name === 'payees' }">
          <span class="dot"></span> Tiers
        </RouterLink>
        <RouterLink to="/loans" class="nav-item" :class="{ active: $route.name === 'loans' }">
          <span class="dot"></span> Prêts
        </RouterLink>
        <RouterLink to="/import" class="nav-item" :class="{ active: $route.name === 'import' }">
          <span class="dot"></span> Import CSV/QIF
        </RouterLink>
      </nav>

      <div v-if="sortedAccounts.length" class="sidebar-accounts">
        <div class="sidebar-accounts-header">Comptes</div>
        <button
          v-for="a in sortedAccounts"
          :key="a.id"
          type="button"
          class="sidebar-account"
          @click="openAccount(a)"
        >
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
    <main class="main-content">
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

// Precharge comptes, categories, tiers et transactions des le demarrage de
// l'app, en arriere-plan, pour que la page Transactions (et les autres)
// s'affichent instantanement des que l'utilisateur y navigue.
onMounted(() => {
  dataStore.prefetch()
})

const balances = computed(() => dataStore.state.balances)

/** Comptes courants toujours en tête de liste, comme sur la page Comptes. */
const sortedAccounts = computed(() =>
  [...dataStore.state.accounts].sort((a, b) => (a.type === 'CHECKING' ? 0 : 1) - (b.type === 'CHECKING' ? 0 : 1))
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

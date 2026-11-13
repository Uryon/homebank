<template>
  <div class="app-shell">
    <a class="skip-link" href="#main-content">Passer au contenu principal</a>
    <aside class="sidebar">
      <div class="brand">
        <AppLogo />
        <span class="brand-name"><strong>Home</strong>Bank</span>
      </div>
      <button type="button" class="sidebar-search" @click="paletteOpen = true">
        <Icon name="search" size="sm" />
        <span>Rechercher…</span>
        <kbd aria-label="raccourci Ctrl K">Ctrl K</kbd>
      </button>
      <nav aria-label="Navigation principale">
        <div v-for="(group, groupIndex) in NAV_GROUPS" :key="groupIndex" class="nav-group">
          <RouterLink
            v-for="item in group"
            :key="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ active: isNavActive(item) }"
            :aria-current="isNavActive(item) ? 'page' : undefined"
          >
            <Icon :name="item.icon" /> {{ item.label }}
            <span
              v-if="item.showUncategorizedBadge && uncategorizedCount"
              class="nav-badge"
              :title="uncategorizedLabel"
              :aria-label="uncategorizedLabel"
            >{{ uncategorizedCount }}</span>
          </RouterLink>
        </div>
      </nav>

      <div v-if="sidebarAccounts.length" class="sidebar-accounts">
        <div class="sidebar-networth">
          <div class="sidebar-networth-label">Patrimoine net</div>
          <div class="sidebar-networth-value"><Money :value="netWorth" /></div>
        </div>
        <button
          v-for="entry in sidebarAccounts"
          :key="entry.account.id"
          type="button"
          class="sidebar-account"
          @click="openAccount(entry.account)"
        >
          <span class="sidebar-account-dot" :class="entry.typeClass" aria-hidden="true"></span>
          <span class="sidebar-account-name">{{ entry.account.name }}</span>
          <span class="sidebar-account-balance amount" :class="entry.balanceClass">
            {{ entry.formattedBalance }}
          </span>
        </button>
      </div>
      <ThemeSwitcher class="sidebar-theme" />
      <button type="button" class="sidebar-shortcuts" @click="shortcutsOpen = true">
        <Icon name="help" size="sm" />
        <span>Raccourcis clavier</span>
        <kbd aria-label="raccourci point d'interrogation">?</kbd>
      </button>
    </aside>
    <main id="main-content" tabindex="-1" class="main-content" :class="{ 'main-content-wide': isWideRoute }" :aria-busy="routeLoading">
      <div v-if="routeLoading" class="route-progress" aria-hidden="true"><span /></div>
      <div v-if="loadFailed" class="connection-banner" role="alert">
        <span class="connection-banner-dot" aria-hidden="true" />
        <div class="connection-banner-text">
          <strong>Serveur HomeBank injoignable.</strong>
          Les données ne peuvent pas être chargées : vérifiez que le backend est démarré. Nouvel essai automatique toutes les 10 secondes.
        </div>
        <button type="button" class="btn secondary" :disabled="retrying" @click="retryLoad">
          {{ retrying ? 'Connexion…' : 'Réessayer' }}
        </button>
      </div>
      <RouterView v-slot="{ Component, route: viewRoute }">
        <Transition name="page" mode="out-in">
          <component :is="Component" :key="viewRoute.path" />
        </Transition>
      </RouterView>
    </main>

    <FeedbackHost />
    <CommandPalette :open="paletteOpen" @close="paletteOpen = false" @show-shortcuts="shortcutsOpen = true" />
    <ShortcutsDialog :open="shortcutsOpen" @close="shortcutsOpen = false" />
    <OverflowTooltip />

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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { dataStore } from './services/dataStore'
import { accountBalanceRows, activeAccounts, computeNetWorth, sortAccountsForDisplay } from './services/accounts'
import AccountModal from './components/AccountModal.vue'
import AppLogo from './components/AppLogo.vue'
import Icon from './components/Icon.vue'
import Money from './components/Money.vue'
import FeedbackHost from './components/FeedbackHost.vue'
import ThemeSwitcher from './components/ThemeSwitcher.vue'
import CommandPalette from './components/CommandPalette.vue'
import ShortcutsDialog from './components/ShortcutsDialog.vue'
import OverflowTooltip from './components/OverflowTooltip.vue'

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

const router = useRouter()
const route = useRoute()

/**
 * Navigation principale, par groupes. matchPrefix : l'entrée reste active sur
 * ses sous-pages (ex : « Transactions » sur /transactions/doublons).
 */
const NAV_GROUPS = [
  [
    { to: '/', label: 'Tableau de bord', icon: 'dashboard' },
    { to: '/transactions', label: 'Transactions', icon: 'transactions', matchPrefix: true, showUncategorizedBadge: true },
    { to: '/accounts', label: 'Comptes', icon: 'accounts' }
  ],
  [
    { to: '/categories', label: 'Catégories', icon: 'categories' },
    { to: '/payees', label: 'Tiers', icon: 'payees' },
    { to: '/loans', label: 'Prêts', icon: 'loans' }
  ],
  [
    { to: '/import', label: 'Importer', icon: 'import' }
  ]
]

function isNavActive(item) {
  return item.matchPrefix
    ? route.path === item.to || route.path.startsWith(`${item.to}/`)
    : route.path === item.to
}

const isWideRoute = computed(() => route.name === 'transactions')

/*
 * Barre de progression de navigation : affichée seulement si le changement de
 * page dure plus de ROUTE_PROGRESS_DELAY_MS. Une page déjà chargée s'ouvre
 * quasi instantanément, et la barre ne faisait alors que clignoter.
 */
const ROUTE_PROGRESS_DELAY_MS = 150
const routeLoading = ref(false)
let routeProgressTimer = null

function stopRouteProgress() {
  clearTimeout(routeProgressTimer)
  routeProgressTimer = null
  routeLoading.value = false
}

router.beforeEach(() => {
  clearTimeout(routeProgressTimer)
  routeProgressTimer = setTimeout(() => { routeLoading.value = true }, ROUTE_PROGRESS_DELAY_MS)
})
router.afterEach(stopRouteProgress)
router.onError(stopRouteProgress)

const balances = computed(() => dataStore.state.balances)

/* --- Serveur injoignable : bandeau + nouvel essai automatique --- */
const RETRY_INTERVAL_MS = 10000
const loadFailed = computed(() => dataStore.state.loadFailed)
const retrying = ref(false)
let retryTimer = null

async function retryLoad() {
  if (retrying.value) return
  retrying.value = true
  try {
    await dataStore.refreshAll()
  } catch {
    // L'échec est déjà reflété par dataStore.state.loadFailed (bandeau affiché).
  } finally {
    retrying.value = false
  }
}

watch(loadFailed, (failed) => {
  clearInterval(retryTimer)
  retryTimer = failed ? setInterval(retryLoad, RETRY_INTERVAL_MS) : null
})

onBeforeUnmount(() => {
  clearInterval(retryTimer)
  clearTimeout(routeProgressTimer)
})

/**
 * Comptes courants toujours en tête de liste, comme sur la page Comptes.
 * Les comptes archivés n'y figurent pas (ils restent sur la page Comptes),
 * mais comptent toujours dans le patrimoine net ci-dessous.
 */
const sidebarAccounts = computed(() =>
  accountBalanceRows(sortAccountsForDisplay(activeAccounts(dataStore.state.accounts)), balances.value)
)

/** Transactions restant à catégoriser : pastille sur l'entrée « Transactions » de la navigation. */
const uncategorizedCount = computed(() => dataStore.state.transactions.filter(t => !t.categoryId).length)
const uncategorizedLabel = computed(() => `${uncategorizedCount.value} transaction(s) sans catégorie`)

/** Somme de tous les soldes (cartes de crédit en négatif : patrimoine NET). */
const netWorth = computed(() => computeNetWorth(dataStore.state.accounts, balances.value))

/** Palette de commandes : Ctrl+K (ou Cmd+K) depuis n'importe quelle page. */
const paletteOpen = ref(false)

/** Aide des raccourcis clavier : touche « ? », bouton de la barre latérale ou palette. */
const shortcutsOpen = ref(false)

/** Champs de recherche des pages, ciblés par la touche « / ». */
const PAGE_SEARCH_SELECTOR = '#tx-search, #payees-search, #categories-search'

/** Vrai si l'utilisateur est en train de saisir du texte ou si une fenêtre est ouverte : pas de raccourci à une touche. */
function isTypingOrDialogOpen(event) {
  const target = event.target
  const typing = target instanceof HTMLElement
    && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
  return typing || !!document.querySelector('.modal-overlay, .feedback-overlay, .palette-overlay')
}

function onGlobalKeydown(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    paletteOpen.value = !paletteOpen.value
    return
  }
  if (event.ctrlKey || event.metaKey || event.altKey || isTypingOrDialogOpen(event)) return

  if (event.key === '/') {
    // Recherche de la page affichée, sinon la palette (recherche globale)
    event.preventDefault()
    const search = document.querySelector(PAGE_SEARCH_SELECTOR)
    if (search) search.focus()
    else paletteOpen.value = true
  } else if (event.key === 'n' || event.key === 'N') {
    event.preventDefault()
    router.push({ path: '/transactions', query: { new: '1' } })
  } else if (event.key === '?') {
    // event.key vaut « ? » quelle que soit la disposition du clavier (Maj+, en AZERTY).
    event.preventDefault()
    shortcutsOpen.value = true
  }
}

onMounted(() => window.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKeydown))

const editingAccount = ref(null)

function openAccount(account) {
  editingAccount.value = account
}

async function onAccountSaved() {
  editingAccount.value = null
  await dataStore.refreshAccounts()
}
</script>

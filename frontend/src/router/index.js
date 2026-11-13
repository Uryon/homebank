import { createRouter, createWebHistory } from 'vue-router'

// Pages chargées à la demande : chacune devient un fichier JS séparé, et
// Chart.js (tableau de bord, prêts) n'est plus téléchargé pour les autres pages.
const routes = [
    { path: '/', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: 'Tableau de bord' } },
    { path: '/accounts', name: 'accounts', component: () => import('../views/Accounts.vue'), meta: { title: 'Comptes' } },
    { path: '/transactions', name: 'transactions', component: () => import('../views/Transactions.vue'), meta: { title: 'Transactions' } },
    { path: '/transactions/periodiques', name: 'transactions-periodic-suggestions', component: () => import('../views/TransactionsPeriodicSuggestions.vue'), meta: { title: 'Transactions périodiques' } },
    { path: '/transactions/doublons', name: 'transactions-duplicates', component: () => import('../views/TransactionsDuplicates.vue'), meta: { title: 'Doublons' } },
    { path: '/categories', name: 'categories', component: () => import('../views/Categories.vue'), meta: { title: 'Catégories' } },
    { path: '/payees', name: 'payees', component: () => import('../views/Payees.vue'), meta: { title: 'Tiers' } },
    { path: '/loans', name: 'loans', component: () => import('../views/Loans.vue'), meta: { title: 'Prêts' } },
    { path: '/import', name: 'import', component: () => import('../views/ImportView.vue'), meta: { title: 'Importer' } }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

// Titre de l'onglet du navigateur propre à chaque page (repérage parmi plusieurs onglets, historique).
router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} · HomeBank` : 'HomeBank'
})

export default router

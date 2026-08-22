import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import Accounts from '../views/Accounts.vue'
import Transactions from '../views/Transactions.vue'
import TransactionsPeriodicSuggestions from '../views/TransactionsPeriodicSuggestions.vue'
import TransactionsDuplicates from '../views/TransactionsDuplicates.vue'
import Categories from '../views/Categories.vue'
import Payees from '../views/Payees.vue'
import Loans from '../views/Loans.vue'
import ImportView from '../views/ImportView.vue'

const routes = [
    { path: '/', name: 'dashboard', component: Dashboard },
    { path: '/accounts', name: 'accounts', component: Accounts },
    { path: '/transactions', name: 'transactions', component: Transactions },
    { path: '/transactions/periodiques', name: 'transactions-periodic-suggestions', component: TransactionsPeriodicSuggestions },
    { path: '/transactions/doublons', name: 'transactions-duplicates', component: TransactionsDuplicates },
    { path: '/categories', name: 'categories', component: Categories },
    { path: '/payees', name: 'payees', component: Payees },
    { path: '/loans', name: 'loans', component: Loans },
    { path: '/import', name: 'import', component: ImportView }
]

export default createRouter({
    history: createWebHistory(),
    routes
})

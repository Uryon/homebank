import axios from 'axios'

const api = axios.create({
    baseURL: '/api'
})

export const AccountsAPI = {
    list: () => api.get('/accounts').then(r => r.data),
    get: (id) => api.get(`/accounts/${id}`).then(r => r.data),
    balances: () => api.get('/accounts/balances').then(r => r.data),
    balance: (id) => api.get(`/accounts/${id}/balance`).then(r => r.data.balance),
    create: (payload) => api.post('/accounts', payload).then(r => r.data),
    update: (id, payload) => api.put(`/accounts/${id}`, payload).then(r => r.data),
    remove: (id) => api.delete(`/accounts/${id}`)
}

export const CategoriesAPI = {
    list: () => api.get('/categories').then(r => r.data),
    roots: () => api.get('/categories/roots').then(r => r.data),
    create: (payload) => api.post('/categories', payload).then(r => r.data),
    update: (id, payload) => api.put(`/categories/${id}`, payload).then(r => r.data),
    remove: (id) => api.delete(`/categories/${id}`),
    merge: (sourceIds, targetId) => api.post('/categories/merge', { sourceIds, targetId }).then(r => r.data)
}

export const TransactionsAPI = {
    list: (params) => api.get('/transactions', { params }).then(r => r.data),
    get: (id) => api.get(`/transactions/${id}`).then(r => r.data),
    create: (payload) => api.post('/transactions', payload).then(r => r.data),
    update: (id, payload) => api.put(`/transactions/${id}`, payload).then(r => r.data),
    remove: (id) => api.delete(`/transactions/${id}`),
    pending: () => api.get('/transactions/pending').then(r => r.data),
    pendingCount: () => api.get('/transactions/pending-count').then(r => r.data.count),
    validateAll: () => api.post('/transactions/validate-all').then(r => r.data),
    toggleValidated: (id) => api.post(`/transactions/${id}/toggle-validated`).then(r => r.data),
    toggleMonthlyExpense: (id) => api.post(`/transactions/${id}/toggle-monthly-expense`).then(r => r.data),
    transfer: (payload) => api.post('/transactions/transfer', payload).then(r => r.data)
}

export const ReportsAPI = {
    expensesByCategory: (params) => api.get('/reports/expenses-by-category', { params }).then(r => r.data),
    incomeByCategory: (params) => api.get('/reports/income-by-category', { params }).then(r => r.data),
    balanceEvolution: (params) => api.get('/reports/balance-evolution', { params }).then(r => r.data),
    summary: (params) => api.get('/reports/summary', { params }).then(r => r.data),
    monthlyCategoryAnalysis: (params) => api.get('/reports/monthly-category-analysis', { params }).then(r => r.data),
    monthlySavings: (params) => api.get('/reports/monthly-savings', { params }).then(r => r.data),
    balanceForecast: (params) => api.get('/reports/balance-forecast', { params }).then(r => r.data),
    monthlyExpenses: () => api.get('/reports/monthly-expenses').then(r => r.data)
}

export const PayeesAPI = {
    list: () => api.get('/payees').then(r => r.data),
    create: (payload) => api.post('/payees', payload).then(r => r.data),
    update: (id, payload) => api.put(`/payees/${id}`, payload).then(r => r.data),
    remove: (id) => api.delete(`/payees/${id}`),
    initializeFromTransactions: () => api.post('/payees/initialize').then(r => r.data),
    merge: (sourceIds, targetId) => api.post('/payees/merge', { sourceIds, targetId }).then(r => r.data)
}

export const LoansAPI = {
    list: () => api.get('/loans').then(r => r.data),
    get: (id) => api.get(`/loans/${id}`).then(r => r.data),
    schedule: (id) => api.get(`/loans/${id}/schedule`).then(r => r.data),
    create: (payload) => api.post('/loans', payload).then(r => r.data),
    update: (id, payload) => api.put(`/loans/${id}`, payload).then(r => r.data),
    remove: (id) => api.delete(`/loans/${id}`)
}

export const ImportAPI = {
    csv: (file, accountId) => {
        const form = new FormData()
        form.append('file', file)
        form.append('accountId', accountId)
        return api.post('/import/csv', form, { headers: { 'Content-Type': 'multipart/form-data' } }).then(r => r.data)
    },
    qif: (file, accountId) => {
        const form = new FormData()
        form.append('file', file)
        form.append('accountId', accountId)
        return api.post('/import/qif', form, { headers: { 'Content-Type': 'multipart/form-data' } }).then(r => r.data)
    }
}

export default api

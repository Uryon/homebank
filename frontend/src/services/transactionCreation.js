import { computed, ref } from 'vue'
import { TransactionsAPI } from './api'
import { askConfirm, notify } from './feedback'
import { alertApiError } from './errors'
import { dataStore } from './dataStore'
import { findIdenticalTransaction } from './duplicateEntry'
import { formatAmount, formatDate, toLocalIsoDate } from './format'
import { transactionPayloadFrom } from './undoRestore'
import { duplicateBlockedReason } from './transactionRules'

function emptyForm() {
  return {
    accountId: null,
    date: toLocalIsoDate(),
    payee: '',
    // Vide plutôt que 0 : on tape directement le montant, sans effacer un zéro.
    amount: null,
    categoryId: null,
    paymentMode: 'NONE',
    memo: '',
    transferTransactionId: null,
    periodicExpense: false,
    exceptional: false,
    validated: true
  }
}

/**
 * Création d'une transaction (fenêtre TransactionCreateModal.vue), vierge ou
 * dupliquée d'une transaction existante, annulable.
 *
 * - `entryAccounts` : comptes ouverts à la saisie (ref), jamais un compte archivé ;
 * - `filterAccount` : compte filtré de la liste (ref), proposé par défaut ;
 * - `load` : rechargement des données après l'enregistrement ;
 * - `setLastAction` : enregistrement de l'annulation (voir lastAction.js).
 */
export function useTransactionCreation({ entryAccounts, filterAccount, load, setLastAction }) {
  /** Valeurs de départ de la fenêtre « Nouvelle transaction », null fenêtre fermée. */
  const newTransactionDraft = ref(null)

  /**
   * Compte proposé pour une nouvelle transaction : le compte filtré, quand on
   * consulte un compte précis (sauf s'il est archivé, donc fermé à la saisie),
   * sinon le premier compte actif.
   */
  const defaultEntryAccountId = computed(() => {
    const accounts = entryAccounts.value
    return accounts.find(a => a.id === filterAccount.value)?.id ?? accounts[0]?.id ?? null
  })

  function openCreate() {
    newTransactionDraft.value = { ...emptyForm(), accountId: defaultEntryAccountId.value }
  }

  /**
   * Ouvre la création pré-remplie avec une transaction existante (tiers,
   * montant, catégorie, mode de paiement, mémo...), datée d'aujourd'hui : pour
   * une dépense qui revient sans être assez régulière pour une suggestion
   * périodique. Rien n'est créé avant « Enregistrer ». Le compte d'origine
   * est repris s'il est encore actif, sinon le compte par défaut.
   */
  function duplicateTransaction(transaction) {
    const blocked = duplicateBlockedReason(transaction)
    if (blocked) {
      notify(`${blocked}.`, 'warning')
      return
    }
    const accountIsActive = entryAccounts.value.some(a => a.id === transaction.accountId)
    newTransactionDraft.value = {
      ...emptyForm(),
      accountId: accountIsActive ? transaction.accountId : defaultEntryAccountId.value,
      payee: transaction.payee || '',
      amount: transaction.amount,
      categoryId: transaction.categoryId,
      paymentMode: transaction.paymentMode || 'NONE',
      memo: transaction.memo || '',
      periodicExpense: !!transaction.periodicExpense,
      exceptional: !!transaction.exceptional
    }
  }

  /** Enregistre le formulaire validé par TransactionCreateModal.vue. */
  async function save(form) {
    const payload = transactionPayloadFrom(form)
    // Double saisie probable : confirmation explicite plutôt que refus (deux
    // achats identiques le même jour restent possibles).
    const identical = findIdenticalTransaction(payload, dataStore.state.transactions)
    if (identical) {
      const label = `${identical.payee || 'sans tiers'}, ${formatAmount(identical.amount)}, ${formatDate(identical.date)}`
      const confirmed = await askConfirm(`Une transaction identique existe déjà sur ce compte (${label}). L'enregistrer quand même ?`, {
        title: 'Transaction en double ?',
        confirmLabel: 'Enregistrer quand même',
        destructive: false
      })
      if (!confirmed) return
    }

    try {
      const created = await TransactionsAPI.create(payload)
      notify('Transaction créée.', 'success')
      newTransactionDraft.value = null
      dataStore.showSavedTransaction(created)
      await load()
      setLastAction('Transaction créée', async () => {
        await TransactionsAPI.undoCreation([created.id])
        await load()
      })
    } catch (e) {
      alertApiError('Erreur lors de la création de la transaction', e)
    }
  }

  return { newTransactionDraft, openCreate, duplicateTransaction, save }
}

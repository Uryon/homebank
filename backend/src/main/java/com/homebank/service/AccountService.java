package com.homebank.service;

import com.homebank.dto.AccountDTO;
import com.homebank.model.Account;
import com.homebank.repository.AccountRepository;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<Account> findAll() {
        return accountRepository.findAll();
    }

    public Account findById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Compte introuvable : " + id));
    }

    public Account create(AccountDTO payload) {
        Account account = new Account();
        account.setName(payload.getName());
        account.setType(payload.getType());
        account.setInitialBalance(payload.getInitialBalance());
        account.setCurrency(payload.getCurrency());
        account.setNotes(payload.getNotes());
        account.setArchived(payload.isArchived());
        return accountRepository.save(account);
    }

    public Account update(Long id, AccountDTO payload) {
        Account existing = findById(id);
        existing.setName(payload.getName());
        existing.setType(payload.getType());
        existing.setInitialBalance(payload.getInitialBalance());
        existing.setCurrency(payload.getCurrency());
        existing.setNotes(payload.getNotes());
        existing.setArchived(payload.isArchived());
        return accountRepository.save(existing);
    }

    public void delete(Long id) {
        accountRepository.deleteById(id);
    }

    /**
     * Solde courant "reel" = solde initial + somme des transactions deja
     * survenues (date <= aujourd'hui). Une transaction datee dans le futur
     * est seulement prevue : elle n'a pas encore reellement eu lieu et ne
     * doit donc pas encore impacter le solde affiche (voir aussi
     * ReportService, meme principe applique aux statistiques).
     */
    private BigDecimal currentBalance(Account account) {
        return account.getInitialBalance()
                .add(transactionRepository.sumAmountByAccountIdAsOf(account.getId(), LocalDate.now()));
    }

    /**
     * Solde de chaque compte, pour le tableau de bord. Reutilise directement
     * les comptes deja charges par findAll() au lieu de les recharger un par
     * un par id.
     */
    public Map<Long, BigDecimal> allBalances() {
        return accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getId, this::currentBalance));
    }
}

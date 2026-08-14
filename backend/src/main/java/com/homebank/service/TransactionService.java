package com.homebank.service;

import com.homebank.model.Account;
import com.homebank.model.Category;
import com.homebank.model.Transaction;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final CategoryService categoryService;

    public TransactionService(TransactionRepository transactionRepository,
                               AccountService accountService,
                               CategoryService categoryService) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.categoryService = categoryService;
    }

    public List<Transaction> findAll() {
        return transactionRepository.findAllByOrderByDateDesc();
    }

    public List<Transaction> findByAccount(Long accountId) {
       return transactionRepository.findByAccount_IdOrderByDateDesc(accountId);
    }

    public List<Transaction> findByPeriod(LocalDate from, LocalDate to) {
        return transactionRepository.findByDateBetween(from, to);
    }

    public Transaction findById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction introuvable : " + id));
    }

    public Transaction create(Transaction payload) {
        Transaction t = new Transaction();
        applyPayload(t, payload);
        return transactionRepository.save(t);
    }

    public Transaction update(Long id, Transaction payload) {
        Transaction existing = findById(id);
        applyPayload(existing, payload);
        return transactionRepository.save(existing);
    }

    public void delete(Long id) {
        transactionRepository.deleteById(id);
    }

    private void applyPayload(Transaction t, Transaction payload) {
        if (payload.getAccount() == null || payload.getAccount().getId() == null) {
            throw new IllegalArgumentException("Le compte est obligatoire");
        }
        Account account = accountService.findById(payload.getAccount().getId());
        t.setAccount(account);
        t.setDate(payload.getDate());
        t.setAmount(payload.getAmount());
        t.setPayee(payload.getPayee());
        t.setMemo(payload.getMemo());
        t.setPaymentMode(payload.getPaymentMode());
        t.setStatus(payload.getStatus());
        t.setTags(payload.getTags());

        if (payload.getCategory() != null && payload.getCategory().getId() != null) {
            Category category = categoryService.findById(payload.getCategory().getId());
            t.setCategory(category);
        } else {
            t.setCategory(null);
        }
    }
}

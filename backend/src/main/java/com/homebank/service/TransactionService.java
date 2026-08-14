package com.homebank.service;

import com.homebank.dto.TransactionDTO;
import com.homebank.mapper.TransactionMapper;
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
    private final TransactionMapper transactionMapper;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountService accountService,
            CategoryService categoryService,
            TransactionMapper transactionMapper) {

        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionMapper = transactionMapper;
    }

    public List<TransactionDTO> findAll() {
        return transactionRepository.findAllByOrderByDateDesc()
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public List<TransactionDTO> findByAccount(Long accountId) {
        return transactionRepository
                .findByAccount_IdOrderByDateDesc(accountId)
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public List<TransactionDTO> findByPeriod(LocalDate from, LocalDate to) {
        return transactionRepository
                .findByDateBetween(from, to)
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public TransactionDTO findById(Long id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Transaction introuvable : " + id
                        ));

        return transactionMapper.toDTO(transaction);
    }

    public TransactionDTO create(TransactionDTO payload) {
        Transaction transaction = new Transaction();

        applyPayload(transaction, payload);

        Transaction saved = transactionRepository.save(transaction);

        return transactionMapper.toDTO(saved);
    }

    public TransactionDTO update(Long id, TransactionDTO payload) {
        Transaction existing = transactionRepository.findById(id)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Transaction introuvable : " + id
                        ));

        applyPayload(existing, payload);

        Transaction saved = transactionRepository.save(existing);

        return transactionMapper.toDTO(saved);
    }

    public void delete(Long id) {
        transactionRepository.deleteById(id);
    }

    private void applyPayload(Transaction transaction, TransactionDTO payload) {

        if (payload.getAccountId() == null) {
            throw new IllegalArgumentException(
                    "Le compte est obligatoire"
            );
        }

        Account account = accountService.findById(payload.getAccountId());
        transaction.setAccount(account);

        transaction.setDate(payload.getDate());
        transaction.setAmount(payload.getAmount());
        transaction.setPayee(payload.getPayee());
        transaction.setMemo(payload.getMemo());
        transaction.setPaymentMode(payload.getPaymentMode());
        transaction.setStatus(payload.getStatus());
        transaction.setTags(payload.getTags());

        if (payload.getCategoryId() != null) {
            Category category =
                    categoryService.findById(payload.getCategoryId());

            transaction.setCategory(category);
        } else {
            transaction.setCategory(null);
        }
    }
}
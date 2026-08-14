package com.homebank.controller;

import com.homebank.model.Transaction;
import com.homebank.service.TransactionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public List<Transaction> findAll(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try{
        if (accountId != null) {
            List<Transaction> transactions = transactionService.findByAccount(accountId);
            System.out.println("Transactions trouvées : " + transactions.size());
            return transactions;
        }
        if (from != null && to != null) {
            List<Transaction> transactions =  transactionService.findByPeriod(from, to);
            System.out.println("Transactions trouvées : " + transactions.size());
            return transactions;
        }
        List<Transaction> transactions =  transactionService.findAll();
        System.out.println("Transactions trouvées : " + transactions.size());
        return transactions;
        }catch(Exception e){
            System.out.println(e.toString());
            return null;
        }
    }

    @GetMapping("/{id}")
    public Transaction findById(@PathVariable Long id) {
        return transactionService.findById(id);
    }

    @PostMapping
    public ResponseEntity<Transaction> create(@RequestBody Transaction transaction) {
        return ResponseEntity.ok(transactionService.create(transaction));
    }

    @PutMapping("/{id}")
    public Transaction update(@PathVariable Long id, @RequestBody Transaction transaction) {
        return transactionService.update(id, transaction);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

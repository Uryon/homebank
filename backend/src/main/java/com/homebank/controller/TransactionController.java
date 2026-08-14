package com.homebank.controller;

import com.homebank.dto.TransactionDTO;
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
    public List<TransactionDTO> findAll(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try{
        if (accountId != null) {
            List<TransactionDTO> transactions = transactionService.findByAccount(accountId);
            System.out.println("Transactions trouvées : " + transactions.size());
            return transactions;
        }
        if (from != null && to != null) {
            List<TransactionDTO> transactions =  transactionService.findByPeriod(from, to);
            System.out.println("Transactions trouvées : " + transactions.size());
            return transactions;
        }
        List<TransactionDTO> transactions =  transactionService.findAll();
        System.out.println("Transactions trouvées : " + transactions.size());
        return transactions;
        }catch(Exception e){
            System.out.println(e.toString());
            return null;
        }
    }

    @GetMapping("/{id}")
    public TransactionDTO findById(@PathVariable Long id) {
        return transactionService.findById(id);
    }

    @PostMapping
    public ResponseEntity<TransactionDTO> create(@RequestBody TransactionDTO transaction) {
        return ResponseEntity.ok(transactionService.create(transaction));
    }

    @PutMapping("/{id}")
    public TransactionDTO update(@PathVariable Long id, @RequestBody TransactionDTO transaction) {
        return transactionService.update(id, transaction);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

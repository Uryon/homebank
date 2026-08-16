package com.homebank.controller;

import com.homebank.dto.TransactionDTO;
import com.homebank.dto.TransferRequestDTO;
import com.homebank.service.TransactionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
        if (accountId != null) {
            return transactionService.findByAccount(accountId);
        }
        if (from != null && to != null) {
            return transactionService.findByPeriod(from, to);
        }
        return transactionService.findAll();
    }

    /**
     * Transactions en attente de validation (rapprochement bancaire).
     */
    @GetMapping("/pending")
    public List<TransactionDTO> findPending() {
        return transactionService.findPending();
    }

    @GetMapping("/pending-count")
    public Map<String, Long> countPending() {
        return Map.of("count", transactionService.countPending());
    }

    /**
     * Passe toutes les transactions en attente au statut "validee".
     */
    @PostMapping("/validate-all")
    public Map<String, Integer> validateAll() {
        return Map.of("validated", transactionService.validateAllPending());
    }

    @PostMapping("/{id}/toggle-validated")
    public TransactionDTO toggleValidated(@PathVariable Long id) {
        return transactionService.toggleValidated(id);
    }

    @PostMapping("/{id}/toggle-monthly-expense")
    public TransactionDTO toggleMonthlyExpense(@PathVariable Long id) {
        return transactionService.toggleMonthlyExpense(id);
    }

    /**
     * Cree un virement entre deux comptes (deux transactions liees).
     */
    @PostMapping("/transfer")
    public List<TransactionDTO> createTransfer(@RequestBody TransferRequestDTO request) {
        return transactionService.createTransfer(request);
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

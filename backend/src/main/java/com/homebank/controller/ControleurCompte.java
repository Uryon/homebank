package com.homebank.controller;

import com.homebank.dto.CompteDTO;
import com.homebank.dto.RapprochementDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.dto.ValidationRapprochementDTO;
import com.homebank.mapper.MappeurCompte;
import com.homebank.service.ServiceCompte;
import com.homebank.service.ServiceRapprochement;
import com.homebank.service.ServiceTransaction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class ControleurCompte {

    private final ServiceCompte accountService;
    private final ServiceRapprochement reconciliationService;
    private final ServiceTransaction transactionService;
    private final MappeurCompte accountMapper;

    public ControleurCompte(ServiceCompte accountService, ServiceRapprochement reconciliationService,
                            ServiceTransaction transactionService, MappeurCompte accountMapper) {
        this.accountService = accountService;
        this.reconciliationService = reconciliationService;
        this.transactionService = transactionService;
        this.accountMapper = accountMapper;
    }

    @GetMapping
    public List<CompteDTO> findAll() {
        return accountService.findAll().stream()
                .map(accountMapper::toDTO)
                .toList();
    }

    @GetMapping("/balances")
    public Map<Long, BigDecimal> allBalances() {
        return accountService.allBalances();
    }

    @PostMapping
    public ResponseEntity<CompteDTO> create(@RequestBody CompteDTO payload) {
        return ResponseEntity.ok(accountMapper.toDTO(accountService.create(payload)));
    }

    @PutMapping("/{id}")
    public CompteDTO update(@PathVariable Long id, @RequestBody CompteDTO payload) {
        return accountMapper.toDTO(accountService.update(id, payload));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Compare le solde du compte a celui d'un releve bancaire a une date (rapprochement). */
    @GetMapping("/{id}/reconciliation")
    public RapprochementDTO reconciliation(@PathVariable Long id,
                                           @RequestParam LocalDate date,
                                           @RequestParam BigDecimal statementBalance) {
        return reconciliationService.compare(id, date, statementBalance);
    }

    /** Aligne le solde du compte sur sa valeur reelle a une date (cree une transaction "Ajustement de solde"). */
    @PostMapping("/{id}/balance-adjustment")
    public TransactionDTO adjustBalance(@PathVariable Long id,
                                       @RequestParam LocalDate date,
                                       @RequestParam BigDecimal actualBalance) {
        return transactionService.adjustAccountBalance(id, date, actualBalance);
    }

    /** Valide les transactions en attente du compte jusqu'a la date du releve rapproche. */
    @PostMapping("/{id}/reconciliation/validate")
    public ValidationRapprochementDTO validateReconciled(@PathVariable Long id, @RequestParam LocalDate date) {
        return reconciliationService.validatePendingUpTo(id, date);
    }
}

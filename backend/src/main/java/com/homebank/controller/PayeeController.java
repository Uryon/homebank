package com.homebank.controller;

import com.homebank.dto.PayeeDTO;
import com.homebank.service.PayeeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/payees")
public class PayeeController {

    private final PayeeService payeeService;

    public PayeeController(PayeeService payeeService) {
        this.payeeService = payeeService;
    }

    @GetMapping
    public List<PayeeDTO> findAll() {
        return payeeService.findAll();
    }

    @GetMapping("/{id}")
    public PayeeDTO findById(@PathVariable Long id) {
        return payeeService.findById(id);
    }

    @PostMapping
    public ResponseEntity<PayeeDTO> create(@RequestBody PayeeDTO payee) {
        return ResponseEntity.ok(payeeService.create(payee));
    }

    @PutMapping("/{id}")
    public PayeeDTO update(@PathVariable Long id, @RequestBody PayeeDTO payee) {
        return payeeService.update(id, payee);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payeeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Cree automatiquement un tiers pour chaque tiers deja present dans les
     * transactions existantes mais pas encore enregistre dans la liste des tiers.
     */
    @PostMapping("/initialize")
    public Map<String, Integer> initializeFromExistingTransactions() {
        return payeeService.initializeFromExistingTransactions();
    }
}

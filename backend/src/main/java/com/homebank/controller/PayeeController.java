package com.homebank.controller;

import com.homebank.dto.MergeRequestDTO;
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

    /**
     * Fusionne plusieurs tiers dans un tiers cible : toutes les transactions
     * des tiers source sont renommees vers la cible, puis les tiers source
     * sont supprimes. Operation atomique.
     */
    @PostMapping("/merge")
    public PayeeDTO merge(@RequestBody MergeRequestDTO request) {
        return payeeService.merge(request.getSourceIds(), request.getTargetId());
    }
}

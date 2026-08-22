package com.homebank.controller;

import com.homebank.dto.DemandeFusionDTO;
import com.homebank.dto.TiersDTO;
import com.homebank.service.ServiceTiers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payees")
public class ControleurTiers {

    private final ServiceTiers payeeService;

    public ControleurTiers(ServiceTiers payeeService) {
        this.payeeService = payeeService;
    }

    @GetMapping
    public List<TiersDTO> findAll() {
        return payeeService.findAll();
    }

    @PostMapping
    public ResponseEntity<TiersDTO> create(@RequestBody TiersDTO payee) {
        return ResponseEntity.ok(payeeService.create(payee));
    }

    @PutMapping("/{id}")
    public TiersDTO update(@PathVariable Long id, @RequestBody TiersDTO payee) {
        return payeeService.update(id, payee);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        payeeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Fusionne plusieurs tiers dans un tiers cible : toutes les transactions
     * des tiers source sont renommees vers la cible, puis les tiers source
     * sont supprimes. Le motif final de la cible est celui fourni dans la
     * requete (choisi par l'utilisateur), inchange si absent. Operation
     * atomique.
     */
    @PostMapping("/merge")
    public TiersDTO merge(@RequestBody DemandeFusionDTO request) {
        return payeeService.merge(request.getSourceIds(), request.getTargetId(), request.getPattern());
    }
}

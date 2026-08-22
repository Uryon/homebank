package com.homebank.controller;

import com.homebank.dto.PretDTO;
import com.homebank.dto.EcheancierPretDTO;
import com.homebank.mapper.MappeurPret;
import com.homebank.model.Pret;
import com.homebank.service.ServicePret;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
public class ControleurPret {

    private final ServicePret loanService;
    private final MappeurPret loanMapper;

    public ControleurPret(ServicePret loanService, MappeurPret loanMapper) {
        this.loanService = loanService;
        this.loanMapper = loanMapper;
    }

    @GetMapping
    public List<PretDTO> findAll() {
        return loanService.findAll().stream()
                .map(loanMapper::toDTO)
                .toList();
    }

    /** Echeancier complet (tableau d'amortissement) pour le graphique et le tableau detaille. */
    @GetMapping("/{id}/schedule")
    public EcheancierPretDTO schedule(@PathVariable Long id) {
        Pret loan = loanService.findById(id);
        return EcheancierPretDTO.builder()
                .loan(loanMapper.toDTO(loan))
                .rows(loanService.computeSchedule(loan))
                .build();
    }

    @PostMapping
    public ResponseEntity<PretDTO> create(@RequestBody PretDTO payload) {
        return ResponseEntity.ok(loanMapper.toDTO(loanService.create(payload)));
    }

    @PutMapping("/{id}")
    public PretDTO update(@PathVariable Long id, @RequestBody PretDTO payload) {
        return loanMapper.toDTO(loanService.update(id, payload));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        loanService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

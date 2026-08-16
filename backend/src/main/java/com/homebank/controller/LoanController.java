package com.homebank.controller;

import com.homebank.dto.LoanDTO;
import com.homebank.dto.LoanScheduleDTO;
import com.homebank.mapper.LoanMapper;
import com.homebank.model.Loan;
import com.homebank.service.LoanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;
    private final LoanMapper loanMapper;

    public LoanController(LoanService loanService, LoanMapper loanMapper) {
        this.loanService = loanService;
        this.loanMapper = loanMapper;
    }

    @GetMapping
    public List<LoanDTO> findAll() {
        return loanService.findAll().stream()
                .map(loanMapper::toDTO)
                .toList();
    }

    @GetMapping("/{id}")
    public LoanDTO findById(@PathVariable Long id) {
        return loanMapper.toDTO(loanService.findById(id));
    }

    /** Echeancier complet (tableau d'amortissement) pour le graphique et le tableau detaille. */
    @GetMapping("/{id}/schedule")
    public LoanScheduleDTO schedule(@PathVariable Long id) {
        Loan loan = loanService.findById(id);
        return LoanScheduleDTO.builder()
                .loan(loanMapper.toDTO(loan))
                .rows(loanService.computeSchedule(loan))
                .build();
    }

    @PostMapping
    public ResponseEntity<LoanDTO> create(@RequestBody Loan loan) {
        return ResponseEntity.ok(loanMapper.toDTO(loanService.create(loan)));
    }

    @PutMapping("/{id}")
    public LoanDTO update(@PathVariable Long id, @RequestBody Loan loan) {
        return loanMapper.toDTO(loanService.update(id, loan));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        loanService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

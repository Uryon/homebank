package com.homebank.controller;

import com.homebank.dto.CompteDTO;
import com.homebank.mapper.MappeurCompte;
import com.homebank.service.ServiceCompte;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class ControleurCompte {

    private final ServiceCompte accountService;
    private final MappeurCompte accountMapper;

    public ControleurCompte(ServiceCompte accountService, MappeurCompte accountMapper) {
        this.accountService = accountService;
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
}

package com.homebank.controller;

import com.homebank.dto.AccountDTO;
import com.homebank.mapper.AccountMapper;
import com.homebank.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountMapper accountMapper;

    public AccountController(AccountService accountService, AccountMapper accountMapper) {
        this.accountService = accountService;
        this.accountMapper = accountMapper;
    }

    @GetMapping
    public List<AccountDTO> findAll() {
        return accountService.findAll().stream()
                .map(accountMapper::toDTO)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountDTO findById(@PathVariable Long id) {
        return accountMapper.toDTO(accountService.findById(id));
    }

    @GetMapping("/{id}/balance")
    public Map<String, BigDecimal> balance(@PathVariable Long id) {
        return Map.of("balance", accountService.currentBalance(id));
    }

    @GetMapping("/balances")
    public Map<Long, BigDecimal> allBalances() {
        return accountService.allBalances();
    }

    @PostMapping
    public ResponseEntity<AccountDTO> create(@RequestBody AccountDTO payload) {
        return ResponseEntity.ok(accountMapper.toDTO(accountService.create(payload)));
    }

    @PutMapping("/{id}")
    public AccountDTO update(@PathVariable Long id, @RequestBody AccountDTO payload) {
        return accountMapper.toDTO(accountService.update(id, payload));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

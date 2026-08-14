package com.homebank.controller;

import com.homebank.model.Account;
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

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public List<Account> findAll() {
        return accountService.findAll();
    }

    @GetMapping("/{id}")
    public Account findById(@PathVariable Long id) {
        return accountService.findById(id);
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
    public ResponseEntity<Account> create(@RequestBody Account account) {
        return ResponseEntity.ok(accountService.create(account));
    }

    @PutMapping("/{id}")
    public Account update(@PathVariable Long id, @RequestBody Account account) {
        return accountService.update(id, account);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

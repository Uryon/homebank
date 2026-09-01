package com.homebank.controller;

import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.dto.TransactionBulkUpdateDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.dto.DemandeDivisionDTO;
import com.homebank.dto.DemandeVirementDTO;
import com.homebank.service.ServiceTransaction;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class ControleurTransaction {

    private final ServiceTransaction transactionService;

    public ControleurTransaction(ServiceTransaction transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public List<TransactionDTO> findAll(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String amountOperator,
            @RequestParam(required = false) BigDecimal amountValue) {
        if (amountOperator != null && amountValue != null) {
            return transactionService.findByAmountFilter(accountId, amountOperator, amountValue);
        }
        if (accountId != null) {
            return transactionService.findByAccount(accountId);
        }
        if (from != null && to != null) {
            return transactionService.findByPeriod(from, to);
        }
        return transactionService.findAll();
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

    @PostMapping("/{id}/toggle-periodic-expense")
    public TransactionDTO togglePeriodicExpense(@PathVariable Long id) {
        return transactionService.togglePeriodicExpense(id);
    }

    @PostMapping("/{id}/toggle-exceptional")
    public TransactionDTO toggleExceptional(@PathVariable Long id) {
        return transactionService.toggleExceptional(id);
    }

    /**
     * Suggestions de transactions qui semblent periodiques (meme tiers,
     * montant proche, plusieurs occurrences), pas encore marquees comme
     * telles. Voir ServiceTransaction#findPeriodicSuggestions.
     */
    @GetMapping("/periodic-suggestions")
    public List<SuggestionPeriodiqueDTO> periodicSuggestions() {
        return transactionService.findPeriodicSuggestions();
    }

    /**
     * Ignore durablement une suggestion de transactions periodiques, pour
     * qu'elle ne revienne plus a chaque visite de l'onglet "Periodiques"
     * (voir ServiceTransaction#ignorePeriodicSuggestion).
     */
    @PostMapping("/ignore-periodic-suggestion")
    public ResponseEntity<Void> ignorePeriodicSuggestion(@RequestBody List<Long> ids) {
        transactionService.ignorePeriodicSuggestion(ids);
        return ResponseEntity.noContent().build();
    }

    /**
     * Groupes de transactions deja en base qui semblent doublonnees (voir
     * ServiceTransaction#findDuplicateGroups), pour l'onglet "Doublons".
     */
    @GetMapping("/duplicates")
    public List<GroupeDoublonDTO> duplicates() {
        return transactionService.findDuplicateGroups();
    }

    /**
     * Marque une ou plusieurs transactions comme n'etant pas des doublons,
     * pour les exclure de l'onglet "Doublons" (voir
     * ServiceTransaction#markAsNotDuplicate).
     */
    @PostMapping("/mark-not-duplicate")
    public ResponseEntity<Void> markNotDuplicate(@RequestBody List<Long> ids) {
        transactionService.markAsNotDuplicate(ids);
        return ResponseEntity.noContent().build();
    }

    /**
     * Cree un virement entre deux comptes (deux transactions liees).
     */
    @PostMapping("/transfer")
    public List<TransactionDTO> createTransfer(@RequestBody DemandeVirementDTO request) {
        return transactionService.createTransfer(request);
    }

    @PostMapping
    public ResponseEntity<TransactionDTO> create(@RequestBody TransactionDTO transaction) {
        return ResponseEntity.ok(transactionService.create(transaction));
    }

    @PutMapping("/{id}")
    public TransactionDTO update(@PathVariable Long id, @RequestBody TransactionDTO transaction) {
        return transactionService.update(id, transaction);
    }

    /**
     * Divise une transaction en deux (voir ServiceTransaction#splitTransaction).
     */
    @PostMapping("/{id}/split")
    public List<TransactionDTO> split(@PathVariable Long id, @RequestBody DemandeDivisionDTO payload) {
        return transactionService.splitTransaction(id, payload);
    }

    /**
     * Modifie en une fois un ou plusieurs champs sur toutes les transactions
     * dont l'id figure dans le payload (selection multiple de l'onglet
     * Transactions).
     */
    @PutMapping("/bulk")
    public List<TransactionDTO> bulkUpdate(@RequestBody TransactionBulkUpdateDTO payload) {
        return transactionService.bulkUpdate(payload);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

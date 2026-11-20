package com.homebank.controller;

import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.dto.SuggestionPeriodiqueDTO;
import com.homebank.dto.TransactionBulkUpdateDTO;
import com.homebank.dto.TransactionDTO;
import com.homebank.dto.DemandeAnnulationDivisionDTO;
import com.homebank.dto.DemandeDivisionDTO;
import com.homebank.dto.DemandeVirementDTO;
import com.homebank.service.ServiceDoublon;
import com.homebank.service.ServiceRestaurationTransaction;
import com.homebank.service.ServiceSuggestionPeriodique;
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
    private final ServiceSuggestionPeriodique periodicSuggestionService;
    private final ServiceDoublon duplicateService;
    private final ServiceRestaurationTransaction restorationService;

    public ControleurTransaction(ServiceTransaction transactionService,
                                 ServiceSuggestionPeriodique periodicSuggestionService,
                                 ServiceDoublon duplicateService,
                                 ServiceRestaurationTransaction restorationService) {
        this.transactionService = transactionService;
        this.periodicSuggestionService = periodicSuggestionService;
        this.duplicateService = duplicateService;
        this.restorationService = restorationService;
    }

    @GetMapping
    public List<TransactionDTO> findAll(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String amountOperator,
            @RequestParam(required = false) BigDecimal amountValue) {
        return transactionService.search(accountId, from, to, amountOperator, amountValue);
    }

    /**
     * Passe les transactions en attente au statut "validee" : toutes, ou
     * celles du seul compte accountId (liste filtree par compte).
     */
    @PostMapping("/validate-all")
    public Map<String, Integer> validateAll(@RequestParam(required = false) Long accountId) {
        return Map.of("validated", transactionService.validateAllPending(accountId));
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
     * telles. Voir ServiceSuggestionPeriodique#findPeriodicSuggestions.
     */
    @GetMapping("/periodic-suggestions")
    public List<SuggestionPeriodiqueDTO> periodicSuggestions() {
        return periodicSuggestionService.findPeriodicSuggestions();
    }

    /**
     * Ignore durablement une suggestion de transactions periodiques, pour
     * qu'elle ne revienne plus a chaque visite de l'onglet "Periodiques"
     * (voir ServiceSuggestionPeriodique#ignorePeriodicSuggestion).
     */
    @PostMapping("/ignore-periodic-suggestion")
    public ResponseEntity<Void> ignorePeriodicSuggestion(@RequestBody List<Long> ids) {
        periodicSuggestionService.ignorePeriodicSuggestion(ids);
        return ResponseEntity.noContent().build();
    }

    /** Annule ignorePeriodicSuggestion (voir ServiceSuggestionPeriodique#unignorePeriodicSuggestion). */
    @PostMapping("/unignore-periodic-suggestion")
    public ResponseEntity<Void> unignorePeriodicSuggestion(@RequestBody List<Long> ids) {
        periodicSuggestionService.unignorePeriodicSuggestion(ids);
        return ResponseEntity.noContent().build();
    }

    /**
     * Groupes de transactions deja en base qui semblent doublonnees (voir
     * ServiceDoublon#findDuplicateGroups), pour l'onglet "Doublons".
     */
    @GetMapping("/duplicates")
    public List<GroupeDoublonDTO> duplicates() {
        return duplicateService.findDuplicateGroups();
    }

    /**
     * Marque une ou plusieurs transactions comme n'etant pas des doublons,
     * pour les exclure de l'onglet "Doublons" (voir
     * ServiceDoublon#markAsNotDuplicate).
     */
    @PostMapping("/mark-not-duplicate")
    public ResponseEntity<Void> markNotDuplicate(@RequestBody List<Long> ids) {
        duplicateService.markAsNotDuplicate(ids);
        return ResponseEntity.noContent().build();
    }

    /** Annule markNotDuplicate (voir ServiceDoublon#unmarkAsNotDuplicate). */
    @PostMapping("/unmark-not-duplicate")
    public ResponseEntity<Void> unmarkNotDuplicate(@RequestBody List<Long> ids) {
        duplicateService.unmarkAsNotDuplicate(ids);
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

    /** Annule une division (voir ServiceTransaction#undoSplit). */
    @PostMapping("/{id}/undo-split")
    public TransactionDTO undoSplit(@PathVariable Long id, @RequestBody DemandeAnnulationDivisionDTO request) {
        return transactionService.undoSplit(id, request);
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

    /** Annule une creation ou un import : suppression sans empreinte (voir ServiceTransaction#undoCreation). */
    @PostMapping("/undo-creation")
    public Map<String, Integer> undoCreation(@RequestBody List<Long> ids) {
        return Map.of("deleted", transactionService.undoCreation(ids));
    }

    /** Annule une suppression (voir ServiceRestaurationTransaction#restore). */
    @PostMapping("/restore")
    public List<TransactionDTO> restore(@RequestBody List<TransactionDTO> snapshots) {
        return restorationService.restore(snapshots);
    }

    /**
     * Supprime plusieurs transactions a la fois (ids fournis par la selection
     * multiple de l'onglet Transactions).
     */
    @DeleteMapping("/bulk")
    public Map<String, Integer> deleteBulk(@RequestBody List<Long> ids) {
        return Map.of("deleted", transactionService.deleteBulk(ids));
    }

    /**
     * Supprime les transactions en attente (non validees) : toutes, ou celles
     * du seul compte accountId (liste filtree par compte).
     */
    @DeleteMapping("/pending")
    public Map<String, Integer> deletePending(@RequestParam(required = false) Long accountId) {
        return Map.of("deleted", transactionService.deleteAllPending(accountId));
    }
}

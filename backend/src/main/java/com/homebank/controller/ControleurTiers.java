package com.homebank.controller;

import com.homebank.dto.ApercuFauxTiersDTO;
import com.homebank.dto.CategorisationAutoResultatDTO;
import com.homebank.dto.DemandeFusionDTO;
import com.homebank.dto.ResultatNettoyageTiersDTO;
import com.homebank.dto.TiersDTO;
import com.homebank.service.ServiceNettoyageTiers;
import com.homebank.service.ServiceTiers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payees")
public class ControleurTiers {

    private final ServiceTiers payeeService;
    private final ServiceNettoyageTiers cleanupService;

    public ControleurTiers(ServiceTiers payeeService, ServiceNettoyageTiers cleanupService) {
        this.payeeService = payeeService;
        this.cleanupService = cleanupService;
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

    /**
     * Categorise automatiquement chaque tiers sans categorie dont toutes les
     * transactions deja categorisees s'accordent sur une seule categorie, et
     * propage cette categorie a leurs transactions encore sans categorie.
     * Voir ServiceTiers#autoCategorize.
     */
    @PostMapping("/auto-categorize")
    public CategorisationAutoResultatDTO autoCategorize() {
        return payeeService.autoCategorize();
    }

    /** Apercu des faux tiers, sans rien modifier (voir ServiceNettoyageTiers). */
    @GetMapping("/computed-label-cleanup")
    public List<ApercuFauxTiersDTO> previewCleanup() {
        return cleanupService.preview();
    }

    @PostMapping("/computed-label-cleanup")
    public ResultatNettoyageTiersDTO cleanup(@RequestBody List<Long> payeeIds) {
        return cleanupService.clean(payeeIds);
    }

    @PostMapping("/computed-label-cleanup/undo")
    public ResponseEntity<Void> undoCleanup(@RequestBody List<ResultatNettoyageTiersDTO.TiersNettoye> cleaned) {
        cleanupService.undo(cleaned);
        return ResponseEntity.noContent().build();
    }
}

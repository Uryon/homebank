package com.homebank.controller;

import com.homebank.service.ServiceExport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/export")
public class ControleurExport {

    private final ServiceExport exportService;

    public ControleurExport(ServiceExport exportService) {
        this.exportService = exportService;
    }

    /**
     * Exporte le tableau de bord en PDF dans le dossier synchronise configure
     * (voir ServiceExport). Declenche manuellement (bouton "Exporter en PDF"
     * du tableau de bord) ou automatiquement apres validation en masse des
     * transactions (voir Transactions.vue#validateAll).
     */
    @PostMapping("/dashboard-pdf")
    public Map<String, String> exporterTableauDeBord() {
        exportService.exporterTableauDeBord();
        return Map.of("status", "ok");
    }
}

package com.homebank.controller;

import com.homebank.dto.DernierImportDTO;
import com.homebank.service.ServiceDernierImport;
import com.homebank.service.ServiceImport;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
public class ControleurImport {

    private final ServiceImport importService;
    private final ServiceDernierImport lastImportService;

    public ControleurImport(ServiceImport importService, ServiceDernierImport lastImportService) {
        this.importService = importService;
        this.lastImportService = lastImportService;
    }

    /** Date du dernier import reussi (dateTime null si aucun), affichee sur la page Import. */
    @GetMapping("/last")
    public DernierImportDTO lastImport() {
        return new DernierImportDTO(lastImportService.dateDernierImport());
    }

    @PostMapping("/csv")
    public ServiceImport.ImportResult importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountId") Long accountId) {
        return importService.importCsv(file, accountId);
    }

    @PostMapping("/qif")
    public ServiceImport.ImportResult importQif(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountId") Long accountId) {
        return importService.importQif(file, accountId);
    }
}

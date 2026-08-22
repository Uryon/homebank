package com.homebank.controller;

import com.homebank.service.ServiceImport;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
public class ControleurImport {

    private final ServiceImport importService;

    public ControleurImport(ServiceImport importService) {
        this.importService = importService;
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

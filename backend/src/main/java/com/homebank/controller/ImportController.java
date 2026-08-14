package com.homebank.controller;

import com.homebank.service.ImportService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/csv")
    public ImportService.ImportResult importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountId") Long accountId) {
        return importService.importCsv(file, accountId);
    }

    @PostMapping("/qif")
    public ImportService.ImportResult importQif(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountId") Long accountId) {
        return importService.importQif(file, accountId);
    }
}

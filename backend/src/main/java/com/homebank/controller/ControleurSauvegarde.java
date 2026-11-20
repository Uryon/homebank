package com.homebank.controller;

import com.homebank.dto.InfoSauvegardeDTO;
import com.homebank.dto.ResultatRestaurationDTO;
import com.homebank.dto.SauvegardeDTO;
import com.homebank.service.ServiceSauvegarde;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Sauvegarde complète des données et restauration (voir ServiceSauvegarde). */
@RestController
@RequestMapping("/api/sauvegarde")
public class ControleurSauvegarde {

    private final ServiceSauvegarde sauvegardeService;

    public ControleurSauvegarde(ServiceSauvegarde sauvegardeService) {
        this.sauvegardeService = sauvegardeService;
    }

    /** Toutes les données, en fichier JSON à enregistrer. */
    @GetMapping
    public ResponseEntity<SauvegardeDTO> telecharger() {
        String nom = "homebank-" + LocalDate.now(ZoneId.systemDefault()) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                .body(sauvegardeService.exporter());
    }

    /** Dossier des sauvegardes automatiques et date de la dernière. */
    @GetMapping("/info")
    public InfoSauvegardeDTO info() {
        return sauvegardeService.info();
    }

    /** Remplace toutes les données par celles du fichier envoyé. */
    @PostMapping("/restauration")
    public ResultatRestaurationDTO restaurer(@RequestParam("file") MultipartFile file) throws IOException {
        return sauvegardeService.restaurer(file.getBytes());
    }

    /** Annule une restauration en revenant aux données écrites juste avant (voir ResultatRestaurationDTO#sauvegardeAvant). */
    @PostMapping("/restauration/annulation")
    public ResultatRestaurationDTO annulerRestauration(@RequestBody Map<String, String> demande) {
        return sauvegardeService.restaurerFichier(demande.get("fichier"));
    }
}

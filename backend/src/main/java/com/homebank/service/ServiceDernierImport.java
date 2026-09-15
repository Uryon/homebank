package com.homebank.service;

import com.homebank.model.DernierImport;
import com.homebank.repository.DepotDernierImport;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Memorise la date du dernier import de fichier reussi (voir ServiceImport),
 * consultee par ServiceExport pour l'afficher dans l'export PDF du tableau
 * de bord a la place de la date de generation du PDF — plus utile pour juger
 * de la fraicheur des donnees, puisque l'export peut etre regenere (bouton
 * manuel, ou automatiquement) sans qu'un nouveau fichier ait ete importe.
 */
@Service
public class ServiceDernierImport {

    /** Une seule ligne en base : pas d'historique, seule la date la plus recente compte. */
    private static final Long ID_LIGNE_UNIQUE = 1L;

    private final DepotDernierImport repository;

    public ServiceDernierImport(DepotDernierImport repository) {
        this.repository = repository;
    }

    /** Enregistre l'instant present comme date du dernier import de fichier. */
    public void enregistrerImport() {
        DernierImport entite = repository.findById(ID_LIGNE_UNIQUE).orElseGet(DernierImport::new);
        entite.setId(ID_LIGNE_UNIQUE);
        entite.setDateHeure(LocalDateTime.now());
        repository.save(entite);
    }

    /** Null si aucun import n'a encore ete effectue. */
    public LocalDateTime dateDernierImport() {
        return repository.findById(ID_LIGNE_UNIQUE).map(DernierImport::getDateHeure).orElse(null);
    }
}

package com.homebank.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Ligne unique (id toujours ID_LIGNE_UNIQUE, voir ServiceDernierImport) qui
 * memorise la date/heure du dernier import de fichier (CSV/QIF) reussi, tous
 * comptes confondus. Pas d'historique complet : seule la date la plus
 * recente est utile, affichee dans l'export PDF du tableau de bord (voir
 * ServiceExport) a la place de la date de generation du PDF.
 */
@Entity
@Table(name = "dernier_import")
@Getter
@Setter
public class DernierImport {

    @Id
    private Long id;

    @Column(name = "date_heure", nullable = false)
    private LocalDateTime dateHeure;
}

package com.homebank.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ligne de releve deja importee sur un compte : date, montant et libelle tels
 * qu'ecrits par la banque. Une ligne de releve enregistree ici n'est plus
 * jamais reimportee (voir ServiceImport), quoi que soit devenue sa
 * transaction depuis : tiers ou categorie changes, divisee, date corrigee,
 * supprimee. Seule l'annulation de l'import qui l'a creee la retire du
 * registre (voir ServiceSuppressionTransaction#undoCreation).
 */
@Entity
@Table(name = "lignes_importees", indexes = {
        @Index(name = "idx_lignes_importees_compte", columnList = "account_id"),
        @Index(name = "idx_lignes_importees_transaction", columnList = "transaction_id")})
@Getter
@Setter
@NoArgsConstructor
public class LigneImportee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Libelle de la ligne du releve, brut (normalise a la comparaison, voir CleLigneImportee). */
    @Column(nullable = false, length = 500)
    private String label;

    /**
     * Transaction creee pour cette ligne, null si la ligne correspondait a une
     * transaction deja presente. Sans cle etrangere : la ligne reste
     * enregistree apres la suppression de sa transaction.
     */
    @Column(name = "transaction_id")
    private Long transactionId;

    public LigneImportee(Long accountId, LocalDate date, BigDecimal amount, String label, Long transactionId) {
        this.accountId = accountId;
        this.date = date;
        this.amount = amount;
        this.label = label;
        this.transactionId = transactionId;
    }
}

package com.homebank.model;

import com.homebank.utils.ModePaiement;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@ToString
@Table(name = "transactions")
@Getter
@Setter
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Compte account;

    @NotNull
    @Column(nullable = false)
    private LocalDate date;

    /** Positif = revenu, negatif = depense (comme dans HomeBank) */
    @NotNull
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /**
     * Tiers de la transaction : vraie relation (et non plus un texte
     * denormalise recopie sur chaque transaction), pour que le nom affiche
     * soit toujours lu en direct depuis Tiers#name. Un renommage ou une
     * fusion de tiers (voir ServiceTiers) se repercute donc automatiquement
     * sur toutes les transactions liees, sans etape de synchronisation a
     * part qui pourrait etre oubliee. Null pour une transaction sans tiers
     * (ex : virement interne, dont le libelle est recalcule a l'affichage).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payee_id")
    private Tiers payee;

    private String memo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Categorie category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModePaiement paymentMode = ModePaiement.NONE;

    /** Tags libres separes par des espaces, comme dans HomeBank */
    private String tags;

    /** Pour les virements internes : transaction correspondante sur l'autre compte */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_transaction_id")
    private Transaction transferTransaction;

    /**
     * Statut de rapprochement : false = "en attente" (import, ajout manuel ou
     * future synchronisation bancaire), true = "validee" par l'utilisateur
     * apres verification. Sert de base a la fonctionnalite de rapprochement
     * bancaire (section "Transactions en attente de validation").
     */
    @Column(nullable = false)
    private boolean validated = false;

    /**
     * Coche par l'utilisateur (ou acceptee depuis une suggestion automatique,
     * voir ServiceTransaction#findPeriodicSuggestions) pour indiquer une
     * depense/revenu periodique recurrent (loyer, abonnements, assurances,
     * salaire...), utilise pour l'encart "Depenses periodiques" du tableau de
     * bord. Le nom de colonne reste "monthly_expense" en base (ancien nom) :
     * seul le concept applicatif a ete renomme, pour eviter toute migration
     * de schema sur les donnees existantes.
     */
    @Column(name = "monthly_expense", nullable = false)
    private boolean periodicExpense = false;

    /**
     * Identifiant unique fourni par la banque ou le fichier importe (si
     * disponible), utilise en priorite pour la detection de doublons lors
     * des imports CSV/QIF ou d'une future synchronisation bancaire.
     */
    @Column(name = "external_id")
    private String externalId;
}

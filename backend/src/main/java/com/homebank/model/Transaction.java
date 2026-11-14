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


    /** Pour les virements internes : transaction correspondante sur l'autre compte */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_transaction_id")
    private Transaction transferTransaction;

    /**
     * Vrai pour un cote de virement INTERNE entre deux comptes de l'appli
     * (voir ServiceTransaction#createTransfer) : c'est la liaison avec l'autre
     * cote qui le caracterise, pas le mode de paiement. « Virement » reste un
     * mode de paiement ordinaire (loyer, salaire...), compte normalement dans
     * les rapports. Le test de nullite ne charge pas la jumelle (proxy LAZY).
     */
    public boolean isInternalTransfer() {
        return transferTransaction != null;
    }

    /**
     * Montant de la transaction AVANT sa premiere division (voir
     * ServiceTransaction#splitTransaction), null si elle n'a jamais ete
     * divisee. Le releve bancaire, lui, contient toujours le montant d'origine :
     * sans ce souvenir, la detection de doublons de l'import (voir
     * importKeyAmount) ne reconnaissait plus la ligne apres division, et le
     * prochain import qui chevauchait le releve la reimportait en entier.
     */
    @Column(name = "split_from_amount", precision = 19, scale = 2)
    private BigDecimal splitFromAmount;

    /** Montant tel qu'il figure sur le releve : celui d'avant division le cas echeant (voir splitFromAmount). */
    public BigDecimal importKeyAmount() {
        return splitFromAmount != null ? splitFromAmount : amount;
    }

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
     * Coche pour une transaction exceptionnelle (ex : achat d'une voiture,
     * gros heritage...) dont le montant fausserait les moyennes/tendances des
     * tableaux de bord si elle etait comptee comme une depense/revenu normal.
     * Exclue de tous les rapports bases sur attributedTransactions (voir
     * ServiceRapport) ; n'affecte jamais le solde reel des comptes, qui reste
     * base sur la date et le montant reels.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean exceptional = false;

    /**
     * Libelle brut de la ligne du releve, enregistre a l'import (null pour une
     * saisie manuelle) : marque la transaction comme importee, et sert a la
     * reconnaitre aux imports suivants meme si l'utilisateur a change son
     * tiers depuis (voir ServiceImport#loadDuplicateCounter). Affiche en
     * info-bulle sur le tiers.
     */
    @Column(name = "bank_label", length = 500)
    private String bankLabel;

    /**
     * Coche par l'utilisateur depuis l'onglet "Doublons" pour indiquer qu'un
     * groupe remonte par ServiceTransaction#findDuplicateGroups n'est en
     * realite pas un doublon (ex : deux achats distincts, memes compte/date/
     * montant par coincidence). Exclue ensuite de toute detection de doublon
     * (voir ServiceTransaction#findDuplicateGroups), qu'elle soit "certaine"
     * ou "probable".
     */
    @Column(name = "duplicate_ignored", nullable = false, columnDefinition = "boolean not null default false")
    private boolean duplicateIgnored = false;

    /**
     * Coche par l'utilisateur depuis l'onglet "Périodiques" ("Ignorer" sur
     * une suggestion, voir ServiceTransaction#findPeriodicSuggestions) pour
     * indiquer que cette transaction ne doit plus jamais etre proposee comme
     * periodique. Sans ce champ, "Ignorer" n'etait qu'un filtre local a la
     * session (un simple Set en memoire cote frontend, voir
     * TransactionsPeriodicSuggestions.vue) : la meme suggestion revenait a
     * chaque nouvelle visite de l'onglet, puisque le backend la recalculait
     * sans rien savoir d'un refus precedent.
     */
    @Column(name = "periodic_suggestion_ignored", nullable = false, columnDefinition = "boolean not null default false")
    private boolean periodicSuggestionIgnored = false;

    /**
     * Ajustement de solde (voir ServiceTransaction#adjustAccountBalance) :
     * ecart entre la valeur reelle d'un compte saisie par l'utilisateur (ex :
     * placement dont la valeur suit la bourse) et le solde calcule. Compte
     * dans le solde, mais ce n'est ni une depense ni un revenu : exclu des
     * rapports, des suggestions periodiques et des doublons, et ne demande
     * jamais de categorie.
     */
    @Column(name = "balance_adjustment", nullable = false, columnDefinition = "boolean not null default false")
    private boolean balanceAdjustment = false;
}

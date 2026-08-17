package com.homebank.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "payees")
@Getter
@Setter
public class Payee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom canonique du tiers, ex : "Carrefour" ou "Bouygues Mel" */
    @NotBlank
    @Column(nullable = false)
    private String name;

    /**
     * Motif teste (insensible a la casse) sur le champ "payee" des
     * transactions importees. Stocke et affiche tel quel, SANS etoile
     * ("Carrefour") : les etoiles de "contient ce texte" ne sont ajoutees
     * qu'au moment du traitement, jamais dans les donnees. Plusieurs motifs
     * peuvent etre combines avec ";" (OR), et "*" reste utilisable comme
     * joker pour un motif avance (ex : "Carrefour*Paris"). Voir PayeeService
     * pour la logique complete.
     */
    @NotBlank
    @Column(nullable = false)
    private String pattern;

    /**
     * Montant de reference (valeur absolue) associe a ce tiers, optionnel.
     * Permet de distinguer deux tiers portant le meme texte brut mais
     * correspondant a des montants differents (ex : "Bouygues" a 10E vs 20E
     * -> deux tiers distincts, renommables independamment).
     * Si null, ce tiers est un tiers "generique" qui matche quel que soit
     * le montant.
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    /** Categorie appliquee automatiquement quand la regexp (et le montant) correspondent */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;
}

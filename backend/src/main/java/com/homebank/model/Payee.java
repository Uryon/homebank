package com.homebank.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payees")
@Getter
@Setter
public class Payee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom canonique du tiers, ex : "Carrefour" */
    @NotBlank
    @Column(nullable = false)
    private String name;

    /**
     * Expression reguliere testee (insensible a la casse) sur le champ
     * "payee" des transactions importees. Ex : "(?i)carrefour|market"
     */
    @NotBlank
    @Column(nullable = false)
    private String pattern;

    /** Categorie appliquee automatiquement quand la regexp correspond */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;
}

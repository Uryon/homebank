package com.homebank.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "categories")
@Getter
@Setter
public class Categorie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    /** Categorie parente (null = categorie racine) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @JsonIgnoreProperties({"children", "parent"})
    private Categorie parent;

    @JsonIgnore
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Categorie> children = new ArrayList<>();

    /** Type indicatif : INCOME, EXPENSE ou BOTH */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeCategorie type = TypeCategorie.EXPENSE;

    /** Categorie masquee (plus utilisee) : exclue des autocompletes, mais conservee pour l'historique */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean hidden = false;

    public enum TypeCategorie {
        INCOME, EXPENSE, BOTH
    }
}

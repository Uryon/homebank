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

    /** Emoji affiche devant le nom de la categorie (frontend), optionnel */
    private String icon;

    /** Categorie masquee (plus utilisee) : exclue des autocompletes, mais conservee pour l'historique */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean hidden = false;

    /**
     * Categorie "annuelle" : toute transaction qui lui est affectee est
     * automatiquement lissee sur les 12 mois suivant (et incluant) son mois
     * de paiement dans les rapports (voir ServiceRapport#attributedTransactions),
     * plutot que comptee en une fois sur ce seul mois — ex : taxe fonciere,
     * assurance annuelle. Remplace l'ancien marquage par transaction
     * (Transaction#annualized, supprime) : la granularite est desormais la
     * categorie entiere, pas transaction par transaction. N'affecte jamais le
     * solde reel des comptes, qui reste base sur la date et le montant reels.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean annualized = false;

    public enum TypeCategorie {
        INCOME, EXPENSE, BOTH
    }
}

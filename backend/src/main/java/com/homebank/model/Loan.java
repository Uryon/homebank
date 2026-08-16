package com.homebank.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pret rembourse par mensualites constantes (immobilier, conso...).
 * L'echeancier (interets / capital / capital restant du mois par mois)
 * n'est pas stocke : il est recalcule a la volee a partir de ces
 * parametres, comme un tableau d'amortissement bancaire classique.
 */
@Entity
@Table(name = "loans")
@Getter
@Setter
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    /** Capital emprunte au depart. */
    @NotNull
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    /** Taux annuel en pourcentage, ex. 3.250 pour 3,25 %. */
    @NotNull
    @Column(name = "annual_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal annualRate;

    /** Duree totale du pret en mois. */
    @NotNull
    @Column(name = "term_months", nullable = false)
    private Integer termMonths;

    /** Date de la premiere mensualite. */
    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Mensualite (hors assurance eventuelle), telle qu'indiquee au contrat. */
    @NotNull
    @Column(name = "monthly_payment", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyPayment;

    /** Assurance emprunteur mensuelle (facultative), informative uniquement. */
    @Column(name = "monthly_insurance", precision = 19, scale = 2)
    private BigDecimal monthlyInsurance;

    private String notes;
}

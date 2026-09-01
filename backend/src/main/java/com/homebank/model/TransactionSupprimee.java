package com.homebank.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Empreinte d'une transaction supprimee manuellement (compte, date, montant,
 * tiers, identifiant externe), conservee apres suppression pour que
 * ServiceImport#isDuplicate la reconnaisse et ne la reimporte pas si le
 * releve bancaire source la contient encore lors d'un futur import (voir
 * ServiceTransaction#delete). Sans cette trace, une transaction supprimee
 * (ex : paire "depense remboursee" totalement effacee, voir
 * ServiceTransaction#findReimbursementGroups) disparaissait bien de la base,
 * mais reapparaissait au prochain import faute d'un doublon deja en base
 * pour la reconnaitre.
 * <p>
 * accountId est un simple identifiant (pas de relation JPA vers Compte) :
 * cette table n'est qu'un journal de reference pour la deduplication a
 * l'import, elle n'a pas besoin de suivre le cycle de vie du compte (pas de
 * cascade de suppression a gerer si le compte est supprime).
 */
@Entity
@Table(name = "transactions_supprimees")
@Getter
@Setter
public class TransactionSupprimee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Nom du tiers au moment de la suppression (brut, normalise a la comparaison via CleDoublon). */
    @Column(name = "payee_name")
    private String payeeName;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "deleted_at", nullable = false)
    private LocalDate deletedAt = LocalDate.now();
}

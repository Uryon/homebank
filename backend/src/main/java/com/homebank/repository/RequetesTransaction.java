package com.homebank.repository;

/**
 * Fragments JPQL communs aux requetes de DepotTransaction, ecrits une seule
 * fois : une jointure ajoutee a une copie mais oubliee dans une autre
 * reintroduisait un N+1 sur cette seule requete.
 */
final class RequetesTransaction {

    /**
     * Transaction avec tiers, categorie et virement correspondant (et son
     * compte) deja charges : MappeurTransaction#payeeLabel lit le nom du tiers
     * (ou, pour un virement, le compte de la transaction correspondante) de
     * CHAQUE transaction — sans ces fetch joins, chaque tiers/virement distinct
     * declencherait sa propre requete au premier acces (LAZY).
     */
    static final String SELECT_FOR_DISPLAY = "select t from Transaction t"
            + " left join fetch t.payee left join fetch t.category"
            + " left join fetch t.transferTransaction tt left join fetch tt.account";

    /** Comme SELECT_FOR_DISPLAY, plus la categorie parente (alias c), lue par les rapports pour regrouper par categorie racine. */
    static final String SELECT_FOR_REPORTS = "select t from Transaction t"
            + " left join fetch t.payee left join fetch t.category c left join fetch c.parent"
            + " left join fetch t.transferTransaction tt left join fetch tt.account";

    /** Ordre d'affichage de la liste des transactions : plus recentes d'abord, ordre stable a date egale. */
    static final String NEWEST_FIRST = " order by t.date desc, t.id desc";

    private RequetesTransaction() {
    }
}

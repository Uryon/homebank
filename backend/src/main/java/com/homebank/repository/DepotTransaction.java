package com.homebank.repository;

import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DepotTransaction extends JpaRepository<Transaction, Long> {

    List<Transaction> findByAccount_IdOrderByDateDescIdDesc(Long accountId);

    List<Transaction> findAllByOrderByDateDescIdDesc();

    List<Transaction> findByDateBetween(LocalDate from, LocalDate to);

    List<Transaction> findByAccountIdAndDateBetween(Long accountId, LocalDate from, LocalDate to);

    /** Recherche de transactions par montant (operateur > < =), utilisee par la recherche de l'onglet Transactions. */
    List<Transaction> findByAmountGreaterThanOrderByDateDescIdDesc(BigDecimal amount);

    List<Transaction> findByAmountLessThanOrderByDateDescIdDesc(BigDecimal amount);

    List<Transaction> findByAmountOrderByDateDescIdDesc(BigDecimal amount);

    List<Transaction> findByAccount_IdAndAmountGreaterThanOrderByDateDescIdDesc(Long accountId, BigDecimal amount);

    List<Transaction> findByAccount_IdAndAmountLessThanOrderByDateDescIdDesc(Long accountId, BigDecimal amount);

    List<Transaction> findByAccount_IdAndAmountOrderByDateDescIdDesc(Long accountId, BigDecimal amount);

    List<Transaction> findByPeriodicExpenseTrueOrderByDateDescIdDesc();

    /** Transactions en attente de validation (rapprochement bancaire), pour la suppression en masse. */
    List<Transaction> findByValidatedFalse();

    /**
     * Transactions dont la categorie est marquee "annuelle" (lissees sur 12
     * mois dans les rapports, voir Categorie#annualized /
     * ServiceRapport#attributedTransactions). Chargees a part de
     * findByDateBetween : une transaction annuelle doit compter dans un
     * rapport meme sur un mois different de sa date reelle de paiement, tant
     * que ce mois appartient a la fenetre glissante de 12 mois qui demarre a
     * son propre mois de paiement.
     */
    List<Transaction> findByCategory_AnnualizedTrue();

    /**
     * Toutes les transactions, tiers et categorie deja charges (fetch join) :
     * utilise par la categorisation automatique en masse (voir
     * ServiceTiers#autoCategorize), qui doit lire le tiers et la categorie de
     * CHAQUE transaction pour les regrouper par tiers — un simple findAll()
     * les chargerait un par un a l'acces (LAZY), soit potentiellement des
     * milliers de requetes N+1.
     */
    @Query("select t from Transaction t left join fetch t.payee left join fetch t.category")
    List<Transaction> findAllWithPayeeAndCategory();

    @Query("select coalesce(sum(t.amount), 0) from Transaction t where t.account.id = :accountId and t.date <= :asOf")
    BigDecimal sumAmountByAccountIdAsOf(@Param("accountId") Long accountId, @Param("asOf") LocalDate asOf);

    /**
     * Vrai si au moins une transaction est encore liee a ce tiers. Utilise
     * pour detecter un tiers auto-cree devenu orphelin (voir
     * ServiceTiers#pruneIfOrphan) sans avoir a charger les transactions.
     */
    boolean existsByPayee(Tiers payee);

    /**
     * Reaffecte en masse toutes les transactions d'un tiers vers un autre.
     * Utilise lors de la fusion de deux tiers : la relation etant une vraie
     * cle etrangere (Transaction#payee), un renommage simple du tiers cible
     * se repercute deja automatiquement sans avoir besoin de toucher aux
     * transactions elles-memes.
     */
    @Modifying
    @Query("update Transaction t set t.payee = :target where t.payee = :source")
    void reassignPayee(@Param("source") Tiers source, @Param("target") Tiers target);

    /**
     * Reaffecte en masse toutes les transactions d'une categorie vers une autre.
     * Utilise lors de la fusion de deux categories.
     */
    @Modifying
    @Query("update Transaction t set t.category = :target where t.category = :source")
    void reassignCategory(@Param("source") Categorie source, @Param("target") Categorie target);

    /** Passe toutes les transactions en attente au statut "validee". */
    @Modifying
    @Query("update Transaction t set t.validated = true where t.validated = false")
    int validateAllPending();

    /**
     * Recherche une transaction correspondant a un identifiant externe donne
     * (fourni par la banque ou le fichier importe), utilisee en priorite pour
     * la detection de doublons.
     */
    Optional<Transaction> findByAccountIdAndExternalId(Long accountId, String externalId);

    /**
     * Vrai si l'ancienne colonne texte "transactions.payee" existe encore en
     * base (schema pas encore passe a la relation payee_id, ou schema fraichement
     * cree par Hibernate sans elle, ex : H2 en profil "test"). Verifie avant
     * findLegacyPayeeTextForMigration(), qui echouerait sinon sur un schema
     * qui n'a jamais connu cette colonne.
     */
    @Query(value = """
            select count(*) from information_schema.columns
            where lower(table_name) = 'transactions' and lower(column_name) = 'payee'
            """, nativeQuery = true)
    long countLegacyPayeeColumn();

    /**
     * Lecture "brute" (SQL natif, colonne texte "payee" desormais orpheline)
     * des transactions pas encore reliees a un tiers via payee_id, pour la
     * migration unique vers la relation Transaction#payee (voir
     * config.PayeeRelationMigration). Necessaire car cette ancienne colonne
     * n'est plus mappee par l'entite JPA une fois la relation en place.
     * Exclut les virements, qui n'ont jamais ete un vrai tiers (voir
     * MappeurTransaction#payeeLabel : leur libelle est recalcule a l'affichage).
     * N'appeler que si countLegacyPayeeColumn() > 0.
     */
    @Query(value = """
            select id, payee from transactions
            where payee_id is null and payee is not null and trim(payee) <> ''
              and payment_mode <> 'TRANSFER'
            """, nativeQuery = true)
    List<Object[]> findLegacyPayeeTextForMigration();

    /**
     * Vrai si l'ancienne colonne "transactions.annualized" (marquage par
     * transaction, remplace par Categorie#annualized) existe encore en base —
     * absente sur un schema fraichement cree par Hibernate une fois l'entite
     * Transaction#annualized supprimee. Verifiee avant
     * findCategoryIdsWithLegacyAnnualizedTransactions()/clearLegacyAnnualizedColumn(),
     * qui echoueraient sinon sur un schema qui n'a jamais connu cette colonne
     * (meme raison que countLegacyPayeeColumn ci-dessus).
     */
    @Query(value = """
            select count(*) from information_schema.columns
            where lower(table_name) = 'transactions' and lower(column_name) = 'annualized'
            """, nativeQuery = true)
    long countLegacyAnnualizedColumn();

    /**
     * Categories ayant au moins une transaction marquee "annualized" dans
     * l'ancienne colonne (SQL natif, plus mappee par l'entite JPA), pour la
     * migration unique vers Categorie#annualized (voir
     * config.CategoryAnnualizedMigration). N'appeler que si
     * countLegacyAnnualizedColumn() > 0.
     */
    @Query(value = """
            select distinct category_id from transactions
            where annualized = true and category_id is not null
            """, nativeQuery = true)
    List<Long> findCategoryIdsWithLegacyAnnualizedTransactions();

    /**
     * Neutralise l'ancienne colonne "annualized" une fois la migration vers
     * Categorie#annualized effectuee (voir config.CategoryAnnualizedMigration),
     * pour que la migration reste un evenement unique : sans ca, elle
     * re-forcerait "annuelle" sur une categorie que l'utilisateur aurait
     * ensuite volontairement decochee, a chaque redemarrage.
     */
    @Modifying
    @Query(value = "update transactions set annualized = false where annualized = true", nativeQuery = true)
    void clearLegacyAnnualizedColumn();

}

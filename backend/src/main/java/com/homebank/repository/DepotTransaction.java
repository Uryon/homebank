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
import java.util.Collection;
import java.util.List;

import static com.homebank.repository.RequetesTransaction.NEWEST_FIRST;
import static com.homebank.repository.RequetesTransaction.SELECT_FOR_DISPLAY;
import static com.homebank.repository.RequetesTransaction.SELECT_FOR_REPORTS;

public interface DepotTransaction extends JpaRepository<Transaction, Long> {

    long countByAccount_Id(Long accountId);

    /** Fetch joins de SELECT_FOR_DISPLAY : voir RequetesTransaction (evite un N+1 par tiers/virement). */
    @Query(SELECT_FOR_DISPLAY + " where t.account.id = :accountId" + NEWEST_FIRST)
    List<Transaction> findByAccount_IdOrderByDateDescIdDesc(@Param("accountId") Long accountId);

    @Query(SELECT_FOR_DISPLAY + NEWEST_FIRST)
    List<Transaction> findAllByOrderByDateDescIdDesc();

    /**
     * Tiers, categorie (et son parent) et virement correspondant deja charges
     * (fetch join) : meme raison que findAllByOrderByDateDescIdDesc ci-dessus
     * (utilisee via ServiceTransaction#findByPeriod, qui mappe chaque resultat
     * vers un DTO, et par les rapports de ServiceRapport, qui lisent aussi la
     * categorie parente pour regrouper/libeller par categorie racine).
     */
    @Query(SELECT_FOR_REPORTS + " where t.date between :from and :to")
    List<Transaction> findByDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Variante de findByDateBetween filtree sur un compte (tableau de bord
     * filtre par compte), avec les memes fetch joins : sans eux, chaque
     * categorie/tiers distinct declencherait sa propre requete (N+1) dans
     * ServiceRapport.
     */
    @Query(SELECT_FOR_REPORTS + " where t.account.id = :accountId and t.date between :from and :to")
    List<Transaction> findByAccountIdAndDateBetween(@Param("accountId") Long accountId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    /**
     * Recherche de transactions par montant (operateur > < =), utilisee par
     * la recherche de l'onglet Transactions. Tiers, categorie et virement
     * correspondant deja charges (fetch join), meme raison que
     * findAllByOrderByDateDescIdDesc ci-dessus.
     */
    @Query(SELECT_FOR_DISPLAY + " where t.amount > :amount" + NEWEST_FIRST)
    List<Transaction> findByAmountGreaterThanOrderByDateDescIdDesc(@Param("amount") BigDecimal amount);

    @Query(SELECT_FOR_DISPLAY + " where t.amount < :amount" + NEWEST_FIRST)
    List<Transaction> findByAmountLessThanOrderByDateDescIdDesc(@Param("amount") BigDecimal amount);

    @Query(SELECT_FOR_DISPLAY + " where t.amount = :amount" + NEWEST_FIRST)
    List<Transaction> findByAmountOrderByDateDescIdDesc(@Param("amount") BigDecimal amount);

    @Query(SELECT_FOR_DISPLAY + " where t.account.id = :accountId and t.amount > :amount" + NEWEST_FIRST)
    List<Transaction> findByAccount_IdAndAmountGreaterThanOrderByDateDescIdDesc(@Param("accountId") Long accountId, @Param("amount") BigDecimal amount);

    @Query(SELECT_FOR_DISPLAY + " where t.account.id = :accountId and t.amount < :amount" + NEWEST_FIRST)
    List<Transaction> findByAccount_IdAndAmountLessThanOrderByDateDescIdDesc(@Param("accountId") Long accountId, @Param("amount") BigDecimal amount);

    @Query(SELECT_FOR_DISPLAY + " where t.account.id = :accountId and t.amount = :amount" + NEWEST_FIRST)
    List<Transaction> findByAccount_IdAndAmountOrderByDateDescIdDesc(@Param("accountId") Long accountId, @Param("amount") BigDecimal amount);

    /**
     * Transactions demandees, avec leur eventuelle jumelle de virement deja
     * chargee (fetch join) : utilisee partout ou le code verifie ensuite
     * getTransferTransaction() sur chaque transaction (suppression/modification
     * en masse, voir ServiceTransaction#unlinkTransferPairs et
     * #applyBulkPaymentMode) — sans ce fetch join, chaque transaction
     * declencherait sa propre requete au premier acces (LAZY), un N+1 sur une
     * selection multiple.
     */
    @Query("select t from Transaction t left join fetch t.transferTransaction where t.id in :ids")
    List<Transaction> findAllByIdWithTransferTransaction(@Param("ids") List<Long> ids);

    /**
     * Transactions marquees "periodique", tiers deja charge (fetch join) :
     * ServiceRapport#periodicExpensesSummary lit le nom du tiers de chacune
     * pour les regrouper (N+1 sinon).
     */
    @Query("select t from Transaction t left join fetch t.payee"
            + " where t.periodicExpense = true" + NEWEST_FIRST)
    List<Transaction> findByPeriodicExpenseTrueOrderByDateDescIdDesc();

    /**
     * Transactions en attente de validation (rapprochement bancaire), pour la
     * suppression en masse, avec leur eventuelle jumelle de virement deja
     * chargee (fetch join) : ServiceTransaction#deleteAllPending verifie le
     * statut valide de cette jumelle pour chacune (voir sa javadoc), sans quoi
     * chaque transaction en attente declencherait sa propre requete au
     * premier acces (LAZY).
     */
    @Query("select t from Transaction t left join fetch t.transferTransaction where t.validated = false")
    List<Transaction> findByValidatedFalseWithTransferTransaction();

    /**
     * Transactions dont la categorie est marquee "annuelle" (lissees sur 12
     * mois dans les rapports, voir Categorie#annualized /
     * ServiceRapport#attributedTransactions). Chargees a part de
     * findByDateBetween : une transaction annuelle doit compter dans un
     * rapport meme sur un mois different de sa date reelle de paiement, tant
     * que ce mois appartient a la fenetre glissante de 12 mois qui demarre a
     * son propre mois de paiement.
     * Tiers et categorie (et son parent) deja charges (fetch join) : lus par
     * ServiceRapport pour chaque tranche mensuelle (N+1 sinon).
     */
    @Query("select t from Transaction t"
            + " join fetch t.category c left join fetch c.parent left join fetch t.payee"
            + " where c.annualized = true")
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
     * Somme des montants survenus jusqu'a `asOf`, pour tous les comptes en une
     * seule requete (voir ServiceCompte#allBalances) : lignes [accountId, somme].
     * Un compte sans transaction n'a pas de ligne.
     */
    @Query("select t.account.id, sum(t.amount) from Transaction t where t.date <= :asOf group by t.account.id")
    List<Object[]> sumAmountGroupedByAccountIdAsOf(@Param("asOf") LocalDate asOf);

    /** Somme des montants par jour sur [from, to], tous comptes (voir ServiceRapport#monthlyMovements) : lignes [date, somme]. */
    @Query("select t.date, sum(t.amount) from Transaction t where t.date between :from and :to group by t.date")
    List<Object[]> sumAmountGroupedByDate(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Idem pour un seul compte. */
    @Query("select t.date, sum(t.amount) from Transaction t"
            + " where t.account.id = :accountId and t.date between :from and :to group by t.date")
    List<Object[]> sumAmountByAccountIdGroupedByDate(@Param("accountId") Long accountId,
                                                     @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Vrai si au moins une transaction est encore liee a ce tiers. Utilise
     * pour detecter un tiers auto-cree devenu orphelin (voir
     * ServiceTiers#pruneIfOrphan) sans avoir a charger les transactions.
     */
    boolean existsByPayee(Tiers payee);

    /** Nombre de transactions d'un tiers, pour expliquer un refus de suppression (voir ServiceTiers#delete). */
    long countByPayee(Tiers payee);

    /** Nombre de transactions rattachees a l'une de ces categories, pour expliquer un refus de suppression (voir ServiceCategorie#delete). */
    long countByCategoryIn(Collection<Categorie> categories);

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

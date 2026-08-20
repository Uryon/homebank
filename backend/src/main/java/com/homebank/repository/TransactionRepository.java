package com.homebank.repository;

import com.homebank.model.Category;
import com.homebank.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

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

    @Query("select coalesce(sum(t.amount), 0) from Transaction t where t.account.id = :accountId and t.date <= :asOf")
    BigDecimal sumAmountByAccountIdAsOf(@Param("accountId") Long accountId, @Param("asOf") LocalDate asOf);

    /**
     * Renvoie chaque paire (texte du tiers, montant) presente dans les
     * transactions existantes. Utilise pour l'initialisation des tiers,
     * qui distingue desormais les tiers par nom ET par montant.
     */
    @Query("select t.payee, t.amount from Transaction t where t.payee is not null and t.payee <> ''")
    List<Object[]> findAllPayeeAmountPairs();

    /**
     * Sous-ensemble de findAllPayeeAmountPairs(), filtre sur les transactions
     * dont le tiers contient (insensible a la casse) le texte donne. Utilise
     * pour verifier l'orphelinage d'UN tiers auto-cree (voir
     * PayeeService#pruneIfOrphan) sans charger toutes les transactions de la
     * base a chaque edition/suppression de transaction.
     */
    @Query("select t.payee, t.amount from Transaction t where t.payee is not null and lower(t.payee) like lower(concat('%', :text, '%'))")
    List<Object[]> findPayeeAmountPairsContaining(@Param("text") String text);

    /**
     * Renomme en masse toutes les transactions dont le tiers correspond
     * exactement (insensible a la casse) a oldName, vers newName.
     * Utilise lors de la fusion de deux tiers.
     */
    @Modifying
    @Query("update Transaction t set t.payee = :newName where lower(t.payee) = lower(:oldName)")
    void renamePayeeText(@Param("oldName") String oldName, @Param("newName") String newName);

    /**
     * Reaffecte en masse toutes les transactions d'une categorie vers une autre.
     * Utilise lors de la fusion de deux categories.
     */
    @Modifying
    @Query("update Transaction t set t.category = :target where t.category = :source")
    void reassignCategory(@Param("source") Category source, @Param("target") Category target);

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
     * Recherche une transaction "manifestement identique" sur un meme compte :
     * meme date, meme montant, meme tiers (insensible a la casse) et meme
     * mode de paiement. Utilise comme repli pour la detection de doublons
     * quand aucun identifiant externe n'est disponible.
     */
    @Query("""
            select t from Transaction t
            where t.account.id = :accountId
              and t.date = :date
              and t.amount = :amount
              and lower(coalesce(t.payee, '')) = lower(coalesce(:payee, ''))
            """)
    List<Transaction> findPossibleDuplicates(@Param("accountId") Long accountId,
                                              @Param("date") LocalDate date,
                                              @Param("amount") BigDecimal amount,
                                              @Param("payee") String payee);


    /**
     * Agrégation mensuelle par catégorie pour les rapports financiers.
     * Le regroupement est effectué en base afin d'éviter de charger toutes les
     * transactions côté application pour construire le tableau des 12 derniers mois.
     */
    @Query(value = """
            SELECT c.id, c.name, p.id, p.name, c.type,
                   EXTRACT(YEAR FROM t.date)::integer,
                   EXTRACT(MONTH FROM t.date)::integer,
                   SUM(t.amount)
            FROM transactions t
            LEFT JOIN categories c ON t.category_id = c.id
            LEFT JOIN categories p ON c.parent_id = p.id
            WHERE t.date BETWEEN :from AND :to
              AND t.payment_mode <> 'TRANSFER'
              AND (:accountId IS NULL OR t.account_id = :accountId)
            GROUP BY c.id, c.name, p.id, p.name, c.type,
                     EXTRACT(YEAR FROM t.date), EXTRACT(MONTH FROM t.date)
            ORDER BY p.name ASC, c.name ASC,
                     EXTRACT(YEAR FROM t.date) ASC, EXTRACT(MONTH FROM t.date) ASC
            """, nativeQuery = true)
    List<Object[]> aggregateMonthlyByCategory(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("accountId") Long accountId);

}

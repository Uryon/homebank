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

    List<Transaction> findByAccount_IdOrderByDateDesc(Long accountId);

    List<Transaction> findAllByOrderByDateDesc();

    List<Transaction> findByDateBetween(LocalDate from, LocalDate to);

    List<Transaction> findByAccountIdAndDateBetween(Long accountId, LocalDate from, LocalDate to);

    List<Transaction> findByValidatedFalseOrderByDateDesc();

    List<Transaction> findByMonthlyExpenseTrueOrderByDateDesc();

    long countByValidatedFalse();

    @Query("select coalesce(sum(t.amount), 0) from Transaction t where t.account.id = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);

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
}

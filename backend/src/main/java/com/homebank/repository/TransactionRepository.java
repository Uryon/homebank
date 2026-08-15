package com.homebank.repository;

import com.homebank.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByAccount_IdOrderByDateDesc(Long accountId);

    List<Transaction> findAllByOrderByDateDesc();

    List<Transaction> findByDateBetween(LocalDate from, LocalDate to);

    List<Transaction> findByAccountIdAndDateBetween(Long accountId, LocalDate from, LocalDate to);

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
}

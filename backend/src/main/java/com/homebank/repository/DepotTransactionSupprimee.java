package com.homebank.repository;

import com.homebank.model.TransactionSupprimee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepotTransactionSupprimee extends JpaRepository<TransactionSupprimee, Long> {

    List<TransactionSupprimee> findByAccountId(Long accountId);

    /**
     * Utilise en priorite par ServiceImport#isDuplicate, comme
     * DepotTransaction#findByAccountIdAndExternalId pour les transactions
     * encore en base.
     */
    Optional<TransactionSupprimee> findByAccountIdAndExternalId(Long accountId, String externalId);
}

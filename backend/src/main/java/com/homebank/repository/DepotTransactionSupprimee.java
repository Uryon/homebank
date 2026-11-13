package com.homebank.repository;

import com.homebank.model.TransactionSupprimee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepotTransactionSupprimee extends JpaRepository<TransactionSupprimee, Long> {

    List<TransactionSupprimee> findByAccountId(Long accountId);
}

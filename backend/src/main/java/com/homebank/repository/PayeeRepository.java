package com.homebank.repository;

import com.homebank.model.Payee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.Optional;

public interface PayeeRepository extends JpaRepository<Payee, Long> {
    Optional<Payee> findByNameIgnoreCase(String name);
    Optional<Payee> findByNameIgnoreCaseAndAmount(String name, BigDecimal amount);
}

package com.homebank.repository;

import com.homebank.model.Payee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayeeRepository extends JpaRepository<Payee, Long> {
    Optional<Payee> findByNameIgnoreCase(String name);
}

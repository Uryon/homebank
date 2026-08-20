package com.homebank.repository;

import com.homebank.model.Category;
import com.homebank.model.Payee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PayeeRepository extends JpaRepository<Payee, Long> {
    Optional<Payee> findByNameIgnoreCase(String name);

    /**
     * Reaffecte en masse la categorie par defaut des tiers qui pointaient
     * vers "source" pour qu'ils pointent desormais vers "target".
     * Utilise lors de la fusion de deux categories.
     */
    @Modifying
    @Query("update Payee p set p.category = :target where p.category = :source")
    void reassignCategory(@Param("source") Category source, @Param("target") Category target);
}

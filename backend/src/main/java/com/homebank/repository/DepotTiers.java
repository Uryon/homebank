package com.homebank.repository;

import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepotTiers extends JpaRepository<Tiers, Long> {

    /**
     * Reaffecte en masse la categorie par defaut des tiers qui pointaient
     * vers "source" pour qu'ils pointent desormais vers "target".
     * Utilise lors de la fusion de deux categories.
     */
    @Modifying
    @Query("update Tiers p set p.category = :target where p.category = :source")
    void reassignCategory(@Param("source") Categorie source, @Param("target") Categorie target);

    /**
     * Retire la categorie par defaut des tiers qui pointaient vers "category" :
     * utilise avant la suppression d'une categorie, pour que les tiers qui
     * l'avaient comme categorie par defaut ne bloquent pas la suppression
     * (contrainte de cle etrangere) et se retrouvent simplement sans
     * categorie par defaut plutot que d'empecher l'operation.
     */
    @Modifying
    @Query("update Tiers p set p.category = null where p.category = :category")
    void clearCategory(@Param("category") Categorie category);
}

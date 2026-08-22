package com.homebank.repository;

import com.homebank.model.Compte;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotCompte extends JpaRepository<Compte, Long> {
}

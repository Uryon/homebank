package com.homebank.repository;

import com.homebank.model.Pret;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotPret extends JpaRepository<Pret, Long> {
}

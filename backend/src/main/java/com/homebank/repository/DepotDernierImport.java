package com.homebank.repository;

import com.homebank.model.DernierImport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotDernierImport extends JpaRepository<DernierImport, Long> {
}

package com.homebank.repository;

import com.homebank.model.Categorie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepotCategorie extends JpaRepository<Categorie, Long> {
    List<Categorie> findByParent(Categorie parent);
    Optional<Categorie> findByNameIgnoreCaseAndParent(String name, Categorie parent);
    Optional<Categorie> findByNameIgnoreCaseAndParentIsNull(String name);
}

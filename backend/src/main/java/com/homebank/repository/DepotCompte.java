package com.homebank.repository;

import com.homebank.model.Compte;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepotCompte extends JpaRepository<Compte, Long> {

    /** Vrai si un compte porte deja ce nom, casse ignoree (voir ServiceCompte#ensureUniqueName). */
    boolean existsByNameIgnoreCase(String name);
}

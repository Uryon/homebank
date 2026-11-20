package com.homebank.repository;

import com.homebank.model.LigneImportee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DepotLigneImportee extends JpaRepository<LigneImportee, Long> {

    List<LigneImportee> findByAccountId(Long accountId);

    /** Oublie les lignes dont l'import est annule : elles redeviennent importables. */
    @Modifying
    @Query("delete from LigneImportee l where l.transactionId in :transactionIds")
    int deleteByTransactionIds(@Param("transactionIds") Collection<Long> transactionIds);
}

package com.homebank.service;

import com.homebank.dto.CategorisationAutoResultatDTO;
import com.homebank.model.Categorie;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.HashMap;

/**
 * Categorisation automatique des tiers et de leurs transactions encore sans
 * categorie, a partir des categories deja choisies (page Tiers).
 */
@Service
public class ServiceCategorisationAuto {

    private static final Logger log = LoggerFactory.getLogger(ServiceCategorisationAuto.class);

    private final DepotTiers payeeRepository;
    private final DepotTransaction transactionRepository;

    public ServiceCategorisationAuto(DepotTiers payeeRepository, DepotTransaction transactionRepository) {
        this.payeeRepository = payeeRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Categorisation automatique en masse : pour chaque tiers sans categorie,
     * si TOUTES ses transactions deja categorisees s'accordent sur UNE seule
     * categorie (les transactions encore sans categorie n'entrent pas en
     * compte, ni pour ni contre — voir suggestedCategoryId cote frontend,
     * meme logique), cette categorie est affectee au tiers. Dans la foulee,
     * la categorie ainsi determinee pour le tiers (deduite a l'instant, ou
     * deja presente auparavant) est propagee a TOUTES les transactions de ce
     * tiers encore sans categorie : ne modifie jamais une categorie deja
     * choisie, ni sur un tiers ni sur une transaction — ne fait que combler
     * les vides a partir d'un signal deja consistant.
     */
    @Transactional
    public CategorisationAutoResultatDTO autoCategorize() {
        List<Tiers> allPayees = payeeRepository.findAll();
        List<Transaction> allTransactions = transactionRepository.findAllWithPayeeAndCategory();
        Map<Long, List<Transaction>> transactionsByPayeeId = groupTransactionsByPayeeId(allTransactions);

        List<Tiers> payeesToSave = new ArrayList<>();
        List<Transaction> transactionsToSave = new ArrayList<>();

        for (Tiers payee : allPayees) {
            List<Transaction> transactions = transactionsByPayeeId.getOrDefault(payee.getId(), List.of());
            Categorie targetCategory = resolveTargetCategory(payee, transactions, payeesToSave);
            if (targetCategory == null) continue;
            applyCategoryToUncategorized(transactions, targetCategory, transactionsToSave);
        }

        payeeRepository.saveAll(payeesToSave);
        transactionRepository.saveAll(transactionsToSave);

        log.info("Categorisation automatique : {} tiers categorise(s), {} transaction(s) mise(s) a jour",
                payeesToSave.size(), transactionsToSave.size());

        return new CategorisationAutoResultatDTO(payeesToSave.size(), transactionsToSave.size(),
                payeesToSave.stream().map(Tiers::getId).toList(),
                transactionsToSave.stream().map(Transaction::getId).toList());
    }

    /** Regroupe les transactions par id de tiers ; les transactions sans tiers sont ignorees. */
    private static Map<Long, List<Transaction>> groupTransactionsByPayeeId(List<Transaction> allTransactions) {
        Map<Long, List<Transaction>> result = new HashMap<>();
        for (Transaction t : allTransactions) {
            if (t.getPayee() == null) continue;
            result.computeIfAbsent(t.getPayee().getId(), id -> new ArrayList<>()).add(t);
        }
        return result;
    }

    /**
     * Categorie deja affectee au tiers, ou deduite si toutes ses transactions
     * deja categorisees s'accordent sur une seule (voir javadoc de
     * autoCategorize) — si deduite, le tiers est ajoute a payeesToSave. Null
     * si le tiers n'a pas de categorie et qu'aucun signal consistant n'existe,
     * ou si la seule categorie trouvee est masquee.
     */
    private static Categorie resolveTargetCategory(Tiers payee, List<Transaction> transactions, List<Tiers> payeesToSave) {
        if (payee.getCategory() != null) return payee.getCategory();

        Set<Categorie> distinctCategories = new HashSet<>();
        for (Transaction t : transactions) {
            if (t.getCategory() != null) distinctCategories.add(t.getCategory());
        }
        if (distinctCategories.size() != 1) return null;

        Categorie deduced = distinctCategories.iterator().next();
        // Categorie masquee = plus utilisee (ex : abonnement resilie) : la
        // proposer pour les nouvelles transactions contredirait la saisie, qui
        // ne la propose plus (CategoryAutocomplete).
        if (deduced.isHidden()) return null;
        payee.setCategory(deduced);
        payeesToSave.add(payee);
        return deduced;
    }

    /** Affecte targetCategory a chaque transaction encore sans categorie, en l'ajoutant a transactionsToSave. */
    private static void applyCategoryToUncategorized(List<Transaction> transactions, Categorie targetCategory, List<Transaction> transactionsToSave) {
        for (Transaction t : transactions) {
            if (t.getCategory() == null) {
                t.setCategory(targetCategory);
                transactionsToSave.add(t);
            }
        }
    }
}

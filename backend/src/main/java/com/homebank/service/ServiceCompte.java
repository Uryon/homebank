package com.homebank.service;

import com.homebank.dto.CompteDTO;
import com.homebank.model.Compte;
import com.homebank.repository.DepotCompte;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.TypeCompte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class ServiceCompte {

    private static final Logger log = LoggerFactory.getLogger(ServiceCompte.class);

    private final DepotCompte accountRepository;
    private final DepotTransaction transactionRepository;

    public ServiceCompte(DepotCompte accountRepository, DepotTransaction transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<Compte> findAll() {
        return accountRepository.findAll();
    }

    public Compte findById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Compte introuvable : " + id));
    }

    /**
     * Un compte archive est en lecture seule pour les NOUVELLES transactions
     * (saisie, virement, import) : son historique reste consultable et
     * modifiable (categorie, tiers...), mais on n'y ajoute plus rien tant
     * qu'il n'a pas ete desarchive. Statique pour rester verifiee meme quand
     * ServiceCompte est simule dans les tests des services appelants.
     */
    public static void ensureNotArchived(Compte account) {
        if (account.isArchived()) {
            throw new IllegalStateException("Le compte « " + account.getName()
                    + " » est archivé : désarchivez-le pour y ajouter des transactions.");
        }
    }

    public Compte create(CompteDTO payload) {
        Compte account = new Compte();
        applyPayload(account, payload);
        return accountRepository.save(account);
    }

    public Compte update(Long id, CompteDTO payload) {
        Compte existing = findById(id);
        if (existing.isArchived() != payload.isArchived()) {
            log.info("Compte {} {}", id, payload.isArchived() ? "archive" : "desarchive");
        }
        applyPayload(existing, payload);
        return accountRepository.save(existing);
    }

    /**
     * Valide et applique le payload. Sans cette validation, un champ manquant
     * (ex : solde initial vide) ne remontait qu'a l'insertion SQL (colonnes
     * NOT NULL), avec le message generique de DataIntegrityViolationException
     * ("Impossible de supprimer...") — hors de propos pour une creation.
     */
    private static void applyPayload(Compte account, CompteDTO payload) {
        String name = payload.getName() != null ? payload.getName().trim() : "";
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Le nom du compte est obligatoire.");
        }
        account.setName(name);
        account.setType(payload.getType() != null ? payload.getType() : TypeCompte.CHECKING);
        // Champ "Solde initial" laisse vide : equivalent a un solde de 0.
        account.setInitialBalance(payload.getInitialBalance() != null ? payload.getInitialBalance() : BigDecimal.ZERO);
        account.setCurrency(normalizeCurrency(payload.getCurrency()));
        account.setNotes(payload.getNotes());
        account.setArchived(payload.isArchived());
    }

    /** Code devise ISO 4217 en majuscules (EUR par defaut si vide), refuse s'il ne fait pas 3 lettres. */
    static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) return "EUR";
        String code = currency.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("La devise doit être un code à 3 lettres (ex : EUR).");
        }
        return code;
    }

    /**
     * Une suppression JPA du compte emporterait aussi toutes ses transactions
     * (Compte#transactions est configure en cascade ALL/orphanRemoval). On
     * refuse donc la suppression d'un compte utilise : l'utilisateur doit
     * d'abord archiver le compte ou traiter explicitement son historique.
     */
    public void delete(Long id) {
        Compte account = findById(id);
        long transactionCount = transactionRepository.countByAccount_Id(id);
        if (transactionCount > 0) {
            throw new IllegalStateException("Ce compte contient " + transactionCount
                    + " transaction(s). Archivez-le plutôt que de le supprimer afin de conserver votre historique.");
        }
        accountRepository.delete(account);
    }

    /**
     * Solde courant "reel" de chaque compte, pour le tableau de bord = solde
     * initial + somme des transactions deja survenues (date <= aujourd'hui).
     * Une transaction datee dans le futur est seulement prevue : elle n'a pas
     * encore reellement eu lieu et ne doit donc pas encore impacter le solde
     * affiche (voir aussi ServiceRapport, meme principe applique aux
     * statistiques).
     * <p>
     * Deux requetes au total (comptes + sommes groupees par compte) au lieu
     * d'une somme par compte.
     */
    public Map<Long, BigDecimal> allBalances() {
        Map<Long, BigDecimal> movementsByAccountId = transactionRepository
                .sumAmountGroupedByAccountIdAsOf(LocalDate.now(ZoneId.systemDefault()))
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (BigDecimal) row[1]));
        return accountRepository.findAll().stream()
                .collect(Collectors.toMap(Compte::getId, account -> account.getInitialBalance()
                        .add(movementsByAccountId.getOrDefault(account.getId(), BigDecimal.ZERO))));
    }
}

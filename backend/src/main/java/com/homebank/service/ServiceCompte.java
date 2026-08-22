package com.homebank.service;

import com.homebank.dto.CompteDTO;
import com.homebank.model.Compte;
import com.homebank.repository.DepotCompte;
import com.homebank.repository.DepotTransaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class ServiceCompte {

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

    public Compte create(CompteDTO payload) {
        Compte account = new Compte();
        account.setName(payload.getName());
        account.setType(payload.getType());
        account.setInitialBalance(payload.getInitialBalance());
        account.setCurrency(payload.getCurrency());
        account.setNotes(payload.getNotes());
        account.setArchived(payload.isArchived());
        return accountRepository.save(account);
    }

    public Compte update(Long id, CompteDTO payload) {
        Compte existing = findById(id);
        existing.setName(payload.getName());
        existing.setType(payload.getType());
        existing.setInitialBalance(payload.getInitialBalance());
        existing.setCurrency(payload.getCurrency());
        existing.setNotes(payload.getNotes());
        existing.setArchived(payload.isArchived());
        return accountRepository.save(existing);
    }

    public void delete(Long id) {
        accountRepository.deleteById(id);
    }

    /**
     * Solde courant "reel" = solde initial + somme des transactions deja
     * survenues (date <= aujourd'hui). Une transaction datee dans le futur
     * est seulement prevue : elle n'a pas encore reellement eu lieu et ne
     * doit donc pas encore impacter le solde affiche (voir aussi
     * ServiceRapport, meme principe applique aux statistiques).
     */
    private BigDecimal currentBalance(Compte account) {
        return account.getInitialBalance()
                .add(transactionRepository.sumAmountByAccountIdAsOf(account.getId(), LocalDate.now()));
    }

    /**
     * Solde de chaque compte, pour le tableau de bord. Reutilise directement
     * les comptes deja charges par findAll() au lieu de les recharger un par
     * un par id.
     */
    public Map<Long, BigDecimal> allBalances() {
        return accountRepository.findAll().stream()
                .collect(Collectors.toMap(Compte::getId, this::currentBalance));
    }
}

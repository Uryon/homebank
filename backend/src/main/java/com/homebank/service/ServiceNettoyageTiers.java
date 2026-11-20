package com.homebank.service;

import com.homebank.dto.ApercuFauxTiersDTO;
import com.homebank.dto.ResultatNettoyageTiersDTO;
import com.homebank.dto.ResultatNettoyageTiersDTO.TiersNettoye;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.LongueurTexte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Repare les faux tiers : modifier un virement ou un ajustement de solde
 * renvoyait autrefois son libelle calcule dans le champ tiers, qui devenait un
 * tiers (« Virement vers Livret »). Ses transactions sont detachees et
 * retrouvent leur libelle a jour. Le tiers n'est supprime que s'il ne sert plus
 * a rien d'autre et n'a pas ete personnalise.
 */
@Service
public class ServiceNettoyageTiers {

    private static final Logger log = LoggerFactory.getLogger(ServiceNettoyageTiers.class);
    private static final int MAX_EXAMPLES = 3;

    private final DepotTransaction transactionRepository;
    private final DepotTiers payeeRepository;

    public ServiceNettoyageTiers(DepotTransaction transactionRepository, DepotTiers payeeRepository) {
        this.transactionRepository = transactionRepository;
        this.payeeRepository = payeeRepository;
    }

    private record FauxTiers(Tiers payee, List<Transaction> transactions, long otherTransactions) {
        boolean deletable() {
            return otherTransactions == 0 && ServiceTiers.isAutoCreatedState(payee);
        }
    }

    @Transactional(readOnly = true)
    public List<ApercuFauxTiersDTO> preview() {
        return findFakePayees().stream().map(ServiceNettoyageTiers::toPreview).toList();
    }

    /** Traite les tiers choisis, en tout ou rien. La detection est refaite : un id hors liste est ignore. */
    @Transactional
    public ResultatNettoyageTiersDTO clean(List<Long> payeeIds) {
        if (payeeIds == null) {
            throw new IllegalArgumentException("La liste des tiers à nettoyer est obligatoire.");
        }
        Set<Long> selected = new HashSet<>(payeeIds);
        List<TiersNettoye> cleaned = new ArrayList<>();
        int detached = 0;
        int deleted = 0;
        for (FauxTiers fake : findFakePayees()) {
            Tiers payee = fake.payee();
            if (!selected.contains(payee.getId())) continue;
            // Lu avant la suppression eventuelle du tiers.
            boolean deletable = fake.deletable();
            fake.transactions().forEach(t -> t.setPayee(null));
            cleaned.add(new TiersNettoye(payee.getId(), payee.getName(), payee.getPattern(),
                    fake.transactions().stream().map(Transaction::getId).toList()));
            detached += fake.transactions().size();
            if (deletable) {
                payeeRepository.delete(payee);
                deleted++;
            }
        }
        log.info("Nettoyage des faux tiers : {} transaction(s) detachee(s), {} tiers supprime(s)", detached, deleted);
        return new ResultatNettoyageTiersDTO(detached, deleted, cleaned);
    }

    /**
     * Annule un nettoyage. Une transaction qui a recu un tiers entre-temps
     * garde ce choix plus recent ; un tiers supprime n'est recree que s'il
     * retrouve au moins une transaction.
     */
    @Transactional
    public void undo(List<TiersNettoye> cleaned) {
        boolean complete = cleaned != null && cleaned.stream()
                .allMatch(entry -> entry != null && entry.payeeId() != null && entry.transactionIds() != null);
        if (!complete) {
            throw new IllegalArgumentException("La liste des tiers à rétablir est incomplète.");
        }
        List<Long> transactionIds = cleaned.stream().flatMap(entry -> entry.transactionIds().stream()).toList();
        Map<Long, Transaction> transactionsById = transactionRepository.findAllById(transactionIds).stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity()));
        for (TiersNettoye entry : cleaned) {
            List<Transaction> toRelink = entry.transactionIds().stream()
                    .map(transactionsById::get)
                    .filter(t -> t != null && t.getPayee() == null && MappeurTransaction.hasComputedLabel(t))
                    .toList();
            if (toRelink.isEmpty()) continue;
            Tiers payee = payeeRepository.findById(entry.payeeId()).orElseGet(() -> recreate(entry));
            toRelink.forEach(t -> t.setPayee(payee));
        }
        log.info("Nettoyage des faux tiers annule : {} tiers", cleaned.size());
    }

    private Tiers recreate(TiersNettoye entry) {
        if (entry.name() == null || entry.name().isBlank()) {
            throw new IllegalArgumentException("Le nom du tiers est obligatoire.");
        }
        LongueurTexte.verifier(entry.name(), "Le nom du tiers");
        Tiers payee = new Tiers();
        payee.setName(entry.name());
        payee.setPattern(entry.pattern() != null && !entry.pattern().isBlank() ? entry.pattern() : entry.name());
        return payeeRepository.save(payee);
    }

    private List<FauxTiers> findFakePayees() {
        Map<Long, List<Transaction>> transactionsByPayeeId = new LinkedHashMap<>();
        Map<Long, Tiers> payeesById = new HashMap<>();
        for (Transaction transaction : transactionRepository.findWithPayeeAndComputedLabel()) {
            Tiers payee = transaction.getPayee();
            if (!MappeurTransaction.looksLikeComputedLabel(payee.getName())) continue;
            payeesById.put(payee.getId(), payee);
            transactionsByPayeeId.computeIfAbsent(payee.getId(), id -> new ArrayList<>()).add(transaction);
        }
        if (payeesById.isEmpty()) return List.of();

        Map<Long, Long> totalByPayeeId = new HashMap<>();
        for (Object[] row : transactionRepository.countByPayeeIds(payeesById.keySet())) {
            totalByPayeeId.put((Long) row[0], (Long) row[1]);
        }
        return transactionsByPayeeId.entrySet().stream()
                .map(entry -> {
                    long total = totalByPayeeId.getOrDefault(entry.getKey(), (long) entry.getValue().size());
                    return new FauxTiers(payeesById.get(entry.getKey()), entry.getValue(), total - entry.getValue().size());
                })
                .sorted(Comparator.comparing(fake -> fake.payee().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static ApercuFauxTiersDTO toPreview(FauxTiers fake) {
        List<ApercuFauxTiersDTO.Exemple> examples = fake.transactions().stream()
                .limit(MAX_EXAMPLES)
                .map(t -> new ApercuFauxTiersDTO.Exemple(t.getDate(), t.getAmount(), MappeurTransaction.computedLabel(t)))
                .toList();
        return new ApercuFauxTiersDTO(fake.payee().getId(), fake.payee().getName(), fake.transactions().size(),
                fake.otherTransactions(), fake.deletable(), examples);
    }
}

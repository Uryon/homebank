package com.homebank.service;

import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Onglet "Doublons" de la page Transactions : detection des transactions deja
 * en base qui semblent doublonnees (certaines, probables) ou remboursees, et
 * marquage definitif "pas un doublon". Lecture seule sur les transactions,
 * hormis le marqueur duplicateIgnored.
 */
@Service
public class ServiceDoublon {

    private static final Logger log = LoggerFactory.getLogger(ServiceDoublon.class);

    private final DepotTransaction transactionRepository;
    private final MappeurTransaction transactionMapper;

    public ServiceDoublon(DepotTransaction transactionRepository, MappeurTransaction transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.transactionMapper = transactionMapper;
    }

    /**
     * Regroupe les transactions deja en base qui semblent doublonnees, en
     * deux categories (voir GroupeDoublonDTO#certain) :
     * - "certains" : meme definition que la detection a l'import (voir
     *   ServiceImport#processOperation) — meme compte, meme date + montant +
     *   tiers canonique (insensible a la casse). Occurrences interchangeables.
     * - "probables" : meme compte + date + montant, mais tiers different —
     *   ratees par la detection ci-dessus car elle exige le meme tiers. Cas
     *   frequent d'un meme releve importe deux fois a des moments differents :
     *   la premiere fois categorisee et le tiers renomme a la main, la
     *   seconde fois avec le libelle brut de la banque jamais nettoye (ex :
     *   "Transfer : Prêt immobilier" vs "PRELEVEMENT DE ECHEANCE PRET REF :
     *   ..." le meme jour pour le meme montant). Un groupe deja compte parmi
     *   les "certains" (tiers identiques) n'est jamais liste une deuxieme
     *   fois ici. Pour l'onglet "Doublons" de la page Transactions, qui
     *   permet de nettoyer les doublons importes par erreur.
     */
    @Transactional(readOnly = true)
    public List<GroupeDoublonDTO> findDuplicateGroups() {
        List<Transaction> all = transactionRepository.findAllByOrderByDateDescIdDesc().stream()
                .filter(t -> !t.isDuplicateIgnored() && !t.isBalanceAdjustment())
                .toList();
        DuplicateGroups groups = groupDuplicates(all);

        Stream<GroupeDoublonDTO> certain = groups.certain().stream().map(group -> buildDuplicateGroup(group, true, false));
        Stream<GroupeDoublonDTO> probable = groups.probable().stream().map(group -> buildDuplicateGroup(group, false, false));
        Stream<GroupeDoublonDTO> reimbursements = findReimbursementGroups(all).stream();

        return Stream.concat(Stream.concat(certain, probable), reimbursements)
                .sorted(Comparator.comparing((GroupeDoublonDTO g) -> g.getTransactions().getFirst().getDate()).reversed())
                .toList();
    }

    /**
     * Nombre de groupes de doublons "certains" et "probables" (remboursements
     * exclus : ce ne sont pas des doublons a supprimer), pour le bandeau
     * « A faire » du tableau de bord. Meme detection que findDuplicateGroups,
     * sans charger categories ni virements ni construire les DTO.
     */
    @Transactional(readOnly = true)
    public int countDuplicateGroups() {
        DuplicateGroups groups = groupDuplicates(transactionRepository.findDuplicateCandidates());
        return groups.certain().size() + groups.probable().size();
    }

    /** Groupes "certains" (tiers identiques) et "probables" (tiers differents, meme commercant possible). */
    private record DuplicateGroups(List<List<Transaction>> certain, List<List<Transaction>> probable) {}

    private DuplicateGroups groupDuplicates(List<Transaction> candidates) {
        List<List<Transaction>> certain = candidates.stream()
                .collect(Collectors.groupingBy(this::duplicateGroupKey, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .filter(group -> group.size() > 1)
                .toList();

        List<List<Transaction>> probable = candidates.stream()
                .collect(Collectors.groupingBy(this::looseDuplicateGroupKey, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .filter(group -> group.size() > 1)
                // Meme tiers partout : deja liste parmi les "certains" (meme cle).
                .filter(group -> !samePayeeForAll(group) && payeesMayBeSameMerchant(group))
                .toList();

        return new DuplicateGroups(certain, probable);
    }

    /**
     * Detecte les groupes "remboursement" : PAIRES d'une depense et d'un
     * remboursement de meme montant (valeur absolue) par le meme tiers, a une
     * date quelconque, meme compte ou non. A la difference des doublons
     * "certains"/"probables" ci-dessus (qui exigent un montant identique,
     * signe compris, et ne peuvent donc jamais matcher ce cas), il ne s'agit
     * pas d'un import en double a supprimer : juste un reperage pour que
     * l'utilisateur voie d'un coup d'œil qu'une depense a deja ete remboursee.
     * Ignore les virements internes (jamais un remboursement au sens metier,
     * et de toute facon sans tiers).
     * <p>
     * S'il y a plusieurs depenses du meme montant mais un seul remboursement
     * (ex : 5 pleins d'essence a -50€ chez le meme tiers, un seul rembourse),
     * une seule paire est formee, avec la depense la plus RECENTE parmi
     * celles pas deja appariees (la plus probable d'etre celle concernee par
     * le remboursement) : les autres depenses du meme montant ne sont pas
     * proposees. S'il y a plusieurs remboursements, chacun forme sa propre
     * paire avec la depense la plus recente restante, en repetant l'operation
     * (2e remboursement <-> 2e depense la plus recente, etc.).
     */
    private List<GroupeDoublonDTO> findReimbursementGroups(List<Transaction> all) {
        Map<Long, List<Transaction>> byPayee = all.stream()
                .filter(t -> t.getPayee() != null)
                .filter(t -> !t.isInternalTransfer() && !t.isBalanceAdjustment())
                .collect(Collectors.groupingBy(t -> t.getPayee().getId()));

        Comparator<Transaction> mostRecentFirst =
                Comparator.comparing(Transaction::getDate).thenComparing(Transaction::getId).reversed();

        List<GroupeDoublonDTO> groups = new ArrayList<>();
        for (List<Transaction> payeeTransactions : byPayee.values()) {
            Map<BigDecimal, List<Transaction>> byAbsAmount = payeeTransactions.stream()
                    .collect(Collectors.groupingBy(t -> t.getAmount().abs().setScale(2, RoundingMode.HALF_UP)));

            for (List<Transaction> sameAbsAmount : byAbsAmount.values()) {
                List<Transaction> expenses = sameAbsAmount.stream()
                        .filter(t -> t.getAmount().signum() < 0)
                        .sorted(mostRecentFirst)
                        .toList();
                List<Transaction> reimbursements = sameAbsAmount.stream()
                        .filter(t -> t.getAmount().signum() > 0)
                        .sorted(mostRecentFirst)
                        .toList();

                int pairCount = Math.min(expenses.size(), reimbursements.size());
                for (int i = 0; i < pairCount; i++) {
                    groups.add(buildDuplicateGroup(List.of(expenses.get(i), reimbursements.get(i)), false, true));
                }
            }
        }
        return groups;
    }

    /**
     * Cle de regroupement d'une transaction pour la detection de doublons
     * "certains" : compte + date + montant + tiers canonique (voir CleDoublon).
     * Montant du releve (voir Transaction#importKeyAmount), comme a l'import :
     * les deux moities d'une operation divisee en parts egales ne sont pas des
     * doublons, et en supprimer une faussait le solde.
     */
    private String duplicateGroupKey(Transaction t) {
        String canonicalPayee = t.getPayee() != null ? t.getPayee().getName() : null;
        CleDoublon key = CleDoublon.of(t.getDate(), t.importKeyAmount(), canonicalPayee);
        return "key:" + t.getAccount().getId() + "|" + key;
    }

    /**
     * Cle de regroupement "large" pour la detection des doublons probables :
     * compte + date + montant seulement, sans le tiers (voir
     * findDuplicateGroups).
     */
    private String looseDuplicateGroupKey(Transaction t) {
        return t.getAccount().getId() + "|" + t.getDate() + "|" + t.importKeyAmount().setScale(2, RoundingMode.HALF_UP);
    }

    private String canonicalPayee(Transaction t) {
        String name = t.getPayee() != null ? t.getPayee().getName() : null;
        return name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
    }

    private boolean samePayeeForAll(List<Transaction> group) {
        String first = canonicalPayee(group.getFirst());
        return group.stream().allMatch(t -> canonicalPayee(t).equals(first));
    }

    /**
     * Groupe "large" (meme compte, date, montant) aux tiers differents : n'est
     * un doublon probable que si deux de ses transactions peuvent designer le
     * meme commercant — l'une sans tiers, ou le motif de reconnaissance de
     * l'un des tiers retrouvant le nom de l'autre (cas vise : la meme
     * operation importee deux fois, une fois sous un tiers renomme, une fois
     * sous le libelle brut de la banque). Deux tiers distincts deja reconnus
     * (ex : « Pharmacie » et « Docteur Trillot », 30 € le meme jour) sont deux
     * vraies operations : les proposer en doublon a conduit a en supprimer une
     * a tort, que l'import refusait ensuite de reintegrer.
     */
    private static boolean payeesMayBeSameMerchant(List<Transaction> group) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                if (sameBankLabel(group.get(i), group.get(j))
                        || mayBeSameMerchant(group.get(i).getPayee(), group.get(j).getPayee())) return true;
            }
        }
        return false;
    }

    /** Meme libelle de releve (voir Transaction#bankLabel) : meme operation bancaire, quels que soient les tiers affectes depuis. */
    private static boolean sameBankLabel(Transaction first, Transaction second) {
        return first.getBankLabel() != null && first.getBankLabel().equalsIgnoreCase(second.getBankLabel());
    }

    private static boolean mayBeSameMerchant(Tiers first, Tiers second) {
        if (first == null || second == null) return true;
        return MotifsTiers.matchesPatternField(first.getPattern(), second.getName())
                || MotifsTiers.matchesPatternField(second.getPattern(), first.getName());
    }

    /**
     * Marque les transactions passees comme n'etant pas des doublons : elles
     * seront exclues de findDuplicateGroups (voir Transaction#duplicateIgnored),
     * ce qui fait disparaitre de l'onglet "Doublons" tout groupe dont l'une des
     * occurrences ne compte plus (un groupe necessite au moins 2 occurrences
     * restantes pour etre affiche).
     */
    @Transactional
    public void markAsNotDuplicate(List<Long> ids) {
        setDuplicateIgnored(ids, true);
    }

    /** Inverse de markAsNotDuplicate (bouton « Annuler ») : les transactions redeviennent candidates aux doublons. */
    @Transactional
    public void unmarkAsNotDuplicate(List<Long> ids) {
        setDuplicateIgnored(ids, false);
    }

    private void setDuplicateIgnored(List<Long> ids, boolean ignored) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Aucune transaction sélectionnée.");
        }
        List<Transaction> transactions = transactionRepository.findAllById(ids);
        transactions.forEach(t -> t.setDuplicateIgnored(ignored));
        transactionRepository.saveAll(transactions);
        log.info("{} de {} transaction(s) comme non doublon", ignored ? "Marquage" : "Retrait du marquage", transactions.size());
    }

    private GroupeDoublonDTO buildDuplicateGroup(List<Transaction> transactions, boolean certain, boolean reimbursement) {
        List<Transaction> sorted = transactions.stream()
                .sorted(Comparator.comparing(Transaction::getId))
                .toList();
        return GroupeDoublonDTO.builder()
                .count(sorted.size())
                .transactions(sorted.stream().map(transactionMapper::toDTO).toList())
                .certain(certain)
                .reimbursement(reimbursement)
                .build();
    }
}

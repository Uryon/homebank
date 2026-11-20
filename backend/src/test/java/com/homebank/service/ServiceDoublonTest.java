package com.homebank.service;

import com.homebank.dto.GroupeDoublonDTO;
import com.homebank.mapper.MappeurTransaction;
import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.homebank.service.FabriqueTransactions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifie la detection des doublons (certains, probables) et des paires
 * depense/remboursement de l'onglet "Doublons", et le marquage "pas un doublon".
 */
@ExtendWith(MockitoExtension.class)
class ServiceDoublonTest {

    @Mock
    private DepotTransaction transactionRepository;

    private ServiceDoublon service;

    @BeforeEach
    void setUp() {
        service = new ServiceDoublon(transactionRepository, new MappeurTransaction());
    }

    @Test
    void findDuplicateGroups_ignoreLesAjustementsDeSolde() {
        Compte account = compte(1L);
        Transaction a1 = transaction(account, null, LocalDate.of(2026, 1, 5), "50.00");
        a1.setId(1L);
        a1.setBalanceAdjustment(true);
        Transaction a2 = transaction(account, null, LocalDate.of(2026, 1, 5), "50.00");
        a2.setId(2L);
        a2.setBalanceAdjustment(true);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(a1, a2));

        assertThat(service.findDuplicateGroups()).isEmpty();
    }

    // --- countDuplicateGroups ---

    @Test
    void countDuplicateGroups_compteCertainsEtProbablesMaisPasLesRemboursements() {
        Compte compte = compte(1L);
        LocalDate date = LocalDate.of(2026, 1, 15);
        Tiers edf = payee(1L, "EDF");
        Transaction edf1 = transaction(compte, edf, date, "-80.00");
        edf1.setId(1L);
        Transaction edf2 = transaction(compte, edf, date, "-80.00");
        edf2.setId(2L);
        // Probable : meme compte/date/montant, l'une sans tiers (libelle brut).
        Transaction nomme = transaction(compte, payee(2L, "Boulangerie"), date, "-4.20");
        nomme.setId(3L);
        Transaction brut = transaction(compte, null, date, "-4.20");
        brut.setId(4L);
        // Remboursement (meme tiers, montant oppose) : jamais compte comme doublon.
        Tiers amazon = payee(3L, "Amazon");
        Transaction achat = transaction(compte, amazon, LocalDate.of(2026, 1, 2), "-30.00");
        achat.setId(5L);
        Transaction rembourse = transaction(compte, amazon, LocalDate.of(2026, 1, 9), "30.00");
        rembourse.setId(6L);
        when(transactionRepository.findDuplicateCandidates())
                .thenReturn(List.of(edf1, edf2, nomme, brut, achat, rembourse));

        assertThat(service.countDuplicateGroups()).isEqualTo(2);
    }

    // --- findDuplicateGroups ---

    @Test
    void findDuplicateGroups_nePrendPasLesPartsEgalesDUneDivisionPourDesDoublons() {
        Tiers edf = payee(1L, "EDF");
        Compte compte = compte(1L);
        LocalDate date = LocalDate.of(2026, 1, 15);
        Transaction premierePartie = transaction(compte, edf, date, "-50.00");
        premierePartie.setId(1L);
        premierePartie.setSplitFromAmount(new BigDecimal("-100.00"));
        Transaction secondePartie = transaction(compte, edf, date, "-50.00");
        secondePartie.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(premierePartie, secondePartie));

        assertThat(service.findDuplicateGroups()).isEmpty();
    }

    @Test
    void findDuplicateGroups_reconnaitUnReimportDUneOperationDiviseeDepuis() {
        Tiers edf = payee(1L, "EDF");
        Compte compte = compte(1L);
        LocalDate date = LocalDate.of(2026, 1, 15);
        Transaction divisee = transaction(compte, edf, date, "-30.00");
        divisee.setId(1L);
        divisee.setSplitFromAmount(new BigDecimal("-100.00"));
        Transaction reimportee = transaction(compte, edf, date, "-100.00");
        reimportee.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(divisee, reimportee));

        assertThat(service.findDuplicateGroups()).singleElement()
                .satisfies(groupe -> assertThat(groupe.isCertain()).isTrue());
    }

    @Test
    void findDuplicateGroups_regroupeCommeCertainMemeCompteDateMontantEtTiers() {
        Compte account = compte(1L);
        Tiers carrefour = payee(1L, "Carrefour");
        Transaction t1 = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        t1.setId(1L);
        Transaction t2 = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        t2.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(t1, t2));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).isCertain()).isTrue();
        assertThat(groups.get(0).getCount()).isEqualTo(2);
    }


    /** Tiers renommé (« Carrefour ») et libellé brut de la banque qu'il reconnaît : probablement la même opération. */
    @Test
    void findDuplicateGroups_regroupeCommeProbableMemeCompteDateMontantMaisTiersDifferent() {
        Compte account = compte(1L);
        Transaction t1 = transaction(account, payeeAvecMotif(1L, "Carrefour", "Carrefour"), LocalDate.of(2026, 1, 5), "-45.50");
        t1.setId(1L);
        Tiers brut = payeeAvecMotif(2L, "Prelevement CB Carrefour Paris", "Prelevement CB Carrefour Paris");
        Transaction t2 = transaction(account, brut, LocalDate.of(2026, 1, 5), "-45.50");
        t2.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(t1, t2));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).isCertain()).isFalse();
        assertThat(groups.get(0).isReimbursement()).isFalse();
    }

    /** Deux commerçants distincts déjà reconnus, même jour et même montant : deux vraies opérations. */
    @Test
    void findDuplicateGroups_neSignalePasDeuxTiersDistinctsReconnusMemeJourMemeMontant() {
        Compte account = compte(1L);
        Transaction pharmacie = transaction(account, payeeAvecMotif(1L, "Pharmacie", "Pharmacie"),
                LocalDate.of(2026, 11, 12), "-30.00");
        pharmacie.setId(1L);
        Transaction docteur = transaction(account, payeeAvecMotif(2L, "Docteur Trillot", "DR TRILLOT"),
                LocalDate.of(2026, 11, 12), "-30.00");
        docteur.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(pharmacie, docteur));

        assertThat(service.findDuplicateGroups()).isEmpty();
    }

    @Test
    void findDuplicateGroups_signaleUneOperationSansTiersEtUneAvecTiersMemeJourMemeMontant() {
        Compte account = compte(1L);
        Transaction sansTiers = transaction(account, null, LocalDate.of(2026, 1, 5), "-12.00");
        sansTiers.setId(1L);
        Transaction avecTiers = transaction(account, payeeAvecMotif(2L, "Boulangerie", "Boulangerie"),
                LocalDate.of(2026, 1, 5), "-12.00");
        avecTiers.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(sansTiers, avecTiers));

        assertThat(service.findDuplicateGroups()).singleElement()
                .satisfies(g -> assertThat(g.isCertain()).isFalse());
    }

    @Test
    void findDuplicateGroups_ligneImporteeRessaisieALaMainMemeTiers_estUnDoublonCertainListeUneSeuleFois() {
        Compte account = compte(1L);
        Tiers carrefour = payee(1L, "Carrefour");
        Transaction importee = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        importee.setId(1L);
        importee.setBankLabel("ACHAT CB CARREFOUR 04.01.26 CARTE NUMERO 697");
        Transaction manuelle = transaction(account, carrefour, LocalDate.of(2026, 1, 5), "-45.50");
        manuelle.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(importee, manuelle));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.isCertain()).isTrue();
            assertThat(g.getCount()).isEqualTo(2);
        });
    }

    @Test
    void findDuplicateGroups_tiersDistinctsMaisMemeLibelleBancaire_estUnDoublonProbable() {
        Compte account = compte(1L);
        Transaction renommee = transaction(account, payee(1L, "Vinted"), LocalDate.of(2026, 1, 5), "-19.90");
        renommee.setId(1L);
        renommee.setBankLabel("ACHAT CB PAYPAL .ryckew 03.01.26");
        Transaction brute = transaction(account, payee(2L, "Boulangerie"), LocalDate.of(2026, 1, 5), "-19.90");
        brute.setId(2L);
        brute.setBankLabel("ACHAT CB PAYPAL .ryckew 03.01.26");
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(renommee, brute));

        assertThat(service.findDuplicateGroups()).singleElement()
                .satisfies(g -> assertThat(g.isCertain()).isFalse());
    }

    @Test
    void findDuplicateGroups_detectePaireRembourseeMemeTiersMontantOppose() {
        Compte account = compte(1L);
        Tiers ami = payee(1L, "Remboursement Ami");
        Transaction depense = transaction(account, ami, LocalDate.of(2026, 1, 5), "-50.00");
        depense.setId(1L);
        Transaction remboursement = transaction(account, ami, LocalDate.of(2026, 1, 20), "50.00");
        remboursement.setId(2L);
        when(transactionRepository.findAllByOrderByDateDescIdDesc()).thenReturn(List.of(depense, remboursement));

        List<GroupeDoublonDTO> groups = service.findDuplicateGroups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).isReimbursement()).isTrue();
        assertThat(groups.get(0).isCertain()).isFalse();
    }

    @Test
    void markAsNotDuplicate_marqueLesTransactionsDemandees() {
        Transaction t1 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-3.00");
        Transaction t2 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-3.00");
        when(transactionRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(t1, t2));

        service.markAsNotDuplicate(List.of(1L, 2L));

        assertThat(t1.isDuplicateIgnored()).isTrue();
        assertThat(t2.isDuplicateIgnored()).isTrue();
        verify(transactionRepository).saveAll(List.of(t1, t2));
    }

    @Test
    void unmarkAsNotDuplicate_rendLesTransactionsDeNouveauCandidates() {
        Transaction t1 = transaction(compte(1L), null, LocalDate.of(2026, 2, 1), "-3.00");
        t1.setDuplicateIgnored(true);
        when(transactionRepository.findAllById(List.of(1L))).thenReturn(List.of(t1));

        service.unmarkAsNotDuplicate(List.of(1L));

        assertThat(t1.isDuplicateIgnored()).isFalse();
        verify(transactionRepository).saveAll(List.of(t1));
    }

    @Test
    void refuseUneSelectionVide() {
        assertThatThrownBy(() -> service.markAsNotDuplicate(List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactionRepository);
    }
}

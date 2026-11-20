package com.homebank.service;

import com.homebank.dto.ApercuFauxTiersDTO;
import com.homebank.dto.ResultatNettoyageTiersDTO;
import com.homebank.model.Categorie;
import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTiers;
import com.homebank.repository.DepotTransaction;
import com.homebank.utils.ModePaiement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.homebank.service.FabriqueTransactions.compte;
import static com.homebank.service.FabriqueTransactions.payeeAvecMotif;
import static com.homebank.service.FabriqueTransactions.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceNettoyageTiersTest {

    private static final LocalDate DATE = LocalDate.of(2026, 3, 5);

    @Mock
    private DepotTransaction transactionRepository;
    @Mock
    private DepotTiers payeeRepository;

    private ServiceNettoyageTiers service;
    private final List<Transaction> candidates = new ArrayList<>();
    private final List<Object[]> counts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ServiceNettoyageTiers(transactionRepository, payeeRepository);
        when(transactionRepository.findWithPayeeAndComputedLabel()).thenReturn(candidates);
        when(transactionRepository.countByPayeeIds(anyCollection())).thenReturn(counts);
    }

    private Transaction virement(long id, Tiers tiers) {
        Compte livret = compte(2L);
        livret.setName("Livret");
        Transaction autreCote = transaction(livret, null, DATE, "50.00");
        Transaction t = transaction(compte(1L), tiers, DATE, "-50.00");
        t.setId(id);
        t.setPaymentMode(ModePaiement.TRANSFER);
        t.setTransferTransaction(autreCote);
        candidates.add(t);
        return t;
    }

    private void total(Tiers tiers, long nombre) {
        counts.add(new Object[]{tiers.getId(), nombre});
    }

    @Test
    void lApercuAnnonceLeNouveauLibelleEtNeModifieRien() {
        Tiers faux = payeeAvecMotif(7L, "Virement vers Ancien nom", "Virement vers Ancien nom");
        Transaction t = virement(1L, faux);
        total(faux, 1);

        List<ApercuFauxTiersDTO> apercu = service.preview();

        assertThat(apercu).hasSize(1);
        assertThat(apercu.getFirst().willBeDeleted()).isTrue();
        assertThat(apercu.getFirst().examples().getFirst().newLabel()).isEqualTo("Virement vers Livret");
        assertThat(t.getPayee()).isSameAs(faux);
        verify(payeeRepository, never()).delete(any());
    }

    @Test
    void leNettoyageDetacheLesTransactionsEtSupprimeLeTiersDevenuInutile() {
        Tiers faux = payeeAvecMotif(7L, "virement VERS Livret", "virement VERS Livret");
        Transaction t = virement(1L, faux);
        total(faux, 1);

        ResultatNettoyageTiersDTO resultat = service.clean(List.of(7L));

        assertThat(t.getPayee()).isNull();
        assertThat(resultat.detachedTransactions()).isEqualTo(1);
        assertThat(resultat.deletedPayees()).isEqualTo(1);
        verify(payeeRepository).delete(faux);
    }

    @Test
    void unTiersEncoreUtiliseAilleursEstConserve() {
        Tiers faux = payeeAvecMotif(7L, "Virement vers Livret", "Virement vers Livret");
        Transaction t = virement(1L, faux);
        total(faux, 3);

        assertThat(service.preview().getFirst().otherTransactions()).isEqualTo(2);
        ResultatNettoyageTiersDTO resultat = service.clean(List.of(7L));

        assertThat(t.getPayee()).isNull();
        assertThat(resultat.deletedPayees()).isZero();
        verify(payeeRepository, never()).delete(any());
    }

    @Test
    void unTiersPersonnaliseEstConserve() {
        Tiers faux = payeeAvecMotif(7L, "Virement vers Livret", "Virement vers Livret");
        faux.setCategory(new Categorie());
        virement(1L, faux);
        total(faux, 1);

        assertThat(service.preview().getFirst().willBeDeleted()).isFalse();
        service.clean(List.of(7L));

        verify(payeeRepository, never()).delete(any());
    }

    @Test
    void unVraiTiersSurUnVirementNestPasConcerne() {
        Tiers vrai = payeeAvecMotif(8L, "Maman", "Maman");
        Transaction t = virement(1L, vrai);

        assertThat(service.preview()).isEmpty();
        service.clean(List.of(8L));

        assertThat(t.getPayee()).isSameAs(vrai);
    }

    @Test
    void seulsLesTiersCochesSontTraites() {
        Tiers garde = payeeAvecMotif(7L, "Virement vers A", "Virement vers A");
        Tiers nettoye = payeeAvecMotif(9L, "Virement vers B", "Virement vers B");
        Transaction t1 = virement(1L, garde);
        Transaction t2 = virement(2L, nettoye);
        total(garde, 1);
        total(nettoye, 1);

        service.clean(List.of(9L));

        assertThat(t1.getPayee()).isSameAs(garde);
        assertThat(t2.getPayee()).isNull();
    }

    @Test
    void uneAnnulationIncompleteEstRefusee() {
        List<ResultatNettoyageTiersDTO.TiersNettoye> incomplete = new ArrayList<>();
        incomplete.add(null);

        assertThatThrownBy(() -> service.undo(incomplete)).isInstanceOf(IllegalArgumentException.class);
        List<ResultatNettoyageTiersDTO.TiersNettoye> sansId = List.of(new ResultatNettoyageTiersDTO.TiersNettoye(null, "x", "x", null));
        assertThatThrownBy(() -> service.undo(sansId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lAnnulationRecreeLeTiersSupprimeSansEcraserUnChoixPlusRecent() {
        Tiers autre = payeeAvecMotif(8L, "Maman", "Maman");
        Transaction detachee = virement(1L, null);
        Transaction modifieeDepuis = virement(2L, autre);
        when(transactionRepository.findAllById(anyList())).thenReturn(List.of(detachee, modifieeDepuis));
        when(payeeRepository.findById(7L)).thenReturn(Optional.empty());
        when(payeeRepository.save(any(Tiers.class))).thenAnswer(inv -> inv.getArgument(0));

        service.undo(List.of(new ResultatNettoyageTiersDTO.TiersNettoye(7L, "Virement vers A", "motif", List.of(1L, 2L))));

        assertThat(detachee.getPayee().getName()).isEqualTo("Virement vers A");
        assertThat(detachee.getPayee().getPattern()).isEqualTo("motif");
        assertThat(modifieeDepuis.getPayee()).isSameAs(autre);
    }
}

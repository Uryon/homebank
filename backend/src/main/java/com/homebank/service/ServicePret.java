package com.homebank.service;

import com.homebank.dto.LigneAmortissementDTO;
import com.homebank.dto.PretDTO;
import com.homebank.model.Pret;
import com.homebank.repository.DepotPret;
import com.homebank.utils.LongueurTexte;
import com.homebank.utils.Montant;
import com.homebank.utils.Periode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
public class ServicePret {

    private static final Logger log = LoggerFactory.getLogger(ServicePret.class);

    private static final int SCALE = 2;
    private static final int RATE_DIVISION_SCALE = 10;
    /** Taux tel que le stocke la colonne annual_rate, numeric(6,3). */
    private static final int RATE_SCALE = 3;
    private static final BigDecimal MAX_ANNUAL_RATE = new BigDecimal("999.999");

    /**
     * Au-dela de ce multiple de la mensualite, la derniere echeance (qui solde
     * le capital restant, voir computeSchedule) trahit une mensualite saisie
     * nettement trop basse pour la duree, plutot qu'un simple ecart d'arrondi.
     */
    private static final BigDecimal MAX_FINAL_PAYMENT_RATIO = BigDecimal.valueOf(2);

    /**
     * Duree maximale d'un pret (50 ans) : l'echeancier complet est calcule a
     * chaque affichage du tableau de bord et de la page Prets ; une duree
     * absurde (faute de frappe) en faisait des dizaines de milliers de lignes.
     */
    static final int MAX_TERM_MONTHS = 600;

    private final DepotPret loanRepository;

    public ServicePret(DepotPret loanRepository) {
        this.loanRepository = loanRepository;
    }

    public List<Pret> findAll() {
        return loanRepository.findAll();
    }

    public Pret findById(Long id) {
        return loanRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Prêt introuvable : " + id));
    }

    public Pret create(PretDTO payload) {
        Pret loan = new Pret();
        normalize(payload);
        validate(payload, null);
        applyPayload(loan, payload);
        Pret saved = loanRepository.save(loan);
        log.info("Pret cree : {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    public Pret update(Long id, PretDTO payload) {
        Pret existing = findById(id);
        normalize(payload);
        validate(payload, existing);
        applyPayload(existing, payload);
        log.info("Pret modifie : {} ({})", existing.getName(), id);
        return loanRepository.save(existing);
    }

    /**
     * Ramene la saisie a ce que la base conserve, avant les controles : sinon
     * ils portaient sur des valeurs que la base arrondissait ensuite, et un
     * montant ou un taux hors limites n'echouait qu'a l'ecriture, avec un
     * message sans rapport.
     */
    private static void normalize(PretDTO payload) {
        if (payload.getName() != null) {
            payload.setName(payload.getName().trim());
        }
        payload.setPrincipal(Montant.auCentime(payload.getPrincipal(), "Le capital emprunté"));
        payload.setMonthlyPayment(Montant.auCentime(payload.getMonthlyPayment(), "Le montant de la mensualité"));
        payload.setMonthlyInsurance(Montant.auCentime(payload.getMonthlyInsurance(), "Le montant de l'assurance"));
        BigDecimal rate = payload.getAnnualRate();
        if (rate != null) {
            // Compare avant setScale, comme Montant : "1E999999" y developperait un million de chiffres.
            if (rate.compareTo(MAX_ANNUAL_RATE) > 0) {
                throw new IllegalArgumentException("Le taux annuel ne peut pas dépasser 999,999 %.");
            }
            if (rate.signum() > 0) {
                payload.setAnnualRate(rate.setScale(RATE_SCALE, RoundingMode.HALF_UP));
            }
        }
    }

    private void applyPayload(Pret loan, PretDTO payload) {
        loan.setName(payload.getName());
        loan.setPrincipal(payload.getPrincipal());
        loan.setAnnualRate(payload.getAnnualRate());
        loan.setTermMonths(payload.getTermMonths());
        loan.setStartDate(payload.getStartDate());
        loan.setMonthlyPayment(payload.getMonthlyPayment());
        loan.setMonthlyInsurance(payload.getMonthlyInsurance());
        loan.setNotes(payload.getNotes());
    }

    /**
     * Validation cote service (le DTO n'a pas d'annotations Bean Validation) :
     * sans elle, un principal/mensualite negatif ou nul, ou une mensualite ne
     * couvrant meme pas les interets du premier mois, passait silencieusement
     * (au mieux un 500 generique via la contrainte JPA @NotNull de Pret pour
     * un champ manquant, au pire un echeancier absurde ou jamais solde pour
     * une mensualite trop faible).
     * <p>
     * `existing` (null en creation) permet de ne pas re-verifier la couverture
     * des interets du premier mois quand capital/taux/mensualite ne changent
     * pas : sans ca, un pret deja en base avant l'ajout de cette regle (ou
     * cree avec des valeurs limites) deviendrait impossible a modifier meme
     * pour un champ sans rapport (nom, notes...).
     */
    private void validate(PretDTO payload, Pret existing) {
        validateFields(payload);
        if (existing != null && sameFinancialTerms(existing, payload)) return;

        BigDecimal monthlyRate = payload.getAnnualRate()
                .divide(BigDecimal.valueOf(1200), RATE_DIVISION_SCALE, RoundingMode.HALF_UP);
        ensureFirstMonthInterestCovered(payload, monthlyRate);
        ensureFinalPaymentIsReasonable(payload, monthlyRate);
    }

    /** Champs obligatoires et bornes de chaque valeur saisie. */
    private static void validateFields(PretDTO payload) {
        if (payload.getName() == null || payload.getName().isBlank()) {
            throw new IllegalArgumentException("Le nom du prêt est obligatoire.");
        }
        LongueurTexte.verifier(payload.getName(), "Le nom du prêt");
        LongueurTexte.verifier(payload.getNotes(), "La note du prêt");
        if (payload.getPrincipal() == null || payload.getPrincipal().signum() <= 0) {
            throw new IllegalArgumentException("Le capital emprunté doit être positif.");
        }
        if (payload.getAnnualRate() == null || payload.getAnnualRate().signum() < 0) {
            throw new IllegalArgumentException("Le taux annuel ne peut pas être négatif.");
        }
        if (payload.getTermMonths() == null || payload.getTermMonths() <= 0) {
            throw new IllegalArgumentException("La durée du prêt doit être d'au moins un mois.");
        }
        if (payload.getTermMonths() > MAX_TERM_MONTHS) {
            throw new IllegalArgumentException("La durée du prêt ne peut pas dépasser " + MAX_TERM_MONTHS + " mois (50 ans).");
        }
        if (payload.getStartDate() == null) {
            throw new IllegalArgumentException("La date de première mensualité est obligatoire.");
        }
        Periode.verifierAnnee(payload.getStartDate(), "La date de première mensualité");
        if (payload.getMonthlyPayment() == null || payload.getMonthlyPayment().signum() <= 0) {
            throw new IllegalArgumentException("La mensualité doit être positive.");
        }
        if (payload.getMonthlyInsurance() != null && payload.getMonthlyInsurance().signum() < 0) {
            throw new IllegalArgumentException("L'assurance mensuelle ne peut pas être négative.");
        }
    }

    /** Capital, taux, mensualite et duree inchanges : pas de nouveau controle de l'echeancier (voir validate). */
    private static boolean sameFinancialTerms(Pret existing, PretDTO payload) {
        return existing.getPrincipal().compareTo(payload.getPrincipal()) == 0
                && existing.getAnnualRate().compareTo(payload.getAnnualRate()) == 0
                && existing.getMonthlyPayment().compareTo(payload.getMonthlyPayment()) == 0
                && existing.getTermMonths().equals(payload.getTermMonths());
    }

    /** La mensualite doit au moins couvrir les interets du premier mois, sinon le capital ne baisse jamais. */
    private static void ensureFirstMonthInterestCovered(PretDTO payload, BigDecimal monthlyRate) {
        BigDecimal firstMonthInterest = payload.getPrincipal().multiply(monthlyRate).setScale(SCALE, RoundingMode.HALF_UP);
        if (payload.getMonthlyPayment().compareTo(firstMonthInterest) <= 0) {
            throw new IllegalArgumentException(
                    "La mensualité (" + euros(payload.getMonthlyPayment()) + ") ne couvre même pas les intérêts du "
                            + "premier mois (" + euros(firstMonthInterest) + ") : le capital ne serait jamais remboursé.");
        }
    }

    /**
     * Une mensualite trop basse pour la duree laisse un gros capital restant,
     * solde d'un coup a la derniere echeance (voir computeSchedule) : refusee
     * au-dela de MAX_FINAL_PAYMENT_RATIO mensualites, avec la mensualite
     * necessaire (annuite constante) pour corriger la saisie.
     */
    private void ensureFinalPaymentIsReasonable(PretDTO payload, BigDecimal monthlyRate) {
        Pret simulated = new Pret();
        simulated.setPrincipal(payload.getPrincipal());
        simulated.setAnnualRate(payload.getAnnualRate());
        simulated.setTermMonths(payload.getTermMonths());
        simulated.setStartDate(payload.getStartDate());
        simulated.setMonthlyPayment(payload.getMonthlyPayment());
        List<LigneAmortissementDTO> schedule = computeSchedule(simulated);
        BigDecimal finalPayment = schedule.getLast().getPayment();
        if (finalPayment.compareTo(payload.getMonthlyPayment().multiply(MAX_FINAL_PAYMENT_RATIO)) > 0) {
            throw new IllegalArgumentException("La mensualité (" + euros(payload.getMonthlyPayment())
                    + ") est trop faible pour rembourser le prêt en " + payload.getTermMonths()
                    + " mois : dernière échéance de " + euros(finalPayment) + ". Mensualité nécessaire : "
                    + euros(annuityPayment(payload.getPrincipal(), monthlyRate, payload.getTermMonths())) + ".");
        }
    }

    /** Montant affiche dans un message d'erreur, au format francais : "1 134,56 €". */
    static String euros(BigDecimal amount) {
        return String.format(Locale.FRANCE, "%,.2f €", amount).replace('\u202f', ' ').replace('\u00a0', ' ');
    }

    /** Mensualite constante (hors assurance) qui rembourse exactement `principal` en `termMonths` mois, arrondie au centime superieur. */
    static BigDecimal annuityPayment(BigDecimal principal, BigDecimal monthlyRate, int termMonths) {
        if (monthlyRate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), SCALE, RoundingMode.CEILING);
        }
        MathContext mc = MathContext.DECIMAL64;
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(termMonths, mc);
        BigDecimal factor = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, mc));
        return principal.multiply(monthlyRate).divide(factor, SCALE, RoundingMode.CEILING);
    }

    public void delete(Long id) {
        Pret loan = findById(id);
        loanRepository.delete(loan);
        log.info("Pret supprime : {} ({})", loan.getName(), id);
    }

    /**
     * Calcule l'echeancier complet d'un pret, mois par mois, a partir de ses
     * parametres (capital, taux annuel, mensualite, date de depart). Chaque
     * mois : interets = capital restant du * taux mensuel, capital rembourse
     * = mensualite - interets, nouveau capital restant du = ancien - capital
     * rembourse. La derniere echeance est ajustee pour tomber pile a zero
     * (evite les ecarts d'arrondi cumules sur 15-25 ans).
     */
    public List<LigneAmortissementDTO> computeSchedule(Pret loan) {
        List<LigneAmortissementDTO> rows = new ArrayList<>();
        BigDecimal monthlyRate = loan.getAnnualRate()
                .divide(BigDecimal.valueOf(1200), RATE_DIVISION_SCALE, RoundingMode.HALF_UP);

        BigDecimal remaining = loan.getPrincipal();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        for (int i = 1; i <= loan.getTermMonths() && remaining.compareTo(BigDecimal.ZERO) > 0; i++) {
            LocalDate dueDate = loan.getStartDate().plusMonths(i - 1L);
            BigDecimal interest = remaining.multiply(monthlyRate).setScale(SCALE, RoundingMode.HALF_UP);

            BigDecimal payment = loan.getMonthlyPayment();
            BigDecimal principalPaid = payment.subtract(interest);

            // Mensualite qui depasserait le capital restant, ou derniere
            // echeance prevue (une mensualite saisie arrondie, ex : 499 au lieu
            // de 499,43, laissait sinon un capital restant du a la fin du
            // pret) : on solde exactement.
            boolean lastScheduledMonth = i == loan.getTermMonths();
            if (lastScheduledMonth || principalPaid.compareTo(remaining) >= 0) {
                principalPaid = remaining;
                payment = principalPaid.add(interest);
            }

            remaining = remaining.subtract(principalPaid);

            rows.add(LigneAmortissementDTO.builder()
                    .month(i)
                    .date(dueDate)
                    .payment(payment)
                    .interest(interest)
                    .principal(principalPaid)
                    .remainingBalance(remaining)
                    .paid(!dueDate.isAfter(today))
                    .build());
        }

        return rows;
    }

    public BigDecimal currentRemainingBalance(Pret loan, List<LigneAmortissementDTO> schedule) {
        return schedule.stream()
                .filter(LigneAmortissementDTO::isPaid)
                .reduce((a, b) -> b)
                .map(LigneAmortissementDTO::getRemainingBalance)
                .orElse(loan.getPrincipal());
    }

    public BigDecimal totalInterest(List<LigneAmortissementDTO> schedule) {
        return schedule.stream()
                .map(LigneAmortissementDTO::getInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public LocalDate payoffDate(List<LigneAmortissementDTO> schedule) {
        if (schedule.isEmpty()) return null;
        return schedule.getLast().getDate();
    }
}

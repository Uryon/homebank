package com.homebank.config;

import com.homebank.model.*;
import com.homebank.repository.*;
import com.homebank.service.ServiceTiers;
import com.homebank.utils.TypeCompte;
import com.homebank.utils.ModePaiement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Jette de donnees de demonstration, chargee UNIQUEMENT quand le profil
 * Spring "test" est actif (voir application-test.properties, base H2 en
 * memoire). N'a strictement aucun effet sur la base PostgreSQL "reelle"
 * utilisee par le jar en fonctionnement normal.
 *
 * Lancement depuis IntelliJ : Run > Edit Configurations > selectionner (ou
 * creer) la configuration de l'application Spring Boot > champ
 * "Active profiles" = test. Ou en ligne de commande :
 *   mvn spring-boot:run -Dspring-boot.run.profiles=test
 */
@Component
@Profile("test")
public class TestDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TestDataSeeder.class);
    private static final String CARREFOUR = "Carrefour";
    private static final String COURSES_DE_LA_SEMAINE = "Courses de la semaine";
    private static final String NETFLIX = "Netflix";

    private final DepotCompte accountRepository;
    private final DepotCategorie categoryRepository;
    private final DepotTiers payeeRepository;
    private final DepotTransaction transactionRepository;
    private final DepotPret loanRepository;
    private final ServiceTiers payeeService;

    public TestDataSeeder(DepotCompte accountRepository,
                           DepotCategorie categoryRepository,
                           DepotTiers payeeRepository,
                           DepotTransaction transactionRepository,
                           DepotPret loanRepository,
                           ServiceTiers payeeService) {
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.payeeRepository = payeeRepository;
        this.transactionRepository = transactionRepository;
        this.loanRepository = loanRepository;
        this.payeeService = payeeService;
    }

    @Override
    public void run(String... args) {
        log.info("Profil 'test' actif : chargement des donnees de demonstration (H2 en memoire)...");

        // --- Comptes ---
        Compte courant = account("Compte courant", TypeCompte.CHECKING, new BigDecimal("1500.00"));
        Compte epargne = account("Livret A", TypeCompte.SAVINGS, new BigDecimal("8000.00"));
        Compte carte = account("Carte de crédit", TypeCompte.CREDIT_CARD, BigDecimal.ZERO);

        // --- Categories ---
        Categorie alimentation = rootCategory("Alimentation", Categorie.TypeCategorie.EXPENSE);
        Categorie supermarche = childCategory("Supermarché", alimentation);
        Categorie restaurant = childCategory("Restaurant", alimentation);

        Categorie logement = rootCategory("Logement", Categorie.TypeCategorie.EXPENSE);
        Categorie loyer = childCategory("Loyer", logement);
        Categorie energie = childCategory("Énergie", logement);

        Categorie loisirs = rootCategory("Loisirs", Categorie.TypeCategorie.EXPENSE);
        Categorie abonnements = childCategory("Abonnements", loisirs);

        Categorie transport = rootCategory("Transport", Categorie.TypeCategorie.EXPENSE);

        Categorie salaire = rootCategory("Salaire", Categorie.TypeCategorie.INCOME);

        // --- Tiers (auto-catégorisation) ---
        payee(CARREFOUR, CARREFOUR, null, supermarche);
        payee("Employeur", "Employeur;Virement salaire", null, salaire);
        payee("Bailleur", "Bailleur;Loyer", null, loyer);
        payee(NETFLIX, NETFLIX, new BigDecimal("15.00"), abonnements);

        // --- Transactions : 4 derniers mois, revenus + depenses variees ---
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        for (int monthsAgo = 4; monthsAgo >= 0; monthsAgo--) {
            LocalDate monthStart = today.withDayOfMonth(1).minusMonths(monthsAgo);
            boolean isCurrentMonth = monthsAgo == 0;

            // Salaire, le dernier jour "raisonnable" du mois (le 28)
            transaction(new TransactionCore(courant, monthStart.withDayOfMonth(28), new BigDecimal("2400.00")),
                    "Employeur", "Virement salaire", salaire, ModePaiement.TRANSFER, true, false);

            // Loyer, prelevement du 3
            transaction(new TransactionCore(courant, monthStart.plusDays(2), new BigDecimal("-850.00")),
                    "Bailleur", "Loyer mensuel", loyer, ModePaiement.DIRECT_DEBIT, true, true);

            // Courses, plusieurs fois dans le mois
            transaction(new TransactionCore(courant, monthStart.plusDays(5), new BigDecimal("-64.30")),
                    CARREFOUR, COURSES_DE_LA_SEMAINE, supermarche, ModePaiement.CARD, true, false);
            transaction(new TransactionCore(courant, monthStart.plusDays(14), new BigDecimal("-48.90")),
                    CARREFOUR, COURSES_DE_LA_SEMAINE, supermarche, ModePaiement.CARD, true, false);
            transaction(new TransactionCore(courant, monthStart.plusDays(21), new BigDecimal("-52.10")),
                    CARREFOUR, COURSES_DE_LA_SEMAINE, supermarche, ModePaiement.CARD, true, false);

            // Restaurant, occasionnel
            transaction(new TransactionCore(courant, monthStart.plusDays(9), new BigDecimal("-38.50")),
                    "Le Bistrot", "Déjeuner", restaurant, ModePaiement.CARD, true, false);

            // Netflix, abonnement mensuel recurrent
            transaction(new TransactionCore(carte, monthStart.plusDays(1), new BigDecimal("-15.00")),
                    NETFLIX, "Abonnement", abonnements, ModePaiement.DIRECT_DEBIT, true, true);

            // Energie
            transaction(new TransactionCore(courant, monthStart.plusDays(10), new BigDecimal("-72.40")),
                    "Fournisseur énergie", "Facture électricité/gaz", energie, ModePaiement.DIRECT_DEBIT, true, true);

            // Transport
            transaction(new TransactionCore(courant, monthStart.plusDays(3), new BigDecimal("-45.00")),
                    "Réseau transport", "Pass mensuel", transport, ModePaiement.CARD, true, false);

            // Sur le mois en cours uniquement : quelques transactions "en attente"
            // (comme si elles venaient d'etre importees), pour illustrer le
            // rapprochement bancaire.
            if (isCurrentMonth) {
                transaction(new TransactionCore(courant, today.minusDays(1), new BigDecimal("-19.90")),
                        "Boulangerie du coin", null, alimentation, ModePaiement.CARD, false, false);
                transaction(new TransactionCore(courant, today, new BigDecimal("-9.50")),
                        "Station essence", null, transport, ModePaiement.CARD, false, false);
            }
        }

        // Petit virement interne courant -> epargne, le mois dernier
        LocalDate transferDate = today.minusMonths(1).withDayOfMonth(15);
        Transaction debit = new Transaction();
        debit.setAccount(courant);
        debit.setDate(transferDate);
        debit.setAmount(new BigDecimal("-200.00"));
        debit.setPaymentMode(ModePaiement.TRANSFER);
        debit.setValidated(true);
        Transaction savedDebit = transactionRepository.save(debit);

        Transaction credit = new Transaction();
        credit.setAccount(epargne);
        credit.setDate(transferDate);
        credit.setAmount(new BigDecimal("200.00"));
        credit.setPaymentMode(ModePaiement.TRANSFER);
        credit.setValidated(true);
        credit.setTransferTransaction(savedDebit);
        Transaction savedCredit = transactionRepository.save(credit);
        savedDebit.setTransferTransaction(savedCredit);
        transactionRepository.save(savedDebit);

        // --- Un pret immobilier de demonstration ---
        Pret loan = new Pret();
        loan.setName("Prêt immobilier - Résidence principale");
        loan.setPrincipal(new BigDecimal("220000.00"));
        loan.setAnnualRate(new BigDecimal("3.250"));
        loan.setTermMonths(240);
        loan.setStartDate(today.minusYears(3).withDayOfMonth(5));
        loan.setMonthlyPayment(new BigDecimal("1254.32"));
        loan.setMonthlyInsurance(new BigDecimal("38.50"));
        loan.setNotes("Donnée de démonstration (profil test)");
        loanRepository.save(loan);

        log.info("Données de démonstration chargées : {} comptes, {} catégories, {} tiers, {} transactions, {} prêt(s).",
                accountRepository.count(), categoryRepository.count(), payeeRepository.count(),
                transactionRepository.count(), loanRepository.count());
    }

    private Compte account(String name, TypeCompte type, BigDecimal initialBalance) {
        Compte a = new Compte();
        a.setName(name);
        a.setType(type);
        a.setInitialBalance(initialBalance);
        a.setCurrency("EUR");
        return accountRepository.save(a);
    }

    private Categorie rootCategory(String name, Categorie.TypeCategorie type) {
        Categorie c = new Categorie();
        c.setName(name);
        c.setType(type);
        return categoryRepository.save(c);
    }

    private Categorie childCategory(String name, Categorie parent) {
        Categorie c = new Categorie();
        c.setName(name);
        c.setType(parent.getType());
        c.setParent(parent);
        return categoryRepository.save(c);
    }

    private void payee(String name, String pattern, BigDecimal amount, Categorie category) {
        Tiers p = new Tiers();
        p.setName(name);
        p.setPattern(pattern);
        p.setAmount(amount);
        p.setCategory(category);
        payeeRepository.save(p);
    }

    /**
     * Regroupe compte/date/montant, communs a chaque transaction de demo,
     * pour ramener transaction(...) a 7 parametres (regle Sonar : 7 max).
     */
    private record TransactionCore(Compte account, LocalDate date, BigDecimal amount) {
    }

    /**
     * `payeeName` passe par la meme reconnaissance/creation automatique que
     * l'import ou la saisie manuelle (voir ServiceTiers#resolveOrCreate),
     * pour que les donnees de demo utilisent exactement le meme chemin que
     * du code reel : un nom deja enregistre via payee(...) rejoint le meme
     * tiers, un nom inconnu (ex : "Le Bistrot") en cree un a la volee.
     */
    private Transaction transaction(TransactionCore core, String payeeName, String memo, Categorie category,
                                     ModePaiement mode, boolean validated, boolean periodicExpense) {
        Transaction t = new Transaction();
        t.setAccount(core.account());
        t.setDate(core.date());
        t.setAmount(core.amount());
        t.setPayee(payeeName != null ? payeeService.resolveOrCreate(payeeName, core.amount()) : null);
        t.setMemo(memo);
        t.setCategory(category);
        t.setPaymentMode(mode);
        t.setValidated(validated);
        t.setPeriodicExpense(periodicExpense);
        return transactionRepository.save(t);
    }
}

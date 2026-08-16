package com.homebank.config;

import com.homebank.model.*;
import com.homebank.repository.*;
import com.homebank.utils.AccountType;
import com.homebank.utils.PaymentMode;
import com.homebank.utils.TransactionStatus;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

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

    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final PayeeRepository payeeRepository;
    private final TransactionRepository transactionRepository;
    private final LoanRepository loanRepository;

    public TestDataSeeder(AccountRepository accountRepository,
                           CategoryRepository categoryRepository,
                           PayeeRepository payeeRepository,
                           TransactionRepository transactionRepository,
                           LoanRepository loanRepository) {
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.payeeRepository = payeeRepository;
        this.transactionRepository = transactionRepository;
        this.loanRepository = loanRepository;
    }

    @Override
    public void run(String... args) {
        System.out.println(">>> Profil 'test' actif : chargement des donnees de demonstration (H2 en memoire)...");

        // --- Comptes ---
        Account courant = account("Compte courant", AccountType.CHECKING, new BigDecimal("1500.00"));
        Account epargne = account("Livret A", AccountType.SAVINGS, new BigDecimal("8000.00"));
        Account carte = account("Carte de crédit", AccountType.CREDIT_CARD, BigDecimal.ZERO);

        // --- Categories ---
        Category alimentation = rootCategory("Alimentation", Category.CategoryType.EXPENSE);
        Category supermarche = childCategory("Supermarché", alimentation);
        Category restaurant = childCategory("Restaurant", alimentation);

        Category logement = rootCategory("Logement", Category.CategoryType.EXPENSE);
        Category loyer = childCategory("Loyer", logement);
        Category energie = childCategory("Énergie", logement);

        Category loisirs = rootCategory("Loisirs", Category.CategoryType.EXPENSE);
        Category abonnements = childCategory("Abonnements", loisirs);

        Category transport = rootCategory("Transport", Category.CategoryType.EXPENSE);

        Category salaire = rootCategory("Salaire", Category.CategoryType.INCOME);

        // --- Tiers (auto-catégorisation) ---
        payee("Carrefour", "(?i)carrefour", null, supermarche);
        payee("Employeur", "(?i)employeur|virement salaire", null, salaire);
        payee("Bailleur", "(?i)bailleur|loyer", null, loyer);
        payee("Netflix", "(?i)netflix", new BigDecimal("15.00"), abonnements);

        // --- Transactions : 4 derniers mois, revenus + depenses variees ---
        LocalDate today = LocalDate.now();
        for (int monthsAgo = 4; monthsAgo >= 0; monthsAgo--) {
            LocalDate monthStart = today.withDayOfMonth(1).minusMonths(monthsAgo);
            boolean isCurrentMonth = monthsAgo == 0;

            // Salaire, le dernier jour "raisonnable" du mois (le 28)
            transaction(courant, monthStart.withDayOfMonth(28), new BigDecimal("2400.00"),
                    "Employeur", "Virement salaire", salaire, PaymentMode.TRANSFER, true, false);

            // Loyer, prelevement du 3
            Transaction loyerTx = transaction(courant, monthStart.plusDays(2), new BigDecimal("-850.00"),
                    "Bailleur", "Loyer mensuel", loyer, PaymentMode.DIRECT_DEBIT, true, true);

            // Courses, plusieurs fois dans le mois
            transaction(courant, monthStart.plusDays(5), new BigDecimal("-64.30"),
                    "Carrefour", "Courses de la semaine", supermarche, PaymentMode.CARD, true, false);
            transaction(courant, monthStart.plusDays(14), new BigDecimal("-48.90"),
                    "Carrefour", "Courses de la semaine", supermarche, PaymentMode.CARD, true, false);
            transaction(courant, monthStart.plusDays(21), new BigDecimal("-52.10"),
                    "Carrefour", "Courses de la semaine", supermarche, PaymentMode.CARD, true, false);

            // Restaurant, occasionnel
            transaction(courant, monthStart.plusDays(9), new BigDecimal("-38.50"),
                    "Le Bistrot", "Déjeuner", restaurant, PaymentMode.CARD, true, false);

            // Netflix, abonnement mensuel recurrent
            transaction(carte, monthStart.plusDays(1), new BigDecimal("-15.00"),
                    "Netflix", "Abonnement", abonnements, PaymentMode.DIRECT_DEBIT, true, true);

            // Energie
            transaction(courant, monthStart.plusDays(10), new BigDecimal("-72.40"),
                    "Fournisseur énergie", "Facture électricité/gaz", energie, PaymentMode.DIRECT_DEBIT, true, true);

            // Transport
            transaction(courant, monthStart.plusDays(3), new BigDecimal("-45.00"),
                    "Réseau transport", "Pass mensuel", transport, PaymentMode.CARD, true, false);

            // Sur le mois en cours uniquement : quelques transactions "en attente"
            // (comme si elles venaient d'etre importees), pour illustrer le
            // rapprochement bancaire.
            if (isCurrentMonth) {
                transaction(courant, today.minusDays(1), new BigDecimal("-19.90"),
                        "Boulangerie du coin", null, alimentation, PaymentMode.CARD, false, false);
                transaction(courant, today, new BigDecimal("-9.50"),
                        "Station essence", null, transport, PaymentMode.CARD, false, false);
            }
        }

        // Petit virement interne courant -> epargne, le mois dernier
        LocalDate transferDate = today.minusMonths(1).withDayOfMonth(15);
        Transaction debit = new Transaction();
        debit.setAccount(courant);
        debit.setDate(transferDate);
        debit.setAmount(new BigDecimal("-200.00"));
        debit.setPayee("Virement vers Livret A");
        debit.setPaymentMode(PaymentMode.TRANSFER);
        debit.setStatus(TransactionStatus.NONE);
        debit.setValidated(true);
        Transaction savedDebit = transactionRepository.save(debit);

        Transaction credit = new Transaction();
        credit.setAccount(epargne);
        credit.setDate(transferDate);
        credit.setAmount(new BigDecimal("200.00"));
        credit.setPayee("Virement depuis Compte courant");
        credit.setPaymentMode(PaymentMode.TRANSFER);
        credit.setStatus(TransactionStatus.NONE);
        credit.setValidated(true);
        credit.setTransferTransaction(savedDebit);
        Transaction savedCredit = transactionRepository.save(credit);
        savedDebit.setTransferTransaction(savedCredit);
        transactionRepository.save(savedDebit);

        // --- Un pret immobilier de demonstration ---
        Loan loan = new Loan();
        loan.setName("Prêt immobilier - Résidence principale");
        loan.setPrincipal(new BigDecimal("220000.00"));
        loan.setAnnualRate(new BigDecimal("3.250"));
        loan.setTermMonths(240);
        loan.setStartDate(today.minusYears(3).withDayOfMonth(5));
        loan.setMonthlyPayment(new BigDecimal("1254.32"));
        loan.setMonthlyInsurance(new BigDecimal("38.50"));
        loan.setNotes("Donnée de démonstration (profil test)");
        loanRepository.save(loan);

        System.out.println(">>> Données de démonstration chargées : "
                + accountRepository.count() + " comptes, "
                + categoryRepository.count() + " catégories, "
                + payeeRepository.count() + " tiers, "
                + transactionRepository.count() + " transactions, "
                + loanRepository.count() + " prêt(s).");
    }

    private Account account(String name, AccountType type, BigDecimal initialBalance) {
        Account a = new Account();
        a.setName(name);
        a.setType(type);
        a.setInitialBalance(initialBalance);
        a.setCurrency("EUR");
        return accountRepository.save(a);
    }

    private Category rootCategory(String name, Category.CategoryType type) {
        Category c = new Category();
        c.setName(name);
        c.setType(type);
        return categoryRepository.save(c);
    }

    private Category childCategory(String name, Category parent) {
        Category c = new Category();
        c.setName(name);
        c.setType(parent.getType());
        c.setParent(parent);
        return categoryRepository.save(c);
    }

    private void payee(String name, String pattern, BigDecimal amount, Category category) {
        Payee p = new Payee();
        p.setName(name);
        p.setPattern(pattern);
        p.setAmount(amount);
        p.setCategory(category);
        payeeRepository.save(p);
    }

    private Transaction transaction(Account account, LocalDate date, BigDecimal amount, String payee,
                                     String memo, Category category, PaymentMode mode,
                                     boolean validated, boolean monthlyExpense) {
        Transaction t = new Transaction();
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(amount);
        t.setPayee(payee);
        t.setMemo(memo);
        t.setCategory(category);
        t.setPaymentMode(mode);
        t.setStatus(TransactionStatus.NONE);
        t.setValidated(validated);
        t.setMonthlyExpense(monthlyExpense);
        return transactionRepository.save(t);
    }
}

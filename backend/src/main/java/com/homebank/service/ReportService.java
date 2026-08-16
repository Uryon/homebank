package com.homebank.service;

import com.homebank.model.Account;
import com.homebank.model.Category;
import com.homebank.model.Transaction;
import com.homebank.repository.AccountRepository;
import com.homebank.repository.TransactionRepository;
import com.homebank.utils.PaymentMode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public ReportService(TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    private List<Transaction> loadTransactions(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);
        // Les virements entre comptes ne sont ni une depense ni un revenu reel.
        return transactions.stream()
                .filter(t -> t.getPaymentMode() != PaymentMode.TRANSFER)
                .toList();
    }

    /**
     * Repartition des depenses (montants negatifs) par categorie racine,
     * sur une periode donnee. Renvoie une liste triee par montant decroissant.
     * Les virements entre comptes sont exclus.
     */
    public List<Map<String, Object>> expensesByCategory(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = loadTransactions(from, to, accountId);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            if (t.getAmount().signum() >= 0) continue; // on ne garde que les depenses
            String label = categoryLabel(t.getCategory());
            totals.merge(label, t.getAmount().abs(), BigDecimal::add);
        }

        BigDecimal grandTotal = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return totals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", e.getKey());
                    row.put("total", e.getValue());
                    row.put("percentage", grandTotal.signum() == 0
                            ? BigDecimal.ZERO
                            : e.getValue().multiply(BigDecimal.valueOf(100))
                                    .divide(grandTotal, 1, RoundingMode.HALF_UP));
                    return row;
                })
                .collect(Collectors.toList());
    }

    /** Idem, mais pour les revenus (montants positifs). Les virements sont exclus. */
    public List<Map<String, Object>> incomeByCategory(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = loadTransactions(from, to, accountId);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            if (t.getAmount().signum() <= 0) continue;
            String label = categoryLabel(t.getCategory());
            totals.merge(label, t.getAmount(), BigDecimal::add);
        }

        return totals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", e.getKey());
                    row.put("total", e.getValue());
                    return row;
                })
                .collect(Collectors.toList());
    }

    /**
     * Evolution du solde jour par jour sur une periode, pour un compte
     * (ou tous les comptes confondus si accountId est null). Les virements
     * sont inclus ici car ils modifient bien le solde reel du compte.
     */
    public List<Map<String, Object>> balanceEvolution(LocalDate from, LocalDate to, Long accountId) {
        List<Account> accounts = accountId != null
                ? List.of(accountRepository.findById(accountId).orElseThrow())
                : accountRepository.findAll();

        BigDecimal startingBalance = BigDecimal.ZERO;
        for (Account a : accounts) {
            startingBalance = startingBalance.add(a.getInitialBalance());
            startingBalance = startingBalance.add(
                    transactionRepository.sumAmountByAccountIdAsOf(a.getId(), from.minusDays(1)));
        }

        List<Transaction> periodTransactions = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);

        Map<LocalDate, BigDecimal> dailyNet = new TreeMap<>();
        for (Transaction t : periodTransactions) {
            dailyNet.merge(t.getDate(), t.getAmount(), BigDecimal::add);
        }

        List<Map<String, Object>> series = new ArrayList<>();
        BigDecimal running = startingBalance;
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            running = running.add(dailyNet.getOrDefault(d, BigDecimal.ZERO));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", d.format(fmt));
            point.put("balance", running);
            series.add(point);
        }
        return series;
    }

    /** Resume global : revenus, depenses et net sur la periode. Les virements sont exclus. */
    public Map<String, Object> summary(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = loadTransactions(from, to, accountId);

        BigDecimal income = transactions.stream()
                .map(Transaction::getAmount)
                .filter(a -> a.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expenses = transactions.stream()
                .map(Transaction::getAmount)
                .filter(a -> a.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("income", income);
        result.put("expenses", expenses.abs());
        result.put("net", income.add(expenses));
        return result;
    }

    /**
     * Epargne mensuelle (revenus - depenses, virements exclus) sur les N
     * derniers mois entierement termines.
     */
    public List<Map<String, Object>> monthlySavings(int monthsBack, Long accountId) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate firstOfCurrentMonth = LocalDate.now().withDayOfMonth(1);

        for (int i = monthsBack; i >= 1; i--) {
            LocalDate monthStart = firstOfCurrentMonth.minusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            Map<String, Object> s = summary(monthStart, monthEnd, accountId);

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", monthStart.toString());
            point.put("label", monthLabel(monthStart));
            point.put("savings", s.get("net"));
            result.add(point);
        }
        return result;
    }

    /**
     * Solde mensuel sur 12 mois historiques (deja termines) + le mois en
     * cours (point "present") + une projection sur 12 mois futurs
     * ("previsionnel"), basee sur la variation mensuelle moyenne observee
     * sur les 12 derniers mois. Chaque point precise son "type"
     * (historique | present | previsionnel) pour permettre au frontend de
     * les distinguer visuellement.
     */
    public List<Map<String, Object>> balanceForecast(Long accountId) {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate firstOfCurrentMonth = today.withDayOfMonth(1);

        List<BigDecimal> historicalBalances = new ArrayList<>();
        for (int i = 12; i >= 1; i--) {
            LocalDate monthStart = firstOfCurrentMonth.minusMonths(i);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            BigDecimal balance = balanceAsOf(monthEnd, accountId);
            historicalBalances.add(balance);
            result.add(point(monthEnd, balance, "historique"));
        }

        BigDecimal presentBalance = balanceAsOf(today, accountId);
        result.add(point(today, presentBalance, "present"));

        BigDecimal avgMonthlyNet = BigDecimal.ZERO;
        if (historicalBalances.size() >= 2) {
            BigDecimal totalChange = historicalBalances.get(historicalBalances.size() - 1)
                    .subtract(historicalBalances.get(0));
            avgMonthlyNet = totalChange.divide(
                    BigDecimal.valueOf(historicalBalances.size() - 1), 2, RoundingMode.HALF_UP);
        }

        BigDecimal projected = presentBalance;
        for (int i = 1; i <= 12; i++) {
            projected = projected.add(avgMonthlyNet);
            LocalDate futureMonthEnd = firstOfCurrentMonth.plusMonths(i).plusMonths(1).minusDays(1);
            result.add(point(futureMonthEnd, projected, "previsionnel"));
        }

        return result;
    }

    /** Somme des transactions marquees "depense mensuelle" (une ligne par tiers, la plus recente). */
    public Map<String, Object> monthlyExpensesSummary() {
        List<Transaction> flagged = transactionRepository.findByMonthlyExpenseTrueOrderByDateDescIdDesc();

        Map<String, Transaction> latestByPayee = new LinkedHashMap<>();
        for (Transaction t : flagged) {
            String key = (t.getPayee() != null ? t.getPayee().toLowerCase(Locale.ROOT) : "id-" + t.getId());
            latestByPayee.putIfAbsent(key, t);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction t : latestByPayee.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("payee", t.getPayee());
            item.put("amount", t.getAmount().abs());
            items.add(item);
            total = total.add(t.getAmount().abs());
        }
        items.sort((a, b) -> ((BigDecimal) b.get("amount")).compareTo((BigDecimal) a.get("amount")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        return result;
    }

    private BigDecimal balanceAsOf(LocalDate date, Long accountId) {
        List<Account> accounts = accountId != null
                ? List.of(accountRepository.findById(accountId).orElseThrow())
                : accountRepository.findAll();

        BigDecimal total = BigDecimal.ZERO;
        for (Account a : accounts) {
            total = total.add(a.getInitialBalance())
                    .add(transactionRepository.sumAmountByAccountIdAsOf(a.getId(), date));
        }
        return total;
    }

    private Map<String, Object> point(LocalDate date, BigDecimal balance, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("label", monthLabel(date));
        m.put("balance", balance);
        m.put("type", type);
        return m;
    }

    private String monthLabel(LocalDate date) {
        String month = date.getMonth().getDisplayName(TextStyle.SHORT, Locale.FRENCH);
        return Character.toUpperCase(month.charAt(0)) + month.substring(1) + " " + date.getYear();
    }

    private String categoryLabel(Category category) {
        if (category == null) return "Non categorise";
        if (category.getParent() != null) {
            return category.getParent().getName() + " : " + category.getName();
        }
        return category.getName();
    }
}

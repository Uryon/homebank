package com.homebank.service;

import com.homebank.model.Account;
import com.homebank.model.Category;
import com.homebank.model.Transaction;
import com.homebank.repository.AccountRepository;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    /**
     * Repartition des depenses (montants negatifs) par categorie racine,
     * sur une periode donnee. Renvoie une liste triee par montant decroissant.
     */
    public List<Map<String, Object>> expensesByCategory(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            if (t.getAmount().signum() >= 0) continue; // on ne garde que les depenses
            String label = categoryLabel(t.getCategory());
            totals.merge(label, t.getAmount().abs(), BigDecimal::add);
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

    /** Idem, mais pour les revenus (montants positifs) */
    public List<Map<String, Object>> incomeByCategory(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);

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
     * (ou tous les comptes confondus si accountId est null).
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

    /** Resume global : solde total, revenus et depenses sur la periode */
    public Map<String, Object> summary(LocalDate from, LocalDate to, Long accountId) {
        List<Transaction> transactions = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);

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

    private String categoryLabel(Category category) {
        if (category == null) return "Non categorise";
        if (category.getParent() != null) {
            return category.getParent().getName() + " : " + category.getName();
        }
        return category.getName();
    }
}

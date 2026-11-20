package com.homebank.service;

import com.homebank.model.Compte;
import com.homebank.model.Tiers;
import com.homebank.model.Transaction;
import com.homebank.utils.ModePaiement;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Entites minimales partagees par les tests des services de transactions. */
final class FabriqueTransactions {

    private FabriqueTransactions() {
    }

    static Tiers payee(Long id, String name) {
        Tiers p = new Tiers();
        p.setId(id);
        p.setName(name);
        return p;
    }

    static Transaction transaction(Tiers payee, LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.setPayee(payee);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setPaymentMode(ModePaiement.NONE);
        t.setPeriodicExpense(false);
        return t;
    }

    static Compte compte(Long id) {
        Compte c = new Compte();
        c.setId(id);
        return c;
    }

    static Transaction transaction(Compte account, Tiers payee, LocalDate date, String amount) {
        Transaction t = transaction(payee, date, amount);
        t.setAccount(account);
        return t;
    }

    static Tiers payeeAvecMotif(Long id, String name, String pattern) {
        Tiers p = payee(id, name);
        p.setPattern(pattern);
        return p;
    }
}

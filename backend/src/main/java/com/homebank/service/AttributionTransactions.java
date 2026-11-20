package com.homebank.service;

import com.homebank.model.Categorie;
import com.homebank.model.Transaction;
import com.homebank.repository.DepotTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Socle commun des rapports du tableau de bord (voir ServiceRapport et
 * ServiceAnalyseMensuelle) : transactions comptees dans les rapports et mois
 * auquel chacune est attribuee — une categorie "annuelle" est lissee sur 12
 * mois, les virements, ajustements de solde et transactions exceptionnelles
 * sont exclus.
 */
@Component
public class AttributionTransactions {

    /** Sens d'un montant dans les rapports (revenu ou depense). */
    static final String INCOME = "income";
    static final String EXPENSE = "expense";

    private final DepotTransaction transactionRepository;

    public AttributionTransactions(DepotTransaction transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Une transaction (reelle) attribuee a un mois donne pour un montant
     * donne — en general la transaction elle-meme sur sa propre date, sauf
     * pour une transaction dont la categorie est marquee "annuelle" (voir
     * Categorie#annualized) qui genere une ligne par mois de la fenetre
     * glissante de 12 mois demarrant a son propre mois de paiement, chacune
     * pour 1/12 de son montant (voir {@link #attributedTransactions}).
     */
    record AttributedTransaction(Transaction source, LocalDate date, BigDecimal amount) {}

    /** Une categorie (null = sans categorie) sur un mois : l'unite de calcul des rapports par categorie. */
    record CategorieMois(Long categoryId, YearMonth month) {
        static CategorieMois de(AttributedTransaction at) {
            Categorie category = at.source().getCategory();
            return new CategorieMois(category != null ? category.getId() : null, YearMonth.from(at.date()));
        }

        String categoryKey() {
            return categoryId != null ? categoryId.toString() : "uncategorized";
        }
    }

    /**
     * Net de chaque categorie sur chaque mois. Regle commune a tous les
     * rapports par categorie : un remboursement vient en deduction des depenses
     * de sa categorie sur le meme mois, et le signe du net decide si ce mois
     * compte en depense ou en revenu. Sans elle, la repartition des depenses et
     * le detail d'une cellule comptaient le montant brut, different de celui
     * du tableau d'analyse.
     */
    static Map<CategorieMois, BigDecimal> netByCategoryMonth(List<AttributedTransaction> transactions) {
        Map<CategorieMois, BigDecimal> nets = new LinkedHashMap<>();
        for (AttributedTransaction at : transactions) {
            nets.merge(CategorieMois.de(at), at.amount(), BigDecimal::add);
        }
        return nets;
    }

    /** Categorie derriere chaque identifiant present (cle null : sans categorie). */
    static Map<Long, Categorie> categoriesById(List<AttributedTransaction> transactions) {
        Map<Long, Categorie> categories = new HashMap<>();
        for (AttributedTransaction at : transactions) {
            categories.putIfAbsent(CategorieMois.de(at).categoryId(), at.source().getCategory());
        }
        return categories;
    }

    /** Vrai si la transaction est affectee a une categorie marquee "annuelle" (voir Categorie#annualized). */
    private boolean hasAnnualizedCategory(Transaction t) {
        return t.getCategory() != null && t.getCategory().isAnnualized();
    }

    /**
     * Transactions "attribuables" a la periode [from,to] : les transactions
     * normales, sur leur propre date reelle ; et, pour toute transaction dont
     * la categorie est marquee "annuelle" (ex : taxe fonciere payee en une
     * fois mais qui couvre toute l'annee), une ligne par mois de la fenetre
     * glissante de 12 mois qui commence a son propre mois de paiement (ex :
     * payee en octobre 2025 => repartie d'octobre 2025 a septembre 2026
     * inclus) qui tombe dans la periode — chacune pour 1/12 de son montant —
     * meme quand la date reelle du paiement elle-meme est hors periode. Une
     * transaction d'une categorie annuelle ne compte donc jamais en une fois
     * sur son seul mois de paiement : uniquement lissee sur les 12 mois
     * suivants. Les points "historique"/"previsionnel" de balanceForecast
     * restent bases sur le solde reel des comptes (balanceAsOf, jamais lisse) ;
     * seule sa tendance mensuelle moyenne (avgMonthlyNet) passe par monthlyNets
     * et donc par cette methode.
     * <p>
     * Une transaction marquee "exceptionnelle" (voir Transaction#exceptional)
     * est totalement exclue de attributedTransactions, quel que soit le
     * caractere annuel de sa categorie, pour ne jamais fausser les moyennes/
     * tendances des tableaux de bord (ex : achat d'une voiture).
     */
    List<AttributedTransaction> attributedTransactions(LocalDate from, LocalDate to, Long accountId) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        List<AttributedTransaction> result = new ArrayList<>();

        List<Transaction> inRange = accountId != null
                ? transactionRepository.findByAccountIdAndDateBetween(accountId, from, to)
                : transactionRepository.findByDateBetween(from, to);
        for (Transaction t : inRange) {
            addIfAttributable(t, today, result);
        }

        for (Transaction t : transactionRepository.findByCategory_AnnualizedTrue()) {
            addAnnualizedShares(t, today, from, to, accountId, result);
        }

        return result;
    }

    /** Vrai si t doit etre exclue de tous les rapports, quelle que soit sa categorie : virement interne, ajustement de solde, transaction future, ou marquee exceptionnelle. */
    static boolean isExcludedFromReports(Transaction t, LocalDate today) {
        return t.isInternalTransfer() || t.isBalanceAdjustment() || t.getDate().isAfter(today) || t.isExceptional();
    }

    /** Ajoute t a result s'il est attribuable normalement (voir attributedTransactions) : les categories annuelles sont traitees a part par addAnnualizedShares, jamais ici pour leur montant plein. */
    private void addIfAttributable(Transaction t, LocalDate today, List<AttributedTransaction> result) {
        if (isExcludedFromReports(t, today)) return;
        if (hasAnnualizedCategory(t)) return;
        result.add(new AttributedTransaction(t, t.getDate(), t.getAmount()));
    }

    /** Ajoute a result une ligne par mois de la fenetre glissante de 12 mois de t qui tombe dans [from,to] (voir attributedTransactions), pour le compte filtre (accountId==null = tous). */
    private void addAnnualizedShares(Transaction t, LocalDate today, LocalDate from, LocalDate to, Long accountId, List<AttributedTransaction> result) {
        if (isExcludedFromReports(t, today)) return;
        if (accountId != null && (t.getAccount() == null || !accountId.equals(t.getAccount().getId()))) return;

        LocalDate firstMonth = t.getDate().withDayOfMonth(1);
        for (int i = 0; i < ANNUALIZED_MONTHS; i++) {
            LocalDate monthStart = firstMonth.plusMonths(i);
            if (monthStart.isBefore(from) || monthStart.isAfter(to)) continue;
            result.add(new AttributedTransaction(t, monthStart, annualizedShare(t.getAmount(), i)));
        }
    }

    private static final int ANNUALIZED_MONTHS = 12;


    /**
     * Part du mois `monthIndex` (0 a 11) d'un montant lisse sur 12 mois : 1/12
     * arrondi au centime, sauf le dernier mois qui prend le reste. Sans ce
     * reste, 12 parts arrondies ne redonnaient pas le montant (100,00 ->
     * 12 x 8,33 = 99,96) et les totaux sur l'annee derivaient de quelques centimes.
     */
    static BigDecimal annualizedShare(BigDecimal amount, int monthIndex) {
        BigDecimal share = amount.divide(BigDecimal.valueOf(ANNUALIZED_MONTHS), 2, RoundingMode.HALF_UP);
        if (monthIndex < ANNUALIZED_MONTHS - 1) return share;
        return amount.subtract(share.multiply(BigDecimal.valueOf(ANNUALIZED_MONTHS - 1L)));
    }

    /**
     * Premier jour du mois "courant" a utiliser comme ancre pour toutes les
     * fenetres de mois des rapports (analyse mensuelle, epargne, solde
     * previsionnel) : referenceMonth + 1 mois si fourni (navigation depuis
     * le tableau de bord, voir Dashboard.vue), sinon le mois calendaire en
     * cours. Chaque rapport reconstruit ensuite sa fenetre de mois EXACTEMENT
     * comme avant (derniers mois avant cette ancre), ce qui garantit que
     * referenceMonth omis reproduit le comportement historique (dernier mois
     * calendaire deja termine).
     */
    static LocalDate anchorMonth(LocalDate referenceMonth) {
        return referenceMonth != null
                ? referenceMonth.withDayOfMonth(1).plusMonths(1)
                : LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1);
    }
}

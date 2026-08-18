package com.homebank.service;

import com.homebank.dto.TransactionDTO;
import com.homebank.dto.TransferRequestDTO;
import com.homebank.mapper.TransactionMapper;
import com.homebank.model.Account;
import com.homebank.model.Category;
import com.homebank.model.Payee;
import com.homebank.model.Transaction;
import com.homebank.repository.TransactionRepository;
import com.homebank.utils.PaymentMode;
import com.homebank.utils.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final PayeeService payeeService;
    private final TransactionMapper transactionMapper;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountService accountService,
            CategoryService categoryService,
            PayeeService payeeService,
            TransactionMapper transactionMapper) {

        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.payeeService = payeeService;
        this.transactionMapper = transactionMapper;
    }

    public List<TransactionDTO> findAll() {
        return transactionRepository.findAllByOrderByDateDescIdDesc()
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public List<TransactionDTO> findByAccount(Long accountId) {
        return transactionRepository
                .findByAccount_IdOrderByDateDescIdDesc(accountId)
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public List<TransactionDTO> findByPeriod(LocalDate from, LocalDate to) {
        return transactionRepository
                .findByDateBetween(from, to)
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    /**
     * Recherche de transactions par montant, avec un operateur de comparaison
     * (">", "<" ou "=") et optionnellement un compte pour restreindre la
     * recherche — utilisee par le champ de recherche de l'onglet Transactions
     * (ex : "> 100", "< 50", "= 42.50"). Filtree directement en base plutot
     * que de charger toutes les transactions cote frontend pour les filtrer
     * ensuite en Javascript.
     */
    public List<TransactionDTO> findByAmountFilter(Long accountId, String operator, BigDecimal amount) {
        List<Transaction> results = switch (operator) {
            case ">" -> accountId != null
                    ? transactionRepository.findByAccount_IdAndAmountGreaterThanOrderByDateDescIdDesc(accountId, amount)
                    : transactionRepository.findByAmountGreaterThanOrderByDateDescIdDesc(amount);
            case "<" -> accountId != null
                    ? transactionRepository.findByAccount_IdAndAmountLessThanOrderByDateDescIdDesc(accountId, amount)
                    : transactionRepository.findByAmountLessThanOrderByDateDescIdDesc(amount);
            case "=" -> accountId != null
                    ? transactionRepository.findByAccount_IdAndAmountOrderByDateDescIdDesc(accountId, amount)
                    : transactionRepository.findByAmountOrderByDateDescIdDesc(amount);
            default -> throw new IllegalArgumentException("Operateur de montant invalide : " + operator);
        };
        return results.stream().map(transactionMapper::toDTO).toList();
    }

    public List<TransactionDTO> findPending() {
        return transactionRepository.findByValidatedFalseOrderByDateDescIdDesc()
                .stream()
                .map(transactionMapper::toDTO)
                .toList();
    }

    public long countPending() {
        return transactionRepository.countByValidatedFalse();
    }

    public TransactionDTO findById(Long id) {
        Transaction transaction = findEntityById(id);
        return transactionMapper.toDTO(transaction);
    }

    private Transaction findEntityById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Transaction introuvable : " + id
                        ));
    }

    public TransactionDTO create(TransactionDTO payload) {
        Transaction transaction = new Transaction();
        applyPayload(transaction, payload);
        Transaction saved = transactionRepository.save(transaction);
        return transactionMapper.toDTO(saved);
    }

    public TransactionDTO update(Long id, TransactionDTO payload) {
        Transaction existing = findEntityById(id);
        String previousPayeeText = existing.getPayee();
        BigDecimal previousAmount = existing.getAmount();

        applyPayload(existing, payload);
        Transaction saved = transactionRepository.save(existing);

        // Nettoyage de l'ancien tiers "best effort" : ne doit jamais faire
        // echouer la sauvegarde de la transaction elle-meme (deja actee a ce
        // stade), meme en cas de probleme inattendu sur ce nettoyage annexe.
        try {
            payeeService.cleanupIfPayeeChanged(previousPayeeText, previousAmount, saved.getPayee(), saved.getAmount());
        } catch (Exception e) {
            log.warn("Echec du nettoyage de l'ancien tiers apres modification de la transaction {} : {}", id, e.getMessage(), e);
        }

        return transactionMapper.toDTO(saved);
    }

    public void delete(Long id) {
        Transaction existing = findEntityById(id);
        String payeeText = existing.getPayee();
        BigDecimal amount = existing.getAmount();

        transactionRepository.deleteById(id);

        try {
            payeeService.cleanupIfPayeeChanged(payeeText, amount, null, null);
        } catch (Exception e) {
            log.warn("Echec du nettoyage de l'ancien tiers apres suppression de la transaction {} : {}", id, e.getMessage(), e);
        }
    }

    /** Passe toutes les transactions en attente au statut "validee". Renvoie le nombre concerne. */
    @Transactional
    public int validateAllPending() {
        return transactionRepository.validateAllPending();
    }

    /** Bascule le statut valide/en attente d'une seule transaction. */
    public TransactionDTO toggleValidated(Long id) {
        Transaction t = findEntityById(id);
        t.setValidated(!t.isValidated());
        return transactionMapper.toDTO(transactionRepository.save(t));
    }

    /** Bascule le statut "depense mensuelle" d'une transaction. */
    public TransactionDTO toggleMonthlyExpense(Long id) {
        Transaction t = findEntityById(id);
        t.setMonthlyExpense(!t.isMonthlyExpense());
        return transactionMapper.toDTO(transactionRepository.save(t));
    }

    /**
     * Cree un virement entre deux comptes : deux transactions liees
     * (debit sur le compte source, credit sur le compte destination),
     * chacune reliee a l'autre via transferTransaction, et marquees
     * PaymentMode.TRANSFER pour etre exclues des rapports de depenses/revenus.
     */
    @Transactional
    public List<TransactionDTO> createTransfer(TransferRequestDTO request) {
        if (request.getFromAccountId() == null || request.getToAccountId() == null) {
            throw new IllegalArgumentException("Les deux comptes sont obligatoires");
        }
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new IllegalArgumentException("Le compte source et le compte destination doivent etre differents");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Le montant du virement doit etre positif");
        }

        Account from = accountService.findById(request.getFromAccountId());
        Account to = accountService.findById(request.getToAccountId());
        LocalDate date = request.getDate() != null ? request.getDate() : LocalDate.now();
        BigDecimal amount = request.getAmount().abs();

        Transaction debit = new Transaction();
        debit.setAccount(from);
        debit.setDate(date);
        debit.setAmount(amount.negate());
        debit.setPayee("Virement vers " + to.getName());
        debit.setMemo(request.getMemo());
        debit.setPaymentMode(PaymentMode.TRANSFER);
        debit.setStatus(TransactionStatus.NONE);
        debit.setValidated(true);

        Transaction credit = new Transaction();
        credit.setAccount(to);
        credit.setDate(date);
        credit.setAmount(amount);
        credit.setPayee("Virement depuis " + from.getName());
        credit.setMemo(request.getMemo());
        credit.setPaymentMode(PaymentMode.TRANSFER);
        credit.setStatus(TransactionStatus.NONE);
        credit.setValidated(true);

        Transaction savedDebit = transactionRepository.save(debit);
        credit.setTransferTransaction(savedDebit);
        Transaction savedCredit = transactionRepository.save(credit);
        savedDebit.setTransferTransaction(savedCredit);
        Transaction finalDebit = transactionRepository.save(savedDebit);

        return List.of(transactionMapper.toDTO(finalDebit), transactionMapper.toDTO(savedCredit));
    }

    private void applyPayload(Transaction transaction, TransactionDTO payload) {

        if (payload.getAccountId() == null) {
            throw new IllegalArgumentException(
                    "Le compte est obligatoire"
            );
        }

        Account account = accountService.findById(payload.getAccountId());
        transaction.setAccount(account);

        transaction.setDate(payload.getDate());
        transaction.setAmount(payload.getAmount());

        // Le tiers saisi/modifie passe par la meme reconnaissance/creation
        // automatique que l'import (voir PayeeService#resolveOrCreate), afin
        // que l'onglet "Tiers" reste toujours a jour avec les tiers realement
        // utilises sur les transactions, meme lors d'une saisie manuelle.
        String payeeText = payload.getPayee();
        if (payeeText != null && !payeeText.isBlank()) {
            Payee payee = payeeService.resolveOrCreate(payeeText, payload.getAmount());
            transaction.setPayee(payee != null ? payee.getName() : payeeText);
        } else {
            transaction.setPayee(payeeText);
        }

        transaction.setMemo(sanitizeMemo(payload.getMemo()));
        transaction.setPaymentMode(payload.getPaymentMode());
        transaction.setStatus(payload.getStatus());
        transaction.setTags(payload.getTags());
        transaction.setMonthlyExpense(payload.isMonthlyExpense());
        transaction.setExternalId(payload.getExternalId());
        // Une transaction saisie/modifiee manuellement est consideree comme validee ;
        // seules les transactions importees/synchronisees demarrent "en attente".
        transaction.setValidated(payload.isValidated());

        if (payload.getCategoryId() != null) {
            Category category =
                    categoryService.findById(payload.getCategoryId());

            transaction.setCategory(category);
        } else {
            transaction.setCategory(null);
        }
    }

    /** Remplace un memo litteralement "(NULL)"/"NULL" ou vide par null. */
    static String sanitizeMemo(String memo) {
        if (memo == null) return null;
        String trimmed = memo.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("(NULL)") || trimmed.equalsIgnoreCase("NULL")) {
            return null;
        }
        return trimmed;
    }
}

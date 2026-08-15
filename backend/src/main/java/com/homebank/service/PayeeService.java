package com.homebank.service;

import com.homebank.dto.PayeeDTO;
import com.homebank.mapper.PayeeMapper;
import com.homebank.model.Category;
import com.homebank.model.Payee;
import com.homebank.repository.PayeeRepository;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class PayeeService {

    private final PayeeRepository payeeRepository;
    private final CategoryService categoryService;
    private final PayeeMapper payeeMapper;
    private final TransactionRepository transactionRepository;

    public PayeeService(PayeeRepository payeeRepository, CategoryService categoryService,
                         PayeeMapper payeeMapper, TransactionRepository transactionRepository) {
        this.payeeRepository = payeeRepository;
        this.categoryService = categoryService;
        this.payeeMapper = payeeMapper;
        this.transactionRepository = transactionRepository;
    }

    public List<PayeeDTO> findAll() {
        return payeeRepository.findAll()
                .stream()
                .map(payeeMapper::toDTO)
                .toList();
    }

    public PayeeDTO findById(Long id) {
        return payeeMapper.toDTO(findEntityById(id));
    }

    private Payee findEntityById(Long id) {
        return payeeRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Tiers introuvable : " + id));
    }

    public PayeeDTO create(PayeeDTO payload) {
        Payee p = new Payee();
        applyPayload(p, payload);
        return payeeMapper.toDTO(payeeRepository.save(p));
    }

    public PayeeDTO update(Long id, PayeeDTO payload) {
        Payee existing = findEntityById(id);
        applyPayload(existing, payload);
        return payeeMapper.toDTO(payeeRepository.save(existing));
    }

    public void delete(Long id) {
        payeeRepository.deleteById(id);
    }

    private void applyPayload(Payee p, PayeeDTO payload) {
        p.setName(payload.getName());
        p.setPattern(payload.getPattern());
        p.setAmount(payload.getAmount() != null ? payload.getAmount().abs() : null);
        if (payload.getCategoryId() != null) {
            Category category = categoryService.findById(payload.getCategoryId());
            p.setCategory(category);
        } else {
            p.setCategory(null);
        }
    }

    /**
     * Cherche, parmi tous les tiers connus, celui qui correspond le mieux au
     * texte et au montant donnes (ex : le champ "payee" et le montant d'une
     * transaction importee).
     *
     * Deux tiers peuvent porter le meme texte reconnu mais correspondre a des
     * montants differents (ex : "Bouygues" a 10E vs 20E) : on les considere
     * comme des tiers DIFFERENTS. Un tiers sans montant renseigne (amount == null)
     * est un tiers "generique" qui matche quel que soit le montant, et sert de
     * repli si aucun tiers avec le montant exact n'est trouve.
     *
     * @param amount montant en valeur absolue (le signe depense/revenu n'entre
     *               pas en compte dans la distinction des tiers)
     */
    public Optional<Payee> matchPayee(String text, BigDecimal amount) {
        if (text == null || text.isBlank()) return Optional.empty();
        Payee genericMatch = null;

        for (Payee payee : payeeRepository.findAll()) {
            try {
                Pattern pattern = Pattern.compile(payee.getPattern(), Pattern.CASE_INSENSITIVE);
                if (!pattern.matcher(text).find()) continue;

                if (payee.getAmount() == null) {
                    if (genericMatch == null) genericMatch = payee;
                } else if (amount != null && payee.getAmount().compareTo(amount) == 0) {
                    return Optional.of(payee); // correspondance exacte nom + montant : priorite absolue
                }
            } catch (PatternSyntaxException e) {
                // regexp invalide sur ce tiers : on l'ignore et on continue avec les suivants
            }
        }
        return Optional.ofNullable(genericMatch);
    }

    /**
     * Reconnait un tiers a partir d'un texte brut importe et d'un montant :
     * - si un tiers existant correspond (texte + montant, ou texte + tiers
     *   generique sans montant), on le renvoie tel quel (son nom peut avoir
     *   ete renomme manuellement par l'utilisateur : c'est ce nom "canonique"
     *   qui sera utilise sur la transaction) ;
     * - sinon, un nouveau tiers est cree automatiquement avec pour nom le texte
     *   brut rencontre et pour montant le montant donne, avec une regexp qui
     *   reconnait ce texte exact. Si ce meme texte brut revient plus tard avec
     *   un montant different, un AUTRE tiers distinct sera cree, meme nom
     *   au depart mais que l'utilisateur pourra renommer independamment
     *   (ex : "Bouygues Mel" pour 10E, "Bouygues Val" pour 20E).
     */
    public Payee resolveOrCreate(String rawText, BigDecimal amount) {
        if (rawText == null || rawText.isBlank()) return null;
        String trimmed = rawText.trim();
        BigDecimal normalizedAmount = amount != null ? amount.abs() : null;

        Optional<Payee> matched = matchPayee(trimmed, normalizedAmount);
        if (matched.isPresent()) return matched.get();

        // Filet de securite : evite un doublon si un tiers de meme nom ET meme
        // montant existe deja mais que sa regexp ne matchait pas ce texte precis.
        Optional<Payee> byNameAndAmount = payeeRepository.findByNameIgnoreCaseAndAmount(trimmed, normalizedAmount);
        if (byNameAndAmount.isPresent()) return byNameAndAmount.get();

        Payee created = new Payee();
        created.setName(trimmed);
        created.setPattern(Pattern.quote(trimmed));
        created.setAmount(normalizedAmount);
        return payeeRepository.save(created);
    }

    /**
     * Parcourt toutes les paires (tiers, montant) deja presentes dans les
     * transactions existantes et cree automatiquement un tiers (Payee) pour
     * chacune de celles qui ne sont pas encore connues. Utile pour "amorcer"
     * la liste des tiers apres avoir deja importe des transactions avant
     * l'ajout de cette fonctionnalite. Renvoie un resume (nombre crees / deja connus).
     */
    public Map<String, Integer> initializeFromExistingTransactions() {
        int created = 0;
        int alreadyKnown = 0;

        for (Object[] row : transactionRepository.findAllPayeeAmountPairs()) {
            String rawText = (String) row[0];
            BigDecimal amount = (BigDecimal) row[1];
            if (rawText == null || rawText.isBlank()) continue;

            String trimmed = rawText.trim();
            BigDecimal normalizedAmount = amount != null ? amount.abs() : null;

            boolean existedBefore = matchPayee(trimmed, normalizedAmount).isPresent()
                    || payeeRepository.findByNameIgnoreCaseAndAmount(trimmed, normalizedAmount).isPresent();

            resolveOrCreate(trimmed, normalizedAmount);

            if (existedBefore) {
                alreadyKnown++;
            } else {
                created++;
            }
        }

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("created", created);
        summary.put("alreadyKnown", alreadyKnown);
        return summary;
    }
}

package com.homebank.service;

import com.homebank.dto.PayeeDTO;
import com.homebank.mapper.PayeeMapper;
import com.homebank.model.Category;
import com.homebank.model.Payee;
import com.homebank.repository.PayeeRepository;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;

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
        if (payload.getCategoryId() != null) {
            Category category = categoryService.findById(payload.getCategoryId());
            p.setCategory(category);
        } else {
            p.setCategory(null);
        }
    }

    /**
     * Cherche, parmi tous les tiers connus, le premier dont la regexp
     * correspond au texte donne (ex : le champ "payee" d'une transaction importee).
     */
    public Optional<Payee> matchPayee(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        for (Payee payee : payeeRepository.findAll()) {
            try {
                Pattern pattern = Pattern.compile(payee.getPattern(), Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(text).find()) {
                    return Optional.of(payee);
                }
            } catch (PatternSyntaxException e) {
                // regexp invalide sur ce tiers : on l'ignore et on continue avec les suivants
            }
        }
        return Optional.empty();
    }

    /**
     * Reconnait un tiers a partir d'un texte brut importe (ex : champ "payee"
     * d'une ligne CSV/QIF) :
     * - si un tiers existant correspond (via sa regexp), on le renvoie tel quel
     *   (son nom peut avoir ete renomme manuellement par l'utilisateur : c'est
     *   ce nom "canonique" qui sera utilise sur la transaction) ;
     * - sinon, un nouveau tiers est cree automatiquement avec pour nom le texte
     *   brut rencontre, et une regexp qui reconnait ce texte exact (l'utilisateur
     *   pourra ensuite le renommer et/ou lui affecter une categorie depuis
     *   l'onglet "Tiers" ; ce renommage sera repris automatiquement aux imports
     *   suivants puisque le meme texte brut continuera a matcher cette regexp).
     */
    public Payee resolveOrCreate(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;
        String trimmed = rawText.trim();

        Optional<Payee> matched = matchPayee(trimmed);
        if (matched.isPresent()) return matched.get();

        // Filet de securite : evite un doublon si un tiers du meme nom existe
        // deja mais que sa regexp ne matchait pas ce texte precis.
        Optional<Payee> byName = payeeRepository.findByNameIgnoreCase(trimmed);
        if (byName.isPresent()) return byName.get();

        Payee created = new Payee();
        created.setName(trimmed);
        created.setPattern(Pattern.quote(trimmed));
        return payeeRepository.save(created);
    }

    /**
     * Parcourt tous les tiers deja presents dans les transactions existantes
     * et cree automatiquement un tiers (Payee) pour chacun de ceux qui ne sont
     * pas encore connus. Utile pour "amorcer" la liste des tiers apres avoir
     * deja importe des transactions avant l'ajout de cette fonctionnalite.
     * Renvoie un resume (nombre crees / deja connus).
     */
    public Map<String, Integer> initializeFromExistingTransactions() {
        int created = 0;
        int alreadyKnown = 0;

        for (String rawText : transactionRepository.findDistinctPayees()) {
            if (rawText == null || rawText.isBlank()) continue;
            String trimmed = rawText.trim();

            boolean existedBefore = matchPayee(trimmed).isPresent()
                    || payeeRepository.findByNameIgnoreCase(trimmed).isPresent();

            resolveOrCreate(trimmed);

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

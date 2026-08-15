package com.homebank.service;

import com.homebank.model.Account;
import com.homebank.model.Payee;
import com.homebank.model.Transaction;
import com.homebank.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ImportService {

    private final AccountService accountService;
    private final CategoryService categoryService;
    private final TransactionRepository transactionRepository;
    private final PayeeService payeeService;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yy")
    );

    public ImportService(AccountService accountService, CategoryService categoryService,
                          TransactionRepository transactionRepository, PayeeService payeeService) {
        this.accountService = accountService;
        this.categoryService = categoryService;
        this.transactionRepository = transactionRepository;
        this.payeeService = payeeService;
    }

    /**
     * Applique a la transaction le tiers (nom canonique, cree automatiquement
     * si inconnu) et la categorie a partir du texte brut importe :
     * - le nom stocke sur la transaction est celui du tiers reconnu (donc un
     *   renommage manuel fait depuis l'onglet "Tiers" est repris automatiquement
     *   des le prochain import portant sur le meme texte brut) ;
     * - si le fichier fournit deja une categorie, elle est respectee ;
     *   sinon, la categorie par defaut du tiers (si definie) est appliquee.
     */
    private void applyPayeeAndCategory(Transaction t, String payeeText, String categoryNameFromFile) {
        Payee payee = payeeService.resolveOrCreate(payeeText);
        t.setPayee(payee != null ? payee.getName() : payeeText);

        if (categoryNameFromFile != null && !categoryNameFromFile.isBlank()) {
            t.setCategory(categoryService.findOrCreateByName(categoryNameFromFile));
        } else if (payee != null) {
            t.setCategory(payee.getCategory());
        }
    }

    public static class ImportResult {
        public int imported;
        public int skipped;
        public List<String> errors = new ArrayList<>();
    }

    /**
     * Import d'un fichier CSV au format HomeBank :
     * date;paymode;info;payee;memo;amount;category;tags
     * Le separateur ';' est celui utilise par defaut par HomeBank.
     */
    public ImportResult importCsv(MultipartFile file, Long accountId) {
        Account account = accountService.findById(accountId);
        ImportResult result = new ImportResult();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                // Ligne d'en-tete HomeBank (commence par "date;")
                if (lineNumber == 1 && line.toLowerCase(Locale.ROOT).startsWith("date")) continue;

                String[] fields = line.split(";", -1);
                if (fields.length < 6) {
                    result.skipped++;
                    result.errors.add("Ligne " + lineNumber + " : nombre de colonnes insuffisant");
                    continue;
                }

                try {
                    LocalDate date = parseDate(fields[0].trim());
                    String payee = fields.length > 3 ? fields[3].trim() : "";
                    String memo = fields.length > 4 ? fields[4].trim() : "";
                    BigDecimal amount = parseAmount(fields[5].trim());
                    String categoryName = fields.length > 6 ? fields[6].trim() : "";
                    String tags = fields.length > 7 ? fields[7].trim() : "";

                    Transaction t = new Transaction();
                    t.setAccount(account);
                    t.setDate(date);
                    t.setAmount(amount);
                    t.setMemo(memo);
                    t.setTags(tags);
                    applyPayeeAndCategory(t, payee, categoryName);
                    transactionRepository.save(t);
                    result.imported++;
                } catch (Exception e) {
                    result.skipped++;
                    result.errors.add("Ligne " + lineNumber + " : " + e.getMessage());
                }
            }
        } catch (IOException e) {
            result.errors.add("Erreur de lecture du fichier : " + e.getMessage());
        }

        return result;
    }

    /**
     * Import d'un fichier QIF (Quicken Interchange Format).
     * Champs geres : D (date), T (montant), P (payee), M (memo), L (categorie).
     */
    public ImportResult importQif(MultipartFile file, Long accountId) {
        Account account = accountService.findById(accountId);
        ImportResult result = new ImportResult();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            LocalDate date = null;
            BigDecimal amount = null;
            String payee = null;
            String memo = null;
            String categoryName = null;
            int lineNumber = 0;

            // On ne traite les transactions qu'après !Type:Bank
            boolean inBankSection = false;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) {
                    continue;
                }

                // Gestion des sections QIF
                if (line.startsWith("!")) {
                    inBankSection = line.equalsIgnoreCase("!Type:Bank");
                    continue;
                }

                // On ignore tout ce qui précède !Type:Bank
                if (!inBankSection) {
                    continue;
                }

                char code = line.charAt(0);
                String value = line.length() > 1
                        ? line.substring(1).trim()
                        : "";

                switch (code) {
                    case 'D' -> date = parseDate(value);

                    case 'T', 'U' -> amount = parseAmount(value);

                    case 'P' -> payee = value;

                    case 'M' -> memo = value;

                    case 'L' -> categoryName = value;

                    case '^' -> {
                        // Fin d'enregistrement -> création de la transaction
                        try {
                            if (date == null || amount == null) {
                                throw new IllegalStateException(
                                        "date ou montant manquant"
                                );
                            }

                            Transaction t = new Transaction();
                            t.setAccount(account);
                            t.setDate(date);
                            t.setAmount(amount);
                            t.setMemo(memo);
                            applyPayeeAndCategory(t, payee, categoryName);

                            transactionRepository.save(t);
                            result.imported++;

                        } catch (Exception e) {
                            result.skipped++;
                            result.errors.add(
                                    "Enregistrement terminé ligne "
                                            + lineNumber
                                            + " : "
                                            + e.getMessage()
                            );
                        } finally {
                            // Réinitialisation pour la prochaine transaction
                            date = null;
                            amount = null;
                            payee = null;
                            memo = null;
                            categoryName = null;
                        }
                    }

                    default -> {
                        // Champs QIF non gérés : N, C, A, etc.
                    }
                }
            }

        } catch (IOException e) {
            result.errors.add(
                    "Erreur de lecture du fichier : " + e.getMessage()
            );
        }

        return result;
    }

    private LocalDate parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
                // essai du format suivant
            }
        }
        throw new IllegalArgumentException("Format de date non reconnu : " + raw);
    }

    private BigDecimal parseAmount(String raw) {
        String cleaned = raw.replace(" ", "").replace("'", "");
        // gestion des formats "1.234,56" (europeen) et "1,234.56" (US)
        if (cleaned.contains(",") && cleaned.contains(".")) {
            if (cleaned.indexOf(',') > cleaned.indexOf('.')) {
                cleaned = cleaned.replace(".", "").replace(",", ".");
            } else {
                cleaned = cleaned.replace(",", "");
            }
        } else if (cleaned.contains(",")) {
            cleaned = cleaned.replace(",", ".");
        }
        return new BigDecimal(cleaned);
    }
}

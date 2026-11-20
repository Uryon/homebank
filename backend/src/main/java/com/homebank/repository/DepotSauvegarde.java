package com.homebank.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture et remplacement de toutes les données, table par table, pour la
 * sauvegarde complète (voir ServiceSauvegarde). En SQL direct plutôt qu'en
 * JPA : la restauration doit réinsérer chaque ligne avec son id d'origine (les
 * références entre tables en dépendent), ce que les entités à id généré ne
 * permettent pas. Colonnes listées explicitement (schéma Flyway V1) : une
 * ancienne colonne restée dans une base n'entre jamais dans une sauvegarde.
 */
@Repository
public class DepotSauvegarde {

    /** Nature d'une colonne : conversion entre la base et le fichier JSON. */
    public enum TypeColonne { ID, ENTIER, TEXTE, DECIMAL, BOOLEEN, DATE, HORODATAGE }

    public record Colonne(String nom, TypeColonne type) {}

    /**
     * Table sauvegardée. `autoReference` : colonne qui pointe vers la même
     * table (catégorie parente, jumelle d'un virement), renseignée après
     * l'insertion de toutes les lignes. `identite` : id généré par la base,
     * dont le compteur est recalé après restauration.
     */
    public record Table(String nom, List<Colonne> colonnes, String autoReference, boolean identite) {}

    private static Colonne c(String nom, TypeColonne type) {
        return new Colonne(nom, type);
    }

    /**
     * Registre des lignes de relevé importées (voir LigneImportee), apparu
     * après les premières sauvegardes : absent d'une sauvegarde plus ancienne,
     * il est reconstitué à partir des transactions restaurées.
     */
    public static final String LIGNES_IMPORTEES = "lignes_importees";

    /** Ordre d'insertion : une table n'est insérée qu'après celles qu'elle référence. */
    public static final List<Table> TABLES = List.of(
            new Table("comptes", List.of(c("id", TypeColonne.ID), c("archived", TypeColonne.BOOLEEN),
                    c("currency", TypeColonne.TEXTE), c("initial_balance", TypeColonne.DECIMAL), c("name", TypeColonne.TEXTE),
                    c("notes", TypeColonne.TEXTE), c("type", TypeColonne.TEXTE)), null, true),
            new Table("categories", List.of(c("id", TypeColonne.ID), c("annualized", TypeColonne.BOOLEEN),
                    c("hidden", TypeColonne.BOOLEEN), c("icon", TypeColonne.TEXTE), c("name", TypeColonne.TEXTE),
                    c("type", TypeColonne.TEXTE), c("parent_id", TypeColonne.ID)), "parent_id", true),
            new Table("tiers", List.of(c("id", TypeColonne.ID), c("amount", TypeColonne.DECIMAL), c("name", TypeColonne.TEXTE),
                    c("pattern", TypeColonne.TEXTE), c("category_id", TypeColonne.ID)), null, true),
            new Table("prets", List.of(c("id", TypeColonne.ID), c("annual_rate", TypeColonne.DECIMAL),
                    c("monthly_insurance", TypeColonne.DECIMAL), c("monthly_payment", TypeColonne.DECIMAL),
                    c("name", TypeColonne.TEXTE), c("notes", TypeColonne.TEXTE), c("principal", TypeColonne.DECIMAL),
                    c("start_date", TypeColonne.DATE), c("term_months", TypeColonne.ENTIER)), null, true),
            new Table("transactions", List.of(c("id", TypeColonne.ID), c("amount", TypeColonne.DECIMAL),
                    c("balance_adjustment", TypeColonne.BOOLEEN), c("bank_label", TypeColonne.TEXTE), c("date", TypeColonne.DATE),
                    c("duplicate_ignored", TypeColonne.BOOLEEN), c("exceptional", TypeColonne.BOOLEEN), c("memo", TypeColonne.TEXTE),
                    c("payment_mode", TypeColonne.TEXTE), c("monthly_expense", TypeColonne.BOOLEEN),
                    c("periodic_suggestion_ignored", TypeColonne.BOOLEEN), c("split_from_amount", TypeColonne.DECIMAL),
                    c("validated", TypeColonne.BOOLEEN), c("account_id", TypeColonne.ID), c("category_id", TypeColonne.ID),
                    c("payee_id", TypeColonne.ID), c("transfer_transaction_id", TypeColonne.ID)), "transfer_transaction_id", true),
            new Table("transactions_supprimees", List.of(c("id", TypeColonne.ID), c("account_id", TypeColonne.ID),
                    c("amount", TypeColonne.DECIMAL), c("bank_label", TypeColonne.TEXTE), c("date", TypeColonne.DATE),
                    c("deleted_at", TypeColonne.DATE), c("payee_name", TypeColonne.TEXTE)), null, true),
            new Table(LIGNES_IMPORTEES, List.of(c("id", TypeColonne.ID), c("account_id", TypeColonne.ID),
                    c("date", TypeColonne.DATE), c("amount", TypeColonne.DECIMAL), c("label", TypeColonne.TEXTE),
                    c("transaction_id", TypeColonne.ID)), null, true),
            new Table("dernier_import", List.of(c("id", TypeColonne.ID), c("date_heure", TypeColonne.HORODATAGE)), null, false)
    );

    private final JdbcTemplate jdbc;

    public DepotSauvegarde(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lignes d'une table, par id croissant, en valeurs simples (texte ISO pour les dates). */
    public List<Map<String, Object>> lire(Table table) {
        String colonnes = String.join(", ", table.colonnes().stream().map(Colonne::nom).toList());
        return jdbc.query("select " + colonnes + " from " + table.nom() + " order by id", (rs, rowNum) -> {
            Map<String, Object> ligne = new LinkedHashMap<>();
            for (Colonne colonne : table.colonnes()) {
                ligne.put(colonne.nom(), versFichier(rs.getObject(colonne.nom()), colonne.type()));
            }
            return ligne;
        });
    }

    /**
     * Remplace toutes les données par celles fournies (table -> lignes), dans
     * la transaction de l'appelant : en cas d'erreur, rien n'est modifié.
     */
    public void remplacerTout(Map<String, List<Map<String, Object>>> donnees) {
        viderTout();
        for (Table table : TABLES) {
            List<Map<String, Object>> lignes = donnees.getOrDefault(table.nom(), List.of());
            inserer(table, lignes);
            if (table.autoReference() != null) relier(table, lignes);
        }
        if (!donnees.containsKey(LIGNES_IMPORTEES)) reconstituerLignesImportees();
        // En dernier, une fois toutes les lignes acceptées : sous H2, un ALTER
        // TABLE valide la transaction en cours, qui ne pourrait plus être annulée.
        for (Table table : TABLES) {
            if (table.identite()) recalerIdentite(table);
        }
    }

    /** Même reprise de l'existant que la migration Flyway V2 (transactions importées, puis supprimées). */
    private void reconstituerLignesImportees() {
        jdbc.update("""
                insert into lignes_importees (account_id, date, amount, label, transaction_id)
                select account_id, date, coalesce(split_from_amount, amount), bank_label, id
                from transactions where bank_label is not null and bank_label <> ''""");
        jdbc.update("""
                insert into lignes_importees (account_id, date, amount, label, transaction_id)
                select account_id, date, amount, coalesce(nullif(bank_label, ''), payee_name), null
                from transactions_supprimees
                where coalesce(nullif(bank_label, ''), payee_name) is not null
                  and coalesce(nullif(bank_label, ''), payee_name) <> ''""");
    }

    private void viderTout() {
        // Les références internes d'abord, puis les tables dans l'ordre inverse de l'insertion.
        jdbc.update("update transactions set transfer_transaction_id = null");
        jdbc.update("update categories set parent_id = null");
        for (int i = TABLES.size() - 1; i >= 0; i--) {
            jdbc.update("delete from " + TABLES.get(i).nom());
        }
    }

    private void inserer(Table table, List<Map<String, Object>> lignes) {
        if (lignes.isEmpty()) return;
        List<Colonne> colonnes = table.colonnes();
        String noms = String.join(", ", colonnes.stream().map(Colonne::nom).toList());
        String marques = String.join(", ", colonnes.stream().map(colonne -> "?").toList());
        List<Object[]> valeurs = new ArrayList<>(lignes.size());
        for (Map<String, Object> ligne : lignes) {
            Object[] parametres = new Object[colonnes.size()];
            for (int i = 0; i < colonnes.size(); i++) {
                Colonne colonne = colonnes.get(i);
                // La référence interne est posée dans un second temps (voir relier).
                parametres[i] = colonne.nom().equals(table.autoReference())
                        ? null
                        : versBase(ligne.get(colonne.nom()), colonne.type());
            }
            valeurs.add(parametres);
        }
        jdbc.batchUpdate("insert into " + table.nom() + " (" + noms + ") values (" + marques + ")", valeurs);
    }

    private void relier(Table table, List<Map<String, Object>> lignes) {
        List<Object[]> liens = lignes.stream()
                .filter(ligne -> ligne.get(table.autoReference()) != null)
                .map(ligne -> new Object[]{
                        versBase(ligne.get(table.autoReference()), TypeColonne.ID),
                        versBase(ligne.get("id"), TypeColonne.ID)})
                .toList();
        if (!liens.isEmpty()) {
            jdbc.batchUpdate("update " + table.nom() + " set " + table.autoReference() + " = ? where id = ?", liens);
        }
    }

    /** Prochain id généré juste après le plus grand id restauré (syntaxe commune à PostgreSQL et H2). */
    private void recalerIdentite(Table table) {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from " + table.nom(), Long.class);
        long suivant = (max == null ? 0 : max) + 1;
        jdbc.execute("alter table " + table.nom() + " alter column id restart with " + suivant);
    }

    private static Object versFichier(Object valeur, TypeColonne type) {
        if (valeur == null) return null;
        return switch (type) {
            case ID -> ((Number) valeur).longValue();
            case ENTIER -> ((Number) valeur).intValue();
            case DECIMAL -> valeur instanceof BigDecimal decimal ? decimal : new BigDecimal(valeur.toString());
            case DATE -> valeur instanceof Date date ? date.toLocalDate().toString() : valeur.toString();
            case HORODATAGE -> valeur instanceof Timestamp horodatage ? horodatage.toLocalDateTime().toString() : valeur.toString();
            case BOOLEEN, TEXTE -> valeur;
        };
    }

    private static Object versBase(Object valeur, TypeColonne type) {
        if (valeur == null) return null;
        return switch (type) {
            case ID -> Long.valueOf(valeur.toString());
            case ENTIER -> Integer.valueOf(valeur.toString());
            case DECIMAL -> new BigDecimal(valeur.toString());
            case BOOLEEN -> valeur instanceof Boolean booleen ? booleen : Boolean.valueOf(valeur.toString());
            case DATE -> Date.valueOf(LocalDate.parse(valeur.toString()));
            case HORODATAGE -> Timestamp.valueOf(LocalDateTime.parse(valeur.toString()));
            case TEXTE -> valeur.toString();
        };
    }
}

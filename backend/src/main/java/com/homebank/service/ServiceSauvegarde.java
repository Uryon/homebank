package com.homebank.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homebank.dto.InfoSauvegardeDTO;
import com.homebank.dto.ResultatRestaurationDTO;
import com.homebank.dto.SauvegardeDTO;
import com.homebank.repository.DepotSauvegarde;
import com.homebank.repository.DepotSauvegarde.Colonne;
import com.homebank.repository.DepotSauvegarde.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sauvegarde complète des données (comptes, catégories, tiers, transactions,
 * prêts, historique des suppressions) dans un fichier JSON, et restauration
 * d'un tel fichier. Une restauration remplace TOUTES les données : celles
 * qu'elle écrase sont d'abord écrites dans le dossier de sauvegarde, ce qui
 * permet de l'annuler (voir restaurerFichier).
 */
@Service
public class ServiceSauvegarde {

    private static final Logger log = LoggerFactory.getLogger(ServiceSauvegarde.class);

    static final String APPLICATION = "homebank";
    /** Format du fichier : à incrémenter si une évolution du schéma change son contenu. */
    static final int VERSION_FORMAT = 1;

    static final String PREFIXE_AUTO = "homebank-auto-";
    private static final String PREFIXE_AVANT_RESTAURATION = "homebank-avant-restauration-";
    /** Seuls ces noms peuvent être relus depuis le dossier (aucun chemin fourni par le client). */
    private static final Pattern NOM_FICHIER = Pattern.compile("homebank-[A-Za-z0-9_-]+\\.json");
    private static final DateTimeFormatter HORODATAGE_FICHIER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final DepotSauvegarde depot;
    private final ObjectMapper json;
    private final TransactionTemplate lecture;
    private final TransactionTemplate ecriture;
    private final Path dossier;
    private final int nombreAConserver;

    public ServiceSauvegarde(DepotSauvegarde depot, ObjectMapper json, PlatformTransactionManager transactionManager,
                             @Value("${sauvegarde.dossier}") String dossier,
                             @Value("${sauvegarde.conserver:30}") int nombreAConserver) {
        this.depot = depot;
        this.json = json;
        this.lecture = new TransactionTemplate(transactionManager);
        this.lecture.setReadOnly(true);
        this.ecriture = new TransactionTemplate(transactionManager);
        this.dossier = Path.of(dossier).toAbsolutePath().normalize();
        this.nombreAConserver = nombreAConserver;
    }

    /** Toutes les données, lues dans une même transaction (instantané cohérent). */
    public SauvegardeDTO exporter() {
        return lecture.execute(status -> {
            Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
            for (Table table : DepotSauvegarde.TABLES) {
                tables.put(table.nom(), depot.lire(table));
            }
            return new SauvegardeDTO(APPLICATION, VERSION_FORMAT, LocalDateTime.now(ZoneId.systemDefault()), tables);
        });
    }

    /**
     * Sauvegarde automatique du jour (au démarrage de l'appli, voir
     * config.SauvegardeAuDemarrage) : une seule par jour, les plus anciennes
     * au-delà de sauvegarde.conserver sont supprimées.
     */
    public Optional<Path> sauvegardeAutomatique() {
        Path fichier = dossier.resolve(PREFIXE_AUTO + LocalDate.now(ZoneId.systemDefault()) + ".json");
        if (Files.exists(fichier)) return Optional.empty();
        ecrire(fichier, exporter());
        supprimerAnciennesSauvegardesAuto();
        log.info("Sauvegarde automatique ecrite : {}", fichier);
        return Optional.of(fichier);
    }

    /**
     * Remplace toutes les données par celles du fichier fourni, après l'avoir
     * entièrement vérifié (rien n'est modifié si le fichier est invalide).
     */
    public ResultatRestaurationDTO restaurer(byte[] contenu) {
        SauvegardeDTO sauvegarde = lire(contenu);
        verifier(sauvegarde);

        String sauvegardeAvant = sauvegarderAvantRestauration();
        try {
            ecriture.executeWithoutResult(status -> depot.remplacerTout(sauvegarde.tables()));
        } catch (DataAccessException | IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("Fichier de sauvegarde invalide : ses données n'ont pas pu être restaurées. Vos données n'ont pas été modifiées.", e);
        }

        Map<String, Integer> lignes = new LinkedHashMap<>();
        for (Table table : DepotSauvegarde.TABLES) {
            lignes.put(table.nom(), sauvegarde.tables().getOrDefault(table.nom(), List.of()).size());
        }
        log.info("Restauration d'une sauvegarde du {} : {}", sauvegarde.creeLe(), lignes);
        return new ResultatRestaurationDTO(lignes, sauvegardeAvant);
    }

    /** Restaure un fichier du dossier de sauvegarde (ex : annulation d'une restauration). */
    public ResultatRestaurationDTO restaurerFichier(String nomFichier) {
        if (nomFichier == null || !NOM_FICHIER.matcher(nomFichier).matches()) {
            throw new IllegalArgumentException("Nom de fichier de sauvegarde invalide.");
        }
        Path fichier = dossier.resolve(nomFichier).normalize();
        if (!fichier.startsWith(dossier) || !Files.isRegularFile(fichier)) {
            throw new IllegalArgumentException("Sauvegarde introuvable : " + nomFichier);
        }
        try {
            return restaurer(Files.readAllBytes(fichier));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture de la sauvegarde impossible : " + nomFichier, e);
        }
    }

    /** Dossier des sauvegardes et date de la plus récente. */
    public InfoSauvegardeDTO info() {
        List<Path> fichiers = fichiersDeSauvegarde(f -> true);
        LocalDateTime derniere = fichiers.stream()
                .map(ServiceSauvegarde::dateModification)
                .max(Comparator.naturalOrder())
                .map(instant -> LocalDateTime.ofInstant(instant.toInstant(), ZoneId.systemDefault()))
                .orElse(null);
        return new InfoSauvegardeDTO(dossier.toString(), derniere, fichiers.size());
    }

    /** Écrit les données actuelles avant de les remplacer ; null si le dossier est inutilisable (la restauration reste possible). */
    private String sauvegarderAvantRestauration() {
        String nom = PREFIXE_AVANT_RESTAURATION + LocalDateTime.now(ZoneId.systemDefault()).format(HORODATAGE_FICHIER) + ".json";
        try {
            ecrire(dossier.resolve(nom), exporter());
            return nom;
        } catch (UncheckedIOException e) {
            log.warn("Sauvegarde avant restauration impossible dans {} : {}", dossier, e.getMessage());
            return null;
        }
    }

    private SauvegardeDTO lire(byte[] contenu) {
        if (contenu == null || contenu.length == 0) {
            throw new IllegalArgumentException("Le fichier de sauvegarde est vide.");
        }
        try {
            return json.readValue(contenu, SauvegardeDTO.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Ce fichier n'est pas une sauvegarde HomeBank (JSON illisible).", e);
        }
    }

    /** Vérifie l'origine, le format et la forme de chaque ligne avant toute modification. */
    static void verifier(SauvegardeDTO sauvegarde) {
        if (!APPLICATION.equals(sauvegarde.application()) || sauvegarde.tables() == null) {
            throw new IllegalArgumentException("Ce fichier n'est pas une sauvegarde HomeBank.");
        }
        if (sauvegarde.version() != VERSION_FORMAT) {
            throw new IllegalArgumentException("Format de sauvegarde non pris en charge (version " + sauvegarde.version() + ").");
        }
        Set<String> tablesConnues = DepotSauvegarde.TABLES.stream().map(Table::nom).collect(Collectors.toSet());
        for (String nom : sauvegarde.tables().keySet()) {
            if (!tablesConnues.contains(nom)) {
                throw new IllegalArgumentException("Sauvegarde invalide : table inconnue « " + nom + " ».");
            }
        }
        for (Table table : DepotSauvegarde.TABLES) {
            List<Map<String, Object>> lignes = sauvegarde.tables().get(table.nom());
            if (lignes == null) {
                throw new IllegalArgumentException("Sauvegarde incomplète : table « " + table.nom() + " » absente.");
            }
            Set<String> colonnes = table.colonnes().stream().map(Colonne::nom).collect(Collectors.toSet());
            for (Map<String, Object> ligne : lignes) {
                if (ligne == null || ligne.get("id") == null || !colonnes.containsAll(ligne.keySet())) {
                    throw new IllegalArgumentException("Sauvegarde invalide : ligne inattendue dans « " + table.nom() + " ».");
                }
            }
        }
    }

    private void ecrire(Path fichier, SauvegardeDTO sauvegarde) {
        try {
            Files.createDirectories(fichier.getParent());
            // Écriture dans un fichier temporaire puis renommage : jamais de sauvegarde à moitié écrite.
            Path temporaire = fichier.resolveSibling(fichier.getFileName() + ".tmp");
            Files.write(temporaire, json.writeValueAsBytes(sauvegarde));
            Files.move(temporaire, fichier, StandardCopyOption.REPLACE_EXISTING);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Sauvegarde impossible à produire", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture de la sauvegarde impossible : " + fichier, e);
        }
    }

    private void supprimerAnciennesSauvegardesAuto() {
        List<Path> automatiques = fichiersDeSauvegarde(f -> f.getFileName().toString().startsWith(PREFIXE_AUTO));
        // Nom daté (AAAA-MM-JJ) : l'ordre alphabétique est l'ordre chronologique.
        for (Path ancienne : automatiques.stream()
                .sorted(Comparator.comparing(Path::getFileName).reversed())
                .skip(nombreAConserver)
                .toList()) {
            try {
                Files.deleteIfExists(ancienne);
            } catch (IOException e) {
                log.warn("Ancienne sauvegarde non supprimee : {} ({})", ancienne, e.getMessage());
            }
        }
    }

    private List<Path> fichiersDeSauvegarde(Predicate<Path> filtre) {
        if (!Files.isDirectory(dossier)) return List.of();
        try (Stream<Path> fichiers = Files.list(dossier)) {
            return fichiers
                    .filter(f -> NOM_FICHIER.matcher(f.getFileName().toString()).matches())
                    .filter(filtre)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du dossier de sauvegarde impossible : " + dossier, e);
        }
    }

    private static FileTime dateModification(Path fichier) {
        try {
            return Files.getLastModifiedTime(fichier);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }
}

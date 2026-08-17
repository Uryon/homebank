package com.homebank.config;

import com.homebank.model.Payee;
import com.homebank.repository.PayeeRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Nettoie au demarrage les motifs de tiers crees avant le passage au systeme
 * de "joker" (*) simplifie, ou avec des etoiles superflues stockees par une
 * ancienne version de l'application :
 * - retire un prefixe "(?i)" (insensibilite a la casse, deja geree
 *   automatiquement de toute facon, inutile de l'ecrire soi-meme) ;
 * - retire l'encadrement "\Q...\E" (citation litterale technique generee par
 *   l'ancien systeme lors de la creation automatique d'un tiers) ;
 * - convertit les alternatives regex "a|b" en motifs joker combines "a;b" ;
 * - retire les etoiles de tete/queue superflues ("*Carrefour*" -> "Carrefour") :
 *   le champ stocke desormais du texte simple, les etoiles ne sont ajoutees
 *   qu'au moment du traitement (voir PayeeService.matchesPatternField), pas
 *   dans les donnees ni dans l'interface. Un motif avance avec une etoile
 *   AILLEURS qu'en tete/queue (ex : "Carrefour*Paris") n'est pas modifie.
 * Idempotent : sans effet une fois les motifs deja au nouveau format.
 */
@Component
@Order(20)
public class PayeePatternMigration implements ApplicationRunner {

    private final PayeeRepository payeeRepository;

    public PayeePatternMigration(PayeeRepository payeeRepository) {
        this.payeeRepository = payeeRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Payee> toFix = new ArrayList<>();

        for (Payee payee : payeeRepository.findAll()) {
            String original = payee.getPattern();
            if (original == null) continue;

            String cleaned = migrate(original);

            if (!cleaned.equals(original)) {
                payee.setPattern(cleaned);
                toFix.add(payee);
            }
        }

        if (!toFix.isEmpty()) {
            payeeRepository.saveAll(toFix);
            System.out.println(">>> Tiers : " + toFix.size()
                    + " motif(s) migre(s) vers le nouveau format simplifie (texte simple, sans etoile).");
        }
    }

    private static String migrate(String original) {
        String withoutFlag = original;
        if (withoutFlag.regionMatches(true, 0, "(?i)", 0, 4)) {
            withoutFlag = withoutFlag.substring(4);
        }

        String[] alternatives = withoutFlag.split("\\|", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < alternatives.length; i++) {
            String part = alternatives[i].trim();
            if (part.startsWith("\\Q") && part.endsWith("\\E") && part.length() >= 4) {
                part = part.substring(2, part.length() - 2);
            }
            part = stripSimpleWildcardWrap(part);
            if (i > 0) result.append(";");
            result.append(part);
        }
        return result.toString();
    }

    /**
     * Retire un encadrement "*...*" simple (une seule etoile en tete, une seule
     * en queue, aucune autre etoile a l'interieur) : "*Carrefour*" -> "Carrefour".
     * Un motif avance avec une etoile au milieu (ex : "Carrefour*Paris") ou
     * plusieurs etoiles n'est jamais modifie.
     */
    private static String stripSimpleWildcardWrap(String part) {
        if (part.length() >= 2 && part.startsWith("*") && part.endsWith("*")) {
            String inner = part.substring(1, part.length() - 1);
            if (inner.indexOf('*') < 0) {
                return inner;
            }
        }
        return part;
    }
}

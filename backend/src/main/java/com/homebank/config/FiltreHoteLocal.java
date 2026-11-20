package com.homebank.config;

import org.springframework.lang.NonNull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Protection contre le « DNS rebinding » : un site malveillant peut faire
 * pointer son propre nom de domaine vers 127.0.0.1. Le navigateur le considere
 * alors comme la meme origine, CORS ne s'applique plus, et la page pourrait
 * lire toutes les donnees de cette API sans authentification. Son en-tete Host
 * garde toutefois le nom du site : seules les adresses locales sont acceptees.
 * <p>
 * Inactif si le serveur est volontairement expose au reseau (SERVER_ADDRESS,
 * voir application.properties), ou il est joint sous d'autres noms.
 */
@Component
public class FiltreHoteLocal extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltreHoteLocal.class);
    private static final Set<String> NOMS_LOCAUX = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final boolean ecouteEnLocal;

    public FiltreHoteLocal(@Value("${server.address:}") String adresseEcoute) {
        this.ecouteEnLocal = estLocal(adresseEcoute);
    }

    private static boolean estLocal(String nom) {
        return nom != null && NOMS_LOCAUX.contains(nom.toLowerCase(Locale.ROOT));
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !ecouteEnLocal;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        if (estLocal(request.getServerName())) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Requete refusee : en-tete Host non local");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
}

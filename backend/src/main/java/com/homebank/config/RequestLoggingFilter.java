package com.homebank.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filet de securite pour le diagnostic : logge TOUTE requete qui echoue,
 * meme si l'exception est levee avant d'atteindre le DispatcherServlet
 * (filtre CORS, parsing multipart...) ou apres le retour du controller
 * (serialisation JSON de la reponse), c'est-a-dire des cas que
 * GlobalExceptionHandler (qui n'intercepte que les exceptions levees
 * DANS un controller) ne voit jamais.
 *
 * S'execute en tout premier (HIGHEST_PRECEDENCE) pour englober tout le
 * reste de la chaine de filtres.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
            if (response.getStatus() >= 500) {
                log.error("Reponse {} sur {} {} (aucune exception n'est remontee jusqu'a ce filtre : "
                                + "regarder juste au-dessus dans les logs pour la vraie cause, ou verifier "
                                + "qu'aucune AUTRE instance du backend ne tourne deja sur ce port)",
                        response.getStatus(), request.getMethod(), request.getRequestURI());
            }
        } catch (Exception e) {
            log.error("Exception non rattrapee sur {} {} : {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage(), e);
            throw e;
        }
    }
}

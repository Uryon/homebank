package com.homebank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration de l'integration Powens (agregateur bancaire PSD2 agree,
 * anciennement Budget Insight). Toutes ces valeurs sont vides par defaut :
 * la fonctionnalite reste totalement inactive tant qu'elles ne sont pas
 * renseignees (voir application.properties + README_POWENS.md).
 *
 * Documentation officielle de reference (a consulter avant mise en prod,
 * l'API pouvant evoluer) : https://docs.powens.com/
 */
@Component
@ConfigurationProperties(prefix = "powens")
public class PowensProperties {

    /** Active ou non l'integration. Reste false tant que ce n'est pas configure explicitement. */
    private boolean enabled = false;

    /** Domaine API Powens qui t'a ete attribue a l'inscription, ex : "monapp-sandbox.biapi.pro" (SANS le https://). */
    private String domain;

    /** Identifiant de l'application cliente, cree dans la console d'administration Powens. */
    private String clientId;

    /** Secret associe au client_id. Ne JAMAIS committer cette valeur : toujours via variable d'environnement. */
    private String clientSecret;

    /**
     * URL de callback backend appelee par Powens a la fin du parcours webview
     * (doit correspondre a la liste blanche configuree dans la console Powens).
     */
    private String redirectUri = "http://localhost:8080/api/bank-sync/callback";

    /** URL du frontend vers laquelle rediriger l'utilisateur une fois le callback backend traite. */
    private String frontendReturnUrl = "http://localhost:5173/bank-sync";

    /** Si true, une synchronisation est tentee automatiquement au demarrage de l'application (si une banque est deja liee). */
    private boolean autoSyncOnStartup = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

    public String getFrontendReturnUrl() { return frontendReturnUrl; }
    public void setFrontendReturnUrl(String frontendReturnUrl) { this.frontendReturnUrl = frontendReturnUrl; }

    public boolean isAutoSyncOnStartup() { return autoSyncOnStartup; }
    public void setAutoSyncOnStartup(boolean autoSyncOnStartup) { this.autoSyncOnStartup = autoSyncOnStartup; }

    /** Base URL de l'API REST Powens pour ce domaine. */
    public String apiBaseUrl() {
        return "https://" + domain + "/2.0";
    }
}

package com.homebank.controller;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Erreurs client (requete mal formee) : 400/405 explicites, jamais un 500.
 * Le message d'une violation d'integrite depend de l'operation : le conseil
 * "reaffectez avant de supprimer" ne doit apparaitre que sur une suppression.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final DataIntegrityViolationException violation = new DataIntegrityViolationException("contrainte");

    private static ServletWebRequest request(String method) {
        return new ServletWebRequest(new MockHttpServletRequest(method, "/api/categories/1"));
    }

    @Test
    void violationDIntegrite_surUneSuppression_conseilleDeReaffecter() {
        ResponseEntity<Map<String, String>> response = handler.handleDataIntegrityViolation(violation, request("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).startsWith("Impossible de supprimer");
    }

    @Test
    void violationDIntegrite_surUnEnregistrement_neParlePasDeSuppression() {
        ResponseEntity<Map<String, String>> response = handler.handleDataIntegrityViolation(violation, request("POST"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error"))
                .startsWith("Enregistrement refusé")
                .doesNotContain("supprimer");
    }

    @Test
    void corpsIllisible_renvoie400SansDetailTechnique() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException(
                "JSON parse error: Cannot deserialize value of type `java.time.LocalDate`",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<Map<String, String>> response = handler.handleUnreadableBody(e, request("POST"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error"))
                .startsWith("Requête invalide")
                .doesNotContain("LocalDate");
    }

    @Test
    void parametreDuMauvaisType_renvoie400EnNommantLeParametre() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", null, new NumberFormatException("abc"));

        ResponseEntity<Map<String, String>> response = handler.handleTypeMismatch(e, request("GET"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).contains("'id'").contains("abc");
    }

    @Test
    void parametreManquant_renvoie400() {
        MissingServletRequestParameterException e = new MissingServletRequestParameterException("from", "LocalDate");

        ResponseEntity<Map<String, String>> response = handler.handleMissingParameter(e, request("GET"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).contains("'from'");
    }

    @Test
    void methodeNonSupportee_renvoie405() {
        HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("PATCH");

        ResponseEntity<Map<String, String>> response = handler.handleMethodNotSupported(e, request("PATCH"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().get("error")).contains("PATCH");
    }

    @Test
    void importSansFichier_renvoie400() {
        ResponseEntity<Map<String, String>> response =
                handler.handleMissingPart(new MissingServletRequestPartException("file"), request("POST"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).startsWith("Fichier manquant");
    }

    @Test
    void fichierTropVolumineux_renvoie413AvecUnConseil() {
        ResponseEntity<Map<String, String>> response =
                handler.handleUploadTooLarge(new MaxUploadSizeExceededException(8_000_000L), request("POST"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("error")).startsWith("Fichier trop volumineux");
    }

    @Test
    void adresseInconnue_renvoie404EtNon500() {
        ResponseEntity<Map<String, String>> response = handler.handleUnknownPath(
                new NoResourceFoundException(HttpMethod.GET, "api/nexistepas"), request("GET"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error")).isEqualTo("Adresse inconnue : /api/nexistepas");
    }

    /** Map.of refuse null : sans message de repli, le handler levait lui-meme une NullPointerException. */
    @Test
    void erreurInterneSansMessage_renvoie500AvecLeTypeDErreur() {
        ResponseEntity<Map<String, String>> response =
                handler.handleGeneric(new NullPointerException(), request("GET"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().get("error")).isEqualTo("Erreur interne : NullPointerException");
    }

    @Test
    void exceptionsMetierSansMessage_renvoientUnMessageDeRepli() {
        assertThat(handler.handleNotFound(new NoSuchElementException(), request("GET")).getBody().get("error"))
                .isEqualTo("Élément introuvable.");
        assertThat(handler.handleBadRequest(new IllegalArgumentException(), request("POST")).getBody().get("error"))
                .isEqualTo("Requête invalide.");
        assertThat(handler.handleConflict(new IllegalStateException(), request("DELETE")).getBody().get("error"))
                .isEqualTo("Opération impossible dans l'état actuel des données.");
    }
}

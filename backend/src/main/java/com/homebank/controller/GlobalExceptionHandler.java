package com.homebank.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_KEY = "error";

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e, WebRequest request) {
        log.warn("404 sur {} : {}", requestPath(request), e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(ERROR_KEY, messageOr(e, "Élément introuvable.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e, WebRequest request) {
        log.warn("400 sur {} : {}", requestPath(request), e.getMessage());
        return badRequest(messageOr(e, "Requête invalide."));
    }

    /** Operation refusee par une regle metier (ex : suppression d'un compte qui a des transactions). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e, WebRequest request) {
        log.warn("409 sur {} : {}", requestPath(request), e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(ERROR_KEY, messageOr(e, "Opération impossible dans l'état actuel des données.")));
    }

    /**
     * Violation de contrainte en base (ex : suppression d'une categorie ou d'un
     * tiers encore reference par des transactions/tiers, la FK n'a pas de
     * ON DELETE CASCADE). Renvoyee comme un 409 explicite plutot que de
     * tomber dans le handler generique (500 "Erreur interne" peu parlant) :
     * l'utilisateur doit comprendre qu'il faut d'abord reaffecter ce qui
     * reference encore l'element (ex : le bouton "Fusionner"), pas relancer
     * la meme suppression.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException e, WebRequest request) {
        log.warn("409 sur {} : {}", requestPath(request), e.getMessage());
        // Le conseil "réaffectez avant de supprimer" n'a de sens que pour une
        // suppression ; sur une création/modification, la violation vient
        // d'une donnée en conflit ou incomplète.
        boolean isDelete = request instanceof ServletWebRequest servletRequest
                && "DELETE".equalsIgnoreCase(servletRequest.getRequest().getMethod());
        String message = isDelete
                ? "Impossible de supprimer : cet élément est encore utilisé ailleurs (transactions, tiers ou sous-catégories). "
                        + "Réaffectez-le d'abord (par exemple via \"Fusionner\") avant de le supprimer."
                : "Enregistrement refusé : une donnée obligatoire est manquante ou entre en conflit avec un élément existant.";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ERROR_KEY, message));
    }

    /**
     * Corps de requete illisible : JSON mal forme, ou valeur impossible a
     * convertir (date invalide, montant non numerique...). C'est une erreur
     * du client, pas du serveur : 400 avec un message comprehensible plutot
     * qu'un 500 "Erreur interne" et une stacktrace en ERROR dans les logs.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e, WebRequest request) {
        log.warn("400 sur {} : corps illisible ({})", requestPath(request), e.getMostSpecificCause().getMessage());
        return badRequest("Requête invalide : les données envoyées sont mal formées (date, montant ou format JSON incorrect).");
    }

    /** Parametre d'URL ou de requete du mauvais type (ex : /api/transactions/abc, from=2024-13-45). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e, WebRequest request) {
        log.warn("400 sur {} : parametre '{}' invalide ({})", requestPath(request), e.getName(), e.getValue());
        return badRequest("Paramètre invalide : '" + e.getName() + "' a une valeur incorrecte (" + e.getValue() + ").");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParameter(MissingServletRequestParameterException e, WebRequest request) {
        log.warn("400 sur {} : parametre '{}' manquant", requestPath(request), e.getParameterName());
        return badRequest("Paramètre manquant : '" + e.getParameterName() + "'.");
    }

    /** Requete multipart sans le fichier attendu (ex : import lance sans fichier selectionne). */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> handleMissingPart(MissingServletRequestPartException e, WebRequest request) {
        log.warn("400 sur {} : partie '{}' manquante", requestPath(request), e.getRequestPartName());
        return badRequest("Fichier manquant : aucun fichier n'a été envoyé ('" + e.getRequestPartName() + "').");
    }

    /** Fichier d'import au-dela de spring.servlet.multipart.max-file-size. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge(MaxUploadSizeExceededException e, WebRequest request) {
        log.warn("413 sur {} : fichier trop volumineux ({})", requestPath(request), e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(ERROR_KEY, "Fichier trop volumineux : un relevé bancaire tient normalement en moins de 1 Mo. "
                        + "Vérifiez qu'il s'agit bien du bon fichier, ou découpez-le en plusieurs imports."));
    }

    /** Adresse d'API inexistante (faute de frappe, frontend et backend de versions differentes...). */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleUnknownPath(NoResourceFoundException e, WebRequest request) {
        log.warn("404 sur {} : adresse inconnue", requestPath(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(ERROR_KEY, "Adresse inconnue : /" + e.getResourcePath()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, WebRequest request) {
        log.warn("405 sur {} : methode {} non supportee", requestPath(request), e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of(ERROR_KEY, "Opération non supportée : méthode " + e.getMethod() + " non autorisée sur cette adresse."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e, WebRequest request) {
        // Stacktrace complete cote serveur : c'est ce qu'il faut regarder
        // dans la console/les logs du backend pour diagnostiquer une 500.
        log.error("500 sur {} : {}", requestPath(request), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(ERROR_KEY, "Erreur interne : " + messageOr(e, e.getClass().getSimpleName())));
    }

    /**
     * Message de l'exception, ou le texte de repli s'il est absent : Map.of
     * refuse les valeurs null, une exception sans message (ex :
     * NullPointerException) faisait echouer le handler lui-meme, et le client
     * recevait une 500 sans corps exploitable.
     */
    private static String messageOr(Exception e, String fallback) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : fallback;
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(ERROR_KEY, message));
    }

    private String requestPath(WebRequest request) {
        return request.getDescription(false).replace("uri=", "");
    }
}

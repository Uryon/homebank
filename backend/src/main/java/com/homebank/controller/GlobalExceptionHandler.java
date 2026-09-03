package com.homebank.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_KEY = "error";

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e, WebRequest request) {
        log.warn("404 sur {} : {}", requestPath(request), e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(ERROR_KEY, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e, WebRequest request) {
        log.warn("400 sur {} : {}", requestPath(request), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(ERROR_KEY, e.getMessage()));
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
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ERROR_KEY,
                "Impossible de supprimer : cet élément est encore utilisé ailleurs (transactions, tiers ou sous-catégories). " +
                        "Réaffectez-le d'abord (par exemple via \"Fusionner\") avant de le supprimer."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e, WebRequest request) {
        // Stacktrace complete cote serveur : c'est ce qu'il faut regarder
        // dans la console/les logs du backend pour diagnostiquer une 500.
        log.error("500 sur {} : {}", requestPath(request), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(ERROR_KEY, "Erreur interne : " + e.getMessage()));
    }

    private String requestPath(WebRequest request) {
        return request.getDescription(false).replace("uri=", "");
    }
}

package com.yourname.aiprep.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Map;

/**
 * Centralizes exception handling so controllers stay clean and
 * stack traces never leak to the client.
 *
 * Place at:
 * src/main/java/com/yourname/aiprep/exception/GlobalExceptionHandler.java
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Groq parse / truncation failures */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.error("AI processing error", ex);
        return error(HttpStatus.BAD_GATEWAY, "AI response could not be processed. Please try again.");
    }

    /** Groq HTTP errors that weren't quota-related */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, Object>> handleRestClient(RestClientResponseException ex) {
        log.error("Upstream API error: status={}", ex.getRawStatusCode(), ex);
        return error(HttpStatus.BAD_GATEWAY, "Upstream AI service error. Please try again later.");
    }

    /** Catch-all — never expose internal detail */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", message,
                "status", status.value(),
                "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(IllegalArgumentException ex) {
        // No stack trace logging needed — this is a client error
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

}
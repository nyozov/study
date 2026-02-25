package com.yourname.aiprep.exception;

import java.time.Instant;
import java.util.UUID;

import com.yourname.aiprep.model.ApiError;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
    ) {
        return buildError(
            HttpStatus.BAD_REQUEST,
            "Validation failed",
            request
        );
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> handleUpstream(
        RestClientResponseException ex,
        HttpServletRequest request
    ) {
        return buildError(
            HttpStatus.BAD_GATEWAY,
            "Upstream provider error",
            request
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleState(
        IllegalStateException ex,
        HttpServletRequest request
    ) {
        return buildError(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal processing error",
            request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(
        Exception ex,
        HttpServletRequest request
    ) {
        return buildError(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error",
            request
        );
    }

    private ResponseEntity<ApiError> buildError(
        HttpStatus status,
        String message,
        HttpServletRequest request
    ) {
        ApiError body = new ApiError(
            Instant.now(),
            status.value(),
            status.getReasonPhrase(),
            message,
            request.getRequestURI(),
            UUID.randomUUID().toString()
        );
        return ResponseEntity.status(status).body(body);
    }
}

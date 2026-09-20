package com.suretyseven.common;

import com.suretyseven.external.ExternalApiException;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Central place that turns exceptions into a consistent JSON error shape,
 * with a status code that actually reflects the failure -- so callers
 * (including the frontend) can branch on `error`/`status` rather than
 * string-matching a message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request failed validation", details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), null);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EntityNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConflict(ObjectOptimisticLockingFailureException ex) {
        return build(HttpStatus.CONFLICT, "CONFLICT",
                "This application was updated concurrently. Please retry your request.", null);
    }

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ApiError> handleExternalApi(ExternalApiException ex) {
        // Never surface the raw upstream error/stack to the client; log it, return a stable message.
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "A required external service is temporarily unavailable. The application will be retried automatically.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String message, List<String> details) {
        ApiError body = new ApiError(Instant.now(), status.value(), error, message, details,
                MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(status).body(body);
    }
}

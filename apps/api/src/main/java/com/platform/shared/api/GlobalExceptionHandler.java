package com.platform.shared.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** RFC 9457 problem+json responses with a stable machine-readable {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException ex) {
        return build(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_FAILED, "Validation failed", Map.of("fields", fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> constraint(ConstraintViolationException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return build(ErrorCode.VALIDATION_FAILED, "Validation failed", Map.of("fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
        return build(ErrorCode.BAD_REQUEST, "Malformed request body", Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, "Access denied", Map.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimistic(OptimisticLockingFailureException ex) {
        return build(ErrorCode.CONFLICT, "The resource was modified concurrently; reload and retry", Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException ex) {
        log.warn("Integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONFLICT, "Conflicts with existing data (duplicate or invalid reference)", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE, "File too large", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, "Not found", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return build(ErrorCode.INTERNAL, "Unexpected error", Map.of());
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String message, Map<String, Object> details) {
        HttpStatus status = code.status();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("https://docs.vendocas.com/errors/" + code.name().toLowerCase().replace('_', '-')));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code.name());
        details.forEach(pd::setProperty);
        return ResponseEntity.status(status).body(pd);
    }
}

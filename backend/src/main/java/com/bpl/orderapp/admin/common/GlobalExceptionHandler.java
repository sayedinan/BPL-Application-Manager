package com.bpl.orderapp.admin.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps exceptions to RFC 7807 {@link ProblemDetail} responses per
 * SPEC §4.1. Every error response carries:
 * <ul>
 *   <li>{@code type} — a URI in our error namespace</li>
 *   <li>{@code title} — short human-readable name</li>
 *   <li>{@code status} — HTTP status code (matches the response status)</li>
 *   <li>{@code detail} — message safe to show the user (no secrets, no stack traces)</li>
 *   <li>{@code instance} — the request path that produced the error</li>
 *   <li>{@code code} — machine-readable code from SPEC §4.2's locked list</li>
 * </ul>
 *
 * <p>Error code constants live in {@code ErrorCode}. Adding a new
 * code requires: (1) adding it to the {@code ErrorCode} constants,
 * (2) adding the corresponding case here if it's a server-raised
 * error, and (3) adding it to the spec's error code table.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String ERROR_TYPE_BASE = "https://errors.bpl-orderapp.com/";

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex) {
        // The detail message is generic by design — never reveal
        // which side of the credential check (username vs password)
        // failed.
        ProblemDetail pd = baseProblem(
            ErrorCode.INVALID_CREDENTIALS,
            HttpStatus.UNAUTHORIZED,
            "Invalid credentials"
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex) {
        // 404 with a generic message. Used for "user not found"
        // and "user soft-deleted" — the same response shape keeps
        // the soft-delete-vs-never-existed distinction out of the
        // response, which would let an attacker probe which user
        // ids are valid.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, "Resource not found");
        pd.setType(URI.create(ERROR_TYPE_BASE + "NOT_FOUND"));
        pd.setTitle("Not Found");
        pd.setProperty("code", "NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        // Map Bean Validation failures to a 400 with the field-level
        // errors in the `details` extension. Field names + messages
        // are safe to surface; never log the request body here.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
            fieldErrors.put(fe.getField(),
                fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage())
        );
        ProblemDetail pd = baseProblem(
            ErrorCode.VALIDATION_FAILED,
            HttpStatus.BAD_REQUEST,
            "Request body validation failed"
        );
        pd.setProperty("details", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // Malformed JSON, missing body, type-mismatched field. All
        // surface as 400 VALIDATION_FAILED — the message is
        // deliberately generic so we don't echo Jackson internals
        // or hints about the request shape.
        ProblemDetail pd = baseProblem(
            ErrorCode.VALIDATION_FAILED,
            HttpStatus.BAD_REQUEST,
            "Request body could not be parsed"
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        // Controllers raise this for semantic 4xx errors that don't
        // fit a more specific exception type. The status comes from
        // the exception; the reason text becomes the detail. We
        // map 400 to VALIDATION_FAILED (the common case) and
        // everything else to its respective status code without a
        // specific code (callers can read the HTTP status).
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        if (status == HttpStatus.BAD_REQUEST) {
            ProblemDetail pd = baseProblem(
                ErrorCode.VALIDATION_FAILED,
                status,
                ex.getReason() == null ? "Request rejected" : ex.getReason()
            );
            return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
        }
        // For non-400 statuses, fall through to the catch-all below
        // by rethrowing. The simplest path is to just handle the
        // 400 case here and let everything else get the default
        // Spring behavior; if a future endpoint raises 4xx other
        // than 400, add it here.
        log.warn("Unhandled ResponseStatusException with non-400 status: {}", ex.getStatusCode());
        return handleAny(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAny(Exception ex) {
        // Last-resort handler. Logs the full stack trace server-side
        // (this is fine — log aggregation is the operator's
        // responsibility) but the response body carries no detail
        // that could leak internals.
        log.error("Unhandled exception", ex);
        ProblemDetail pd = baseProblem(
            "INTERNAL_ERROR",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An internal error occurred"
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(pd);
    }

    private ProblemDetail baseProblem(String code, HttpStatus status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(ERROR_TYPE_BASE + code));
        pd.setTitle(humanize(code));
        pd.setProperty("code", code);
        return pd;
    }

    private static String humanize(String code) {
        // "INVALID_CREDENTIALS" -> "Invalid Credentials"
        StringBuilder sb = new StringBuilder(code.length());
        boolean upper = true;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '_') {
                sb.append(' ');
                upper = true;
            } else if (upper) {
                sb.append(c);
                upper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}

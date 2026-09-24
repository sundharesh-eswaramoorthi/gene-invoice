package com.geneinvoice.common;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.geneinvoice.approval.ApprovalDtos;
import com.geneinvoice.approval.ApprovalService;
import com.geneinvoice.approval.PendingApprovalException;
import com.geneinvoice.approval.StaleChangeException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final ApprovalService approvals;

    /** A transaction of its own for the park, because by the time a handler runs the mutator's
     *  transaction has already rolled back and ApprovalService.park is MANDATORY. The
     *  BulkExecutor:22 idiom rather than @RequiredArgsConstructor, which cannot build one (B2). */
    private final TransactionTemplate parkTx;

    public GlobalExceptionHandler(ApprovalService approvals, PlatformTransactionManager txManager) {
        this.approvals = approvals;
        this.parkTx = new TransactionTemplate(txManager);
        this.parkTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A save held for approval is not an error: it was accepted, it just has not happened (B2).
     *
     * <p>202 and never 200 — a client that reads 200 as "done" will tell somebody their payment
     * was recorded — never 409, because nothing conflicted, and never 403, because the maker was
     * entitled to ask. The error contract fixes this and it is not negotiable.
     *
     * <p>Spring's ExceptionHandlerMethodResolver picks the most specific handler within one
     * advice, so the @ExceptionHandler(Exception.class) below is not a hazard and this does not
     * need an advice of its own (B2).
     */
    @ExceptionHandler(PendingApprovalException.class)
    public ResponseEntity<Object> pendingApproval(PendingApprovalException ex, HttpServletRequest req) {
        String path = req.getRequestURI();
        try {
            return ResponseEntity.accepted().body(parkTx.execute(s -> approvals.park(ex.change(), path)));
        } catch (DataIntegrityViolationException e) {
            // uq_pending_open is the backstop for two makers who both passed the gate's
            // in-transaction exists(): the target's row lock is released by the rollback before
            // either pending row is written, so the database is the only thing that can decide
            // which of them is the one that is waiting (B2).
            ApprovalDtos.Accepted waiting =
                    parkTx.execute(s -> approvals.describeExisting(ex.change(), path));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    ApiError.of(409, "Conflict", waiting.message(), path));
        }
    }

    /** The record moved under the change that was approved: look again, do not replay (B2). */
    @ExceptionHandler(StaleChangeException.class)
    public ResponseEntity<ApiError> staleChange(StaleChangeException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "Conflict", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "Not Found", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException ex, HttpServletRequest req) {
        return badRequest(ex.getMessage(), req);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> badCreds(BadCredentialsException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(401, "Unauthorized", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> authEx(AuthenticationException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(401, "Unauthorized", ex.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiError.of(403, "Forbidden", "You do not have permission for this action", req.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.validation(req.getRequestURI(), errors));
    }

    @ExceptionHandler(InvalidFieldsException.class)
    public ResponseEntity<ApiError> invalidFields(InvalidFieldsException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.validation(req.getRequestURI(), ex.getFieldErrors()));
    }

    public static class InvalidFieldsException extends RuntimeException {
        private final Map<String, String> fieldErrors;

        public InvalidFieldsException(Map<String, String> fieldErrors) {
            super("One or more fields are invalid: " + fieldErrors.keySet());
            this.fieldErrors = Map.copyOf(fieldErrors);
        }

        public Map<String, String> getFieldErrors() {
            return fieldErrors;
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        if (ex.getCause() instanceof MismatchedInputException mie && !mie.getPath().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.validation(
                    req.getRequestURI(), Map.of(fieldPath(mie), expectation(mie.getTargetType()))));
        }
        String message = ex.getMessage() != null && ex.getMessage().startsWith("Required request body is missing")
                ? "A request body is required"
                : "The request body is not valid JSON";
        return badRequest(message, req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return badRequest("Invalid value for '" + ex.getName() + "': " + expectation(ex.getRequiredType()), req);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException ex,
                                                     HttpServletRequest req) {
        return badRequest("Missing required parameter '" + ex.getParameterName() + "'", req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ApiError.of(415,
                "Unsupported Media Type", "Send the request body as application/json", req.getRequestURI()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiError.of(405,
                "Method Not Allowed", ex.getMethod() + " is not supported here", req.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noSuchPath(NoResourceFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "Not Found", "No such endpoint", req.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "Conflict", "This change conflicts with existing data", req.getRequestURI()));
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> concurrency(ConcurrencyFailureException ex, HttpServletRequest req) {
        log.warn("Concurrent change on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict",
                "This record changed while you were working on it; reload and try again",
                req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(500, "Internal Server Error", "Unexpected error", req.getRequestURI()));
    }

    private static ResponseEntity<ApiError> badRequest(String message, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiError.of(400, "Bad Request", message, req.getRequestURI()));
    }

    private static String fieldPath(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null) {
                if (!path.isEmpty()) path.append('.');
                path.append(ref.getFieldName());
            } else {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String expectation(Class<?> type) {
        if (type == null) return "has the wrong type";
        if (type.isEnum()) return "must be one of " + Arrays.toString(type.getEnumConstants());
        if (type == Boolean.class || type == boolean.class) return "must be true or false";
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return "must be a number";
        if (Temporal.class.isAssignableFrom(type)) return "must be a date";
        return "has the wrong type";
    }
}

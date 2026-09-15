package com.geneinvoice.common;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
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

/**
 * Turns exceptions into the {@link ApiError} shape. A mistake in the request is a 4xx with a short
 * message the caller can act on; only a genuine fault is a 500, and that never echoes internal
 * detail — class names, SQL and parser text stay in the server log (AC-D9).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

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

    /** Unreadable JSON, a missing body, or a value of the wrong type inside the body. */
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

    /** A path variable or query parameter that doesn't convert, e.g. /api/invoices/abc. */
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

    /** A unique or foreign-key constraint refused the write. The SQL behind it stays server-side. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "Conflict", "This change conflicts with existing data", req.getRequestURI()));
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

    /** Jackson's path into the body as the caller wrote it, e.g. {@code items[0].quantity}. */
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

    /** What a value of this type should look like, in words rather than a Java class name. */
    private static String expectation(Class<?> type) {
        if (type == null) return "has the wrong type";
        if (type.isEnum()) return "must be one of " + Arrays.toString(type.getEnumConstants());
        if (type == Boolean.class || type == boolean.class) return "must be true or false";
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return "must be a number";
        if (Temporal.class.isAssignableFrom(type)) return "must be a date";
        return "has the wrong type";
    }
}

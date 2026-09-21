package com.geneinvoice.mail.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class ApiErrors {

    public record Body(int status, String error, String message,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> fieldErrors) {

        public static Body of(HttpStatus status, String message) {
            return new Body(status.value(), status.getReasonPhrase(), message, null);
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Body> api(ApiException ex) {
        return respond(ex.status(), ex.getMessage());
    }

    @ExceptionHandler(InvalidFieldsException.class)
    public ResponseEntity<Body> invalid(InvalidFieldsException ex) {
        return ResponseEntity.badRequest().body(new Body(400, HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(), ex.fieldErrors()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Body> unreadable(HttpMessageNotReadableException ex) {
        String message = ex.getMessage() != null && ex.getMessage().startsWith("Required request body is missing")
                ? "A request body is required"
                : "The request body is not valid JSON";
        return respond(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Body> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Body> mediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the request body as application/json");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Body> method(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ex.getMethod() + " is not supported here");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Body> noSuchPath(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "No such endpoint");
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<Body> conflict(RuntimeException ex, HttpServletRequest req) {
        log.info("Conflicting change on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.CONFLICT, "Another request changed the same data at the same time; try again");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Body> generic(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private static ResponseEntity<Body> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Body.of(status, message));
    }
}

package com.geneinvoice.document;

import com.geneinvoice.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DocumentUploadAdvice {

    private final DocumentProperties properties;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.validation(
                req.getRequestURI(), Map.of("file", DocumentRules.tooLarge(properties.getMaxSizeBytes()))));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> unparseable(MultipartException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.validation(
                req.getRequestURI(), Map.of("file", DocumentRules.BAD_FILENAME)));
    }
}

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

/**
 * The container's own size limit, answered in the app's words.
 *
 * <p>{@link DocumentRules} refuses an over-large upload with a field error, and the multipart
 * limits in {@code application.yml} are set so it gets the chance to. A part larger than the
 * container's limit is refused before any controller is chosen, though, so that refusal reaches no
 * controller's own handler — only an advice. This one says exactly what the app's check says, so a
 * user sees one message whichever limit fired.
 *
 * <p>It is ordered first because {@code GlobalExceptionHandler} handles {@code Exception}, and an
 * advice is chosen whole: the first one with a matching method wins.
 */
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

    /**
     * A request the container would not parse, answered as a refusal rather than a fault.
     *
     * <p>Tomcat rejects some filenames while reading the request — a null byte in the middle of a
     * name, or just before its extension — and throws before a controller is chosen, so
     * {@link DocumentRules#cleanFilename} never gets to drop the byte as it would have. Without
     * this, {@code GlobalExceptionHandler}'s catch-all answered 500, and the answer depended on
     * where in the name the byte sat: leading, it parsed and the upload succeeded (D-02).
     *
     * <p>{@link MaxUploadSizeExceededException} is itself a {@code MultipartException}; it keeps
     * its own handler above, because Spring picks the closest match and that one says which limit
     * was passed.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> unparseable(MultipartException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.validation(
                req.getRequestURI(), Map.of("file", DocumentRules.BAD_FILENAME)));
    }
}

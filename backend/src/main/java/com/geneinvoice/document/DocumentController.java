package com.geneinvoice.document;

import com.geneinvoice.common.ApiError;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * The document endpoints (§4.2). Each carries the document privilege; the parent record's own
 * privilege and scope are checked in the service, so a document is exactly as reachable as the
 * record it hangs off.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Privileges.DOCUMENT_MANAGE + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDtos.DocumentDto upload(
            // Not required, so a request without a file is the app's "Choose a file", not Spring's.
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam String entityType,
            @RequestParam Long entityId,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String visibility) {
        return service.upload(entityType, entityId, file, description, visibility);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.DOCUMENT_VIEW + "')")
    public PageResponse<DocumentDtos.DocumentDto> list(@RequestParam String entityType,
                                                       @RequestParam Long entityId,
                                                       @RequestParam(required = false) Integer page,
                                                       @RequestParam(required = false) Integer size) {
        return service.list(entityType, entityId, page, size);
    }

    @GetMapping("/count")
    @PreAuthorize("hasAuthority('" + Privileges.DOCUMENT_VIEW + "')")
    public DocumentDtos.DocumentCount count(@RequestParam String entityType,
                                            @RequestParam Long entityId) {
        return service.count(entityType, entityId);
    }

    /**
     * Streams the bytes, with the headers that keep a browser from making anything of them: it
     * downloads the file, does not sniff a type of its own, and may not run a script from it in
     * the app's origin (AC-C13).
     */
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('" + Privileges.DOCUMENT_VIEW + "')")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        DocumentService.Download file = service.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(file.filename()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .body(new InputStreamResource(file.stream()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.DOCUMENT_MANAGE + "')")
    public DocumentDtos.DocumentDto update(@PathVariable Long id,
                                           @RequestBody DocumentDtos.PatchDocumentRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.DOCUMENT_MANAGE + "')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** No storage configured (503), or a read or write that failed (500). */
    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<ApiError> storageFailed(DocumentStorageException e, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
        if (status == null) status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(
                ApiError.of(status.value(), status.getReasonPhrase(), e.getMessage(), req.getRequestURI()));
    }

    /**
     * RFC 6266: an ASCII fallback every client understands, and the name as it really is for those
     * that do. The filename has already had control characters and path separators taken out of it
     * (AC-C8); what is left is escaped here so it cannot break out of the header.
     */
    private static String attachment(String filename) {
        StringBuilder ascii = new StringBuilder(filename.length());
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            ascii.append(c < 0x20 || c > 0x7E || c == '"' || c == '\\' ? '_' : c);
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encode(filename);
    }

    /** Percent-encoded UTF-8, keeping only RFC 5987's attr-chars as they are. */
    private static String encode(String filename) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c < 0x80 && Character.isLetterOrDigit(c)) || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format("%02X", c));
            }
        }
        return encoded.toString();
    }
}

package com.geneinvoice.document;

import java.nio.file.Path;

/**
 * The one place a virus scanner is called (AC-C14). It is handed the staged file — the whole
 * upload, on disk, before a byte of it has reached storage and before any row is written — so a
 * scanner that refuses one leaves nothing behind to clean up.
 *
 * <p>No scanner ships in this round: {@link NoopDocumentScanner} accepts everything. Adding one is
 * another implementation of this interface, and no other file changes.
 */
public interface DocumentScanner {

    /**
     * Looks at the staged file. Refusing it is a {@link com.geneinvoice.common.BadRequestException}
     * or a {@link com.geneinvoice.common.GlobalExceptionHandler.InvalidFieldsException} on
     * {@code file}, which is what the uploader is shown.
     */
    void scan(Path file, String filename, String contentType);
}

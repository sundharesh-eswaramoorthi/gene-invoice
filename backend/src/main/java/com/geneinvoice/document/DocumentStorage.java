package com.geneinvoice.document;

import java.io.InputStream;

/**
 * Where the bytes go (D6). Exactly one implementation is active, chosen by
 * {@code app.documents.storage}: the local filesystem, or nothing. An object store is added by
 * writing another implementation of this interface — controllers, services and the app never learn
 * which one they are talking to.
 */
public interface DocumentStorage {

    /** False when nothing is set up to hold bytes; uploads are then 503 and nothing else changes. */
    boolean isConfigured();

    /**
     * Writes the stream under {@code key}, which the caller generated, and answers with what
     * landed. Nothing is visible under the key until every byte is written, so a failure leaves no
     * half-made file (AC-C18). Throws {@link DocumentStorageException} when the bytes could not be
     * stored.
     */
    StoredFile put(InputStream in, String key, String contentType, long maxBytes);

    /** The stored bytes, for streaming to a caller who has been allowed to have them. */
    InputStream open(String key);

    /** Removes the bytes; a key that is not there is not an error. */
    void delete(String key);
}

package com.geneinvoice.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@ConditionalOnProperty(name = "app.documents.storage", havingValue = DocumentProperties.NONE)
public class NoDocumentStorage implements DocumentStorage {

    public static final String NOT_CONFIGURED = "Document storage is not configured";

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public StoredFile put(InputStream in, String key, String contentType, long maxBytes) {
        throw new DocumentStorageException(503, NOT_CONFIGURED);
    }

    @Override
    public InputStream open(String key) {
        throw new DocumentStorageException(503, NOT_CONFIGURED);
    }

    @Override
    public void delete(String key) {
    }
}

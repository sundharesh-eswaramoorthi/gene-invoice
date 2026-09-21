package com.geneinvoice.document;

import java.io.InputStream;

public interface DocumentStorage {

    boolean isConfigured();

    StoredFile put(InputStream in, String key, String contentType, long maxBytes);

    InputStream open(String key);

    void delete(String key);
}

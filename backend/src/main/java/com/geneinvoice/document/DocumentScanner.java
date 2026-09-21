package com.geneinvoice.document;

import java.nio.file.Path;

public interface DocumentScanner {

    void scan(Path file, String filename, String contentType);
}

package com.geneinvoice.document;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class NoopDocumentScanner implements DocumentScanner {

    @Override
    public void scan(Path file, String filename, String contentType) {
    }
}

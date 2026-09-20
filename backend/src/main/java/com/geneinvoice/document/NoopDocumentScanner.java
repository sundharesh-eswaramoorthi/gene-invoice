package com.geneinvoice.document;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** The scanner this round ships with: it looks at nothing and refuses nothing (AC-C14). */
@Component
public class NoopDocumentScanner implements DocumentScanner {

    @Override
    public void scan(Path file, String filename, String contentType) {
        // A real scanner goes here, or replaces this bean.
    }
}

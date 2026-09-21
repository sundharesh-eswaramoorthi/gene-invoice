package com.geneinvoice.document;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "app.documents")
@Getter
@Setter
public class DocumentProperties {

    public static final String LOCAL = "local";
    public static final String NONE = "none";

    private String storage = LOCAL;

    private long maxSizeBytes = 10L * 1024 * 1024;

    private List<String> allowedTypes = List.of(ContentSniffer.PDF, ContentSniffer.PNG,
            ContentSniffer.JPEG, ContentSniffer.DOCX, ContentSniffer.XLSX);

    private final Local local = new Local();

    @Getter
    @Setter
    public static class Local {
        private String root = "./data/documents";
    }

    @PostConstruct
    void check() {
        String kind = storage == null ? "" : storage.trim().toLowerCase(Locale.ROOT);
        if (!LOCAL.equals(kind) && !NONE.equals(kind)) {
            throw new IllegalStateException(
                    "Set DOCUMENT_STORAGE to " + LOCAL + " or " + NONE + ", not " + storage);
        }
        if (maxSizeBytes < 1) {
            throw new IllegalStateException("DOCUMENT_MAX_BYTES must be at least 1");
        }
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            throw new IllegalStateException(
                    "app.documents.allowed-types must list at least one content type");
        }
    }
}

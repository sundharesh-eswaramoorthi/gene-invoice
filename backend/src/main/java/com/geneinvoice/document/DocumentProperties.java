package com.geneinvoice.document;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * {@code app.documents.*} (§5). The limit and the allow-list are read here and nowhere else, so
 * the check, the message the user is shown and the multipart configuration cannot drift apart.
 */
@Component
@ConfigurationProperties(prefix = "app.documents")
@Getter
@Setter
public class DocumentProperties {

    public static final String LOCAL = "local";
    public static final String NONE = "none";

    /** {@code DOCUMENT_STORAGE}: {@value #LOCAL} or {@value #NONE}. */
    private String storage = LOCAL;

    /** {@code DOCUMENT_MAX_BYTES}, 10 MB by default (answer 7 of §1). */
    private long maxSizeBytes = 10L * 1024 * 1024;

    /** What may be attached, matched against the type detected from the bytes (AC-C7). */
    private List<String> allowedTypes = List.of(ContentSniffer.PDF, ContentSniffer.PNG,
            ContentSniffer.JPEG, ContentSniffer.DOCX, ContentSniffer.XLSX);

    private final Local local = new Local();

    @Getter
    @Setter
    public static class Local {
        /** {@code DOCUMENT_ROOT}: the directory the bytes live under. */
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

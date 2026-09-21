package com.geneinvoice.document;

import java.time.Instant;
import java.util.Locale;

public class DocumentDtos {

    public record UploadedBy(Long userId, String name) {}

    public record DocumentDto(
            Long id,
            DocumentEntityType entityType,
            Long entityId,
            String entityLabel,
            String entityLink,
            String filename,
            String contentType,
            long sizeBytes,
            String sizeLabel,
            DocumentVisibility visibility,
            String description,
            UploadedBy uploadedBy,
            Instant uploadedAt,
            boolean canDownload,
            boolean canEdit,
            boolean canDelete
    ) {}

    public record DocumentCount(long count) {}

    public record PatchDocumentRequest(String description, String visibility) {}

    static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (oneDecimal(kb) < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576d);
    }

    private static double oneDecimal(double value) {
        return Math.round(value * 10d) / 10d;
    }
}

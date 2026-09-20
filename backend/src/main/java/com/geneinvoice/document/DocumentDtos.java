package com.geneinvoice.document;

import java.time.Instant;
import java.util.Locale;

public class DocumentDtos {

    /** Who uploaded it, as a snapshot: the name stands even after the account goes. */
    public record UploadedBy(Long userId, String name) {}

    /**
     * One document as the caller may see it. The three {@code can*} flags are what this caller may
     * do with this document, so the UI shows no button that would predictably 403 (AC-C22).
     */
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

    /** The tab's badge (AC-C1). */
    public record DocumentCount(long count) {}

    /** What may be changed after the fact; a field left out is left alone. */
    public record PatchDocumentRequest(String description, String visibility) {}

    /**
     * A size a person reads: {@code 512 B}, {@code 4.0 KB}, {@code 1.2 MB}.
     *
     * <p>The unit is chosen from the number that will be <em>printed</em>, not from the one that
     * was divided. A byte short of a megabyte is 1023.999 KB, which is under the limit but prints
     * as "1024.0 KB" — a unit that has already rolled over, written as if it had not (DOC-8).
     */
    static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (oneDecimal(kb) < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576d);
    }

    /** What {@code %.1f} will make of it, as a number to compare. */
    private static double oneDecimal(double value) {
        return Math.round(value * 10d) / 10d;
    }
}

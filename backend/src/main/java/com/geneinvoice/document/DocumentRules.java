package com.geneinvoice.document;

import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentRules {

    static final String NO_FILE = "Choose a file";
    static final String DISALLOWED =
            "Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)";
    static final String UNREADABLE = "The file could not be read";

    static final String BAD_FILENAME =
            "The file's name cannot be read. Rename it and try again";
    static final String UNNAMED = "file";

    private final DocumentProperties properties;
    private final DocumentScanner scanner;

    public record Staged(Path file, String filename, String contentType, long sizeBytes)
            implements AutoCloseable {

        @Override
        public void close() {
            deleteQuietly(file);
        }
    }

    public Staged stage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid(NO_FILE);
        }
        long max = properties.getMaxSizeBytes();
        if (file.getSize() > max) {
            throw invalid(tooLarge(max));
        }
        String filename = cleanFilename(file.getOriginalFilename());
        Path temp = null;
        try {
            temp = Files.createTempFile("geneinvoice-upload-", ".part");
            long size = copy(file, temp, max);
            String contentType = ContentSniffer.detect(temp);
            if (contentType == null || !properties.getAllowedTypes().contains(contentType)) {
                throw invalid(DISALLOWED);
            }
            scanner.scan(temp, filename, contentType);
            Staged staged = new Staged(temp, filename, contentType, size);
            temp = null;
            return staged;
        } catch (IOException e) {
            log.error("Could not read the upload {}", filename, e);
            throw new DocumentStorageException(500, UNREADABLE, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    public String description(String raw) {
        if (raw == null) return null;
        String trimmed = cleaned(raw, DocumentRules::isTextInANote).trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > FieldLimits.DOCUMENT_DESCRIPTION) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("description",
                    "must be at most " + FieldLimits.DOCUMENT_DESCRIPTION + " characters"));
        }
        return trimmed;
    }

    static String tooLarge(long maxBytes) {
        return "The file is larger than " + megabytes(maxBytes);
    }

    static String cleanFilename(String raw) {
        if (raw == null) return UNNAMED;
        String lastSegment = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        String cleaned = cleaned(lastSegment, DocumentRules::isTextInAName).trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return UNNAMED;
        return cut(cleaned, FieldLimits.DOCUMENT_FILENAME);
    }

    private static String cleaned(String raw, IntPredicate keep) {
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().filter(keep).forEach(kept::appendCodePoint);
        return kept.toString();
    }

    private static boolean isTextInAName(int c) {
        if (Character.isISOControl(c)) return false;
        int type = Character.getType(c);
        return type != Character.FORMAT
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR;
    }

    private static boolean isTextInANote(int c) {
        if (c == '\n' || c == '\r' || c == '\t') return true;
        return isTextInAName(c);
    }

    private long copy(MultipartFile file, Path temp, long max) throws IOException {
        long size = 0;
        try (InputStream in = file.getInputStream(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) {
                size += read;
                if (size > max) {
                    throw invalid(tooLarge(max));
                }
                out.write(buffer, 0, read);
            }
        }
        return size;
    }

    private static GlobalExceptionHandler.InvalidFieldsException invalid(String message) {
        return new GlobalExceptionHandler.InvalidFieldsException(Map.of("file", message));
    }

    private static String megabytes(long bytes) {
        double mb = bytes / 1048576d;
        return (mb == Math.rint(mb)
                ? String.valueOf((long) mb)
                : String.format(Locale.ROOT, "%.1f", mb)) + " MB";
    }

    private static String cut(String text, int max) {
        if (text.length() <= max) return text;
        int end = max > 0 && Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove the staged upload {}: {}", file, e.getMessage());
        }
    }
}

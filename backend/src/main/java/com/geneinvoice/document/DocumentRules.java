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

/**
 * What may be attached, and what an uploaded file is called (§4.3). Everything an uploader sends
 * passes through here before storage sees it: the size is counted as the bytes arrive, the type is
 * read from the bytes themselves, and the name is cleaned for display and then never used again.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentRules {

    static final String NO_FILE = "Choose a file";
    static final String DISALLOWED =
            "Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)";
    static final String UNREADABLE = "The file could not be read";

    /**
     * A name the container would not hand over at all. {@link #cleanFilename} drops control
     * characters itself, but Tomcat refuses some placements of a null byte while parsing the
     * request, before any of this is reached, so that refusal needs the app's words too
     * (see {@code DocumentUploadAdvice}).
     */
    static final String BAD_FILENAME =
            "The file's name cannot be read. Rename it and try again";
    /** What a file with nothing usable in its name is called. */
    static final String UNNAMED = "file";

    private final DocumentProperties properties;
    private final DocumentScanner scanner;

    /**
     * An upload on disk, checked and ready to store, and the temp file it lives in until then.
     * Closing it removes that file, which the upload does whichever way it ends.
     */
    public record Staged(Path file, String filename, String contentType, long sizeBytes)
            implements AutoCloseable {

        @Override
        public void close() {
            deleteQuietly(file);
        }
    }

    /**
     * Takes the upload, in order: a file is there, it is not too big, it is one of the kinds
     * allowed, and the scanner has seen it. Nothing has been stored and no row written when any of
     * those refuses, so there is nothing to undo.
     */
    public Staged stage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid(NO_FILE);
        }
        long max = properties.getMaxSizeBytes();
        // What the client declared, refused before a byte is read; the copy below is the real check.
        if (file.getSize() > max) {
            throw invalid(tooLarge(max));
        }
        String filename = cleanFilename(file.getOriginalFilename());
        Path temp = null;
        try {
            temp = Files.createTempFile("geneinvoice-upload-", ".part");
            long size = copy(file, temp, max);
            String contentType = ContentSniffer.detect(temp);
            // What the client called it is ignored: the bytes decide, and the bytes are stored.
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

    /**
     * A description as it will be stored: cleaned, trimmed, null when blank, refused when too
     * long. Cleaning is the same idea as {@link #cleanFilename}'s — a character that cannot be
     * seen is not text a user typed — but keeps the line breaks and tabs a note may be written
     * with. It matters as well as reads: a null byte cannot be stored in a text column at all, and
     * one reaching the database was answered as a conflict with existing data, which it is not
     * (DOC-4).
     */
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

    /**
     * The name to show. Only the last segment of what was sent survives, so {@code ../../etc/passwd}
     * is {@code passwd} and an absolute path is its own last name; everything that is not a
     * character a reader can see is dropped, and what is left is cut to fit the column without
     * splitting a character in half. It is never a path: storage keys are generated (AC-C8).
     */
    static String cleanFilename(String raw) {
        if (raw == null) return UNNAMED;
        String lastSegment = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        String cleaned = cleaned(lastSegment, DocumentRules::isTextInAName).trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return UNNAMED;
        return cut(cleaned, FieldLimits.DOCUMENT_FILENAME);
    }

    /** The code points of {@code raw} that {@code keep} accepts, in order. */
    private static String cleaned(String raw, IntPredicate keep) {
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().filter(keep).forEach(kept::appendCodePoint);
        return kept.toString();
    }

    /**
     * Whether a code point belongs in a name that will be shown to somebody. Control characters go,
     * as they always have — a null byte among them — and so does everything else that is not there
     * to be read: the bidi controls (U+202E and its family), the zero-width characters, the byte
     * order mark, and the two separators that break a line. A name is one line of text, and a
     * character that reorders or hides the rest of it is a way of making a file look like another
     * file — {@code invoice}, U+202E, {@code fdp.exe} reads as {@code invoiceexe.pdf} — both in the
     * documents tab and in the {@code Content-Disposition} header a browser saves it under
     * (DOC-6, §4.3).
     */
    private static boolean isTextInAName(int c) {
        if (Character.isISOControl(c)) return false;
        int type = Character.getType(c);
        return type != Character.FORMAT
                && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * The same, for a note somebody typed: it may have more than one line, so the characters that
     * write one keep their place. Everything else a name drops goes here too — a description is
     * shown beside its file in the documents tab, and U+202E reorders what it says there exactly as
     * it would in the name (DOC-6, §4.3).
     */
    private static boolean isTextInANote(int c) {
        if (c == '\n' || c == '\r' || c == '\t') return true;
        return isTextInAName(c);
    }

    /**
     * Copies the upload to {@code temp}, counting as it goes and stopping the moment it is over the
     * limit — a declared size can lie, and a streamed upload has none until it is over (AC-C6).
     */
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

    /** {@code 10485760} as "10 MB", {@code 5242880} as "5 MB", anything else to one decimal. */
    private static String megabytes(long bytes) {
        double mb = bytes / 1048576d;
        return (mb == Math.rint(mb)
                ? String.valueOf((long) mb)
                : String.format(Locale.ROOT, "%.1f", mb)) + " MB";
    }

    /**
     * At most {@code max} of the column's characters, never ending between the two halves of a
     * character outside the BMP (an emoji): the half left over is not UTF-8, and Postgres would
     * store it as '?'. The whole character is dropped instead.
     */
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

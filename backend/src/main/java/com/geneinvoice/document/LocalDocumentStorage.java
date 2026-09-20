package com.geneinvoice.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The default storage: a directory on the machine the backend runs on, so the app works on a
 * laptop with no cloud account (AC-C17). The root is {@code app.documents.local.root}; it is made
 * if it is not there, and startup stops naming {@code DOCUMENT_ROOT} when it cannot be made, is
 * not a directory, or cannot be written to (AC-C15) — the way missing mail settings already stop
 * startup.
 *
 * <p>Every key is resolved against the root and checked to still be inside it. Keys are generated
 * by {@link DocumentService} and nothing from an uploader reaches one, so the check is a backstop
 * — but a backstop that is there rather than assumed (§4.4).
 */
@Component
@ConditionalOnProperty(name = "app.documents.storage", havingValue = DocumentProperties.LOCAL,
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class LocalDocumentStorage implements DocumentStorage, InitializingBean {

    static final String COULD_NOT_STORE = "The file could not be stored";
    static final String NO_LONGER_STORED = "The file is no longer in storage";
    private static final String UNUSABLE_ROOT =
            "Set DOCUMENT_ROOT to a directory the app can write documents to: ";

    private final DocumentProperties properties;

    private Path root;

    @Override
    public void afterPropertiesSet() {
        Path configured = Path.of(properties.getLocal().getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(configured);
        } catch (IOException e) {
            throw new IllegalStateException(UNUSABLE_ROOT + configured + " (" + e.getMessage() + ")", e);
        }
        if (!Files.isDirectory(configured) || !Files.isWritable(configured)) {
            throw new IllegalStateException(UNUSABLE_ROOT + configured);
        }
        root = configured;
        log.info("Documents are stored under {}", root);
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    /**
     * Writes to a temp file beside the target, then moves it into place, so the key holds either
     * the whole file or nothing at all. The size is counted again here: the caller has already
     * refused an over-large upload, and this is storage's own floor under that.
     */
    @Override
    public StoredFile put(InputStream in, String key, String contentType, long maxBytes) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    size += read;
                    if (size > maxBytes) {
                        throw new DocumentStorageException(500,
                                COULD_NOT_STORE + ": more than " + maxBytes + " bytes");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            move(temp, target);
            temp = null;
            return new StoredFile(size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.error("Could not store document {}", key, e);
            throw new DocumentStorageException(500, COULD_NOT_STORE, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (NoSuchFileException e) {
            log.error("Document {} has no bytes in storage", key);
            throw new DocumentStorageException(500, NO_LONGER_STORED, e);
        } catch (IOException e) {
            log.error("Could not read document {}", key, e);
            throw new DocumentStorageException(500, NO_LONGER_STORED, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            // Nothing the caller can do about it: the row is already gone or was never written.
            log.warn("Could not remove the bytes of document {}: {}", key, e.getMessage());
        }
    }

    /** The absolute path of a key, which must lie inside the root — a check, not a hope (§4.4). */
    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new DocumentStorageException(500, COULD_NOT_STORE);
        }
        return resolved;
    }

    /** Atomically where the filesystem can; a plain replace where it cannot. */
    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove the half-written file {}: {}", file, e.getMessage());
        }
    }
}

package com.geneinvoice.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a file actually is, read from its own first bytes rather than from its extension or from
 * what the client said it was (AC-C7). An Office file is a ZIP, so the four signature bytes only
 * say "ZIP": which Office file it is comes from the parts inside, which is why this is given the
 * staged file rather than a handful of bytes.
 */
final class ContentSniffer {

    static final String PDF = "application/pdf";
    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** The extension a stored file is given; nothing from the uploader's name is used (§4.4). */
    private static final Map<String, String> EXTENSIONS = Map.of(
            PDF, "pdf", PNG, "png", JPEG, "jpg", DOCX, "docx", XLSX, "xlsx");

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

    private static final String OPEN_XML_MANIFEST = "[Content_Types].xml";

    private ContentSniffer() {}

    /** The type the bytes are, or null when they are none this app accepts. */
    static String detect(Path file) {
        byte[] head = head(file);
        if (starts(head, PDF_MAGIC)) return PDF;
        if (starts(head, PNG_MAGIC)) return PNG;
        if (starts(head, JPEG_MAGIC)) return JPEG;
        if (starts(head, ZIP_MAGIC)) return openXml(file);
        return null;
    }

    static String extensionOf(String contentType) {
        return EXTENSIONS.getOrDefault(contentType, "bin");
    }

    /**
     * A ZIP is a DOCX or an XLSX when it carries the Open XML manifest and a {@code word/} or
     * {@code xl/} part. Anything else — a plain ZIP, a JAR, an ODT — is not something this app
     * accepts, so it is left unrecognised rather than guessed at.
     */
    private static String openXml(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            boolean manifest = zip.getEntry(OPEN_XML_MANIFEST) != null;
            if (!manifest) return null;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (name.startsWith("word/")) return DOCX;
                if (name.startsWith("xl/")) return XLSX;
            }
            return null;
        } catch (IOException e) {
            // Not a ZIP this JVM can read, whatever its first four bytes say.
            return null;
        }
    }

    private static byte[] head(Path file) {
        byte[] head = new byte[8];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length) {
                byte[] shorter = new byte[read];
                System.arraycopy(head, 0, shorter, 0, read);
                return shorter;
            }
            return head;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static boolean starts(byte[] bytes, byte[] magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) return false;
        }
        return true;
    }
}

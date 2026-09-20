package com.geneinvoice.mail.tracking;

import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.gmail.GmailMime;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tells a delivery-status notice (a bounce) from any other message, and reads what it says (§4.7
 * step 5). A notice is a {@code multipart/report; report-type=delivery-status} (RFC 3464), as Gmail
 * writes its "Delivery Status Notification (Failure)", or a message from {@code mailer-daemon@} or
 * {@code postmaster@} carrying {@code X-Failed-Recipients}, as some servers write theirs.
 */
public final class DsnParser {

    static final String DEFAULT_ERROR = "The recipient's mail server rejected the message";
    private static final int ERROR_MAX = 1000;
    /** How deep nested multiparts are followed; real notices nest two or three levels. */
    private static final int MAX_DEPTH = 8;

    private DsnParser() {}

    /** One recipient's block of the {@code message/delivery-status} part. */
    public record Recipient(String address, String action, String status, String diagnosticCode) {

        boolean failed() {
            return "failed".equals(action);
        }
    }

    /**
     * @param recipients        the per-recipient blocks, in order
     * @param failedRecipients  the addresses of the {@code X-Failed-Recipients} header
     * @param originalMessageId the Message-ID of the message the notice is about, when it says
     */
    public record Report(List<Recipient> recipients, List<String> failedRecipients, String originalMessageId) {

        /** Delivery failed: a recipient's {@code Action: failed}, or {@code X-Failed-Recipients} alone. */
        public boolean failed() {
            if (recipients.stream().anyMatch(Recipient::failed)) return true;
            return recipients.stream().allMatch(r -> r.action() == null) && !failedRecipients.isEmpty();
        }

        /** Only delayed: the server is still trying, so nothing is decided yet. */
        public boolean delayed() {
            return !failed() && !recipients.isEmpty() && recipients.stream().allMatch(r -> "delayed".equals(r.action()));
        }

        /** The addresses that failed, in lower case. */
        public Set<String> failedAddresses() {
            Set<String> addresses = new LinkedHashSet<>();
            recipients.stream().filter(Recipient::failed).map(Recipient::address)
                    .filter(a -> a != null).forEach(a -> addresses.add(a.toLowerCase(Locale.ROOT)));
            failedRecipients.forEach(a -> addresses.add(a.toLowerCase(Locale.ROOT)));
            return addresses;
        }

        /**
         * What the copy to {@code address} shows: {@code "{Status} {Diagnostic-Code}"} of its failed
         * block (else the first failed block), without the {@code smtp;} prefix, or a plain sentence
         * when the notice gives neither.
         */
        public String error(String address) {
            Recipient block = recipients.stream()
                    .filter(r -> r.failed() && r.address() != null && r.address().equalsIgnoreCase(address))
                    .findFirst()
                    .orElse(recipients.stream().filter(Recipient::failed).findFirst().orElse(null));
            if (block == null) return DEFAULT_ERROR;
            String status = block.status() == null ? "" : block.status();
            String diagnostic = block.diagnosticCode() == null ? "" : block.diagnosticCode();
            String error = (status + " " + diagnostic).trim();
            return error.isEmpty() ? DEFAULT_ERROR : MailText.fit(error, ERROR_MAX);
        }
    }

    /** The report when the message is a delivery-status notice, else empty. */
    public static Optional<Report> parse(byte[] raw) throws MessagingException {
        return parse(GmailMime.read(raw));
    }

    public static Optional<Report> parse(MimeMessage message) throws MessagingException {
        List<String> failedRecipients = failedRecipients(message);
        if (!isReport(message) && !(fromMailerDaemon(message) && message.getHeader("X-Failed-Recipients") != null)) {
            return Optional.empty();
        }
        Found found = new Found();
        try {
            walk(message, found, 0);
        } catch (IOException e) {
            throw new MessagingException("The delivery-status notice could not be read", e);
        }
        String originalId = found.originalMessageId;
        if (originalId == null) originalId = found.perMessage.get("x-original-message-id");
        if (originalId == null) originalId = GmailMime.messageId(message.getHeader("In-Reply-To", " "));
        return Optional.of(new Report(found.recipients, failedRecipients,
                originalId == null ? null : GmailMime.messageId(originalId)));
    }

    /** What the walk through the parts finds. */
    private static final class Found {
        final List<Recipient> recipients = new ArrayList<>();
        final Map<String, String> perMessage = new LinkedHashMap<>();
        String originalMessageId;
        boolean statusRead;
    }

    private static void walk(Part part, Found found, int depth) throws MessagingException, IOException {
        if (depth > MAX_DEPTH) return;
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = new MimeMultipart(part.getDataHandler().getDataSource());
            for (int i = 0; i < multipart.getCount(); i++) walk(multipart.getBodyPart(i), found, depth + 1);
        } else if (part.isMimeType("message/delivery-status") || part.isMimeType("message/global-delivery-status")) {
            if (!found.statusRead) {
                found.statusRead = true;
                readStatus(text(part), found);
            }
        } else if (part.isMimeType("message/rfc822") || part.isMimeType("text/rfc822-headers")
                || part.isMimeType("message/global") || part.isMimeType("message/global-headers")) {
            if (found.originalMessageId == null) {
                try (InputStream in = part.getInputStream()) {
                    String id = new InternetHeaders(in, true).getHeader("Message-ID", " ");
                    found.originalMessageId = GmailMime.messageId(id);
                }
            }
        }
    }

    /**
     * The delivery-status fields: a block for the message, then one per recipient, separated by blank
     * lines, each written like mail headers (folded lines continue the one before).
     */
    private static void readStatus(String text, Found found) {
        for (String block : text.replace("\r\n", "\n").replace('\r', '\n').split("\n[ \t]*\n")) {
            Map<String, String> fields = fields(block);
            if (fields.isEmpty()) continue;
            if (fields.containsKey("final-recipient") || fields.containsKey("original-recipient")
                    || fields.containsKey("action")) {
                String recipient = fields.getOrDefault("final-recipient", fields.get("original-recipient"));
                String action = fields.get("action");
                found.recipients.add(new Recipient(address(recipient),
                        action == null ? null : action.trim().toLowerCase(Locale.ROOT),
                        fields.get("status"), diagnostic(fields.get("diagnostic-code"))));
            } else {
                fields.forEach(found.perMessage::putIfAbsent);
            }
        }
    }

    private static Map<String, String> fields(String block) {
        Map<String, String> fields = new LinkedHashMap<>();
        String name = null;
        StringBuilder value = new StringBuilder();
        for (String line : block.split("\n")) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
                value.append(' ').append(line.trim());
                continue;
            }
            if (name != null) fields.putIfAbsent(name, value.toString().trim());
            int colon = line.indexOf(':');
            if (colon <= 0) {
                name = null;
                continue;
            }
            name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            value = new StringBuilder(line.substring(colon + 1).trim());
        }
        if (name != null) fields.putIfAbsent(name, value.toString().trim());
        return fields;
    }

    /** {@code rfc822; bob@acme.test} → {@code bob@acme.test}. */
    private static String address(String typed) {
        if (typed == null) return null;
        int semicolon = typed.indexOf(';');
        String address = (semicolon >= 0 ? typed.substring(semicolon + 1) : typed).trim();
        if (address.startsWith("<") && address.endsWith(">")) address = address.substring(1, address.length() - 1).trim();
        return address.isEmpty() ? null : address;
    }

    /** {@code smtp; 550 5.1.1 …} → {@code 550 5.1.1 …}. */
    private static String diagnostic(String code) {
        if (code == null) return null;
        String text = code.trim();
        if (text.regionMatches(true, 0, "smtp;", 0, 5)) text = text.substring(5).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean isReport(MimeMessage message) throws MessagingException {
        if (!message.isMimeType("multipart/report")) return false;
        try {
            String type = new ContentType(message.getContentType()).getParameter("report-type");
            return type != null && type.trim().equalsIgnoreCase("delivery-status");
        } catch (MessagingException e) {
            return false;
        }
    }

    private static boolean fromMailerDaemon(MimeMessage message) throws MessagingException {
        String from = GmailMime.address(message.getHeader("From", ","));
        if (from == null) return false;
        String local = from.substring(0, Math.max(0, from.indexOf('@'))).toLowerCase(Locale.ROOT);
        return local.equals("mailer-daemon") || local.equals("postmaster");
    }

    private static List<String> failedRecipients(MimeMessage message) throws MessagingException {
        List<String> addresses = new ArrayList<>();
        String header = message.getHeader("X-Failed-Recipients", ",");
        if (header == null || header.isBlank()) return addresses;
        try {
            for (InternetAddress address : InternetAddress.parseHeader(MimeUtility.unfold(header), false)) {
                if (address.getAddress() != null && !address.getAddress().isBlank()) addresses.add(address.getAddress().trim());
            }
        } catch (AddressException e) {
            for (String token : header.split("[,\\s]+")) if (token.contains("@")) addresses.add(token.trim());
        }
        return addresses;
    }

    private static String text(Part part) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

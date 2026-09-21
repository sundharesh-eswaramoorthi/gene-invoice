package com.geneinvoice.mail.gmail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Email as MIME: writing what the app sends, and reading whatever mail program wrote what it receives. */
@Slf4j
public final class GmailMime {

    private GmailMime() {}

    /** For writing: non-ASCII headers go out as encoded words, which every mail program reads. */
    private static final Session SESSION = Session.getInstance(new Properties());
    /**
     * For reading: 8-bit header text is UTF-8, as RFC 6532 mail writes it. Without this a raw UTF-8
     * subject or name is read as ISO-8859-1 and comes out garbled.
     */
    private static final Session READING = readingSession();
    private static final String UTF_8 = "UTF-8";
    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");
    /** Where HTML asked for a line break ({@code <br>}) or a paragraph break; private-use characters. */
    private static final String LINE_BREAK = "\uE000";
    private static final String PARAGRAPH_BREAK = "\uE001";
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
    /**
     * How much of an HTML body is reduced to text. The saved body is cut to 20,000 characters, so more
     * would only cost time.
     */
    static final int HTML_MAX = 200_000;
    /** Elements whose content is not text to read. */
    private static final Set<String> HIDDEN_ELEMENTS = Set.of("head", "script", "style", "title");
    /** End tags after which a blank line follows. */
    private static final Set<String> PARAGRAPH_ENDS = Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "ul", "ol");
    /** Tags at a block's edge, opening or closing: what follows starts a new line. */
    private static final Set<String> BLOCK_EDGES = Set.of("p", "div", "tr", "table", "ul", "ol",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "hr", "section", "article", "header", "footer");
    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("nbsp", " "), Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("hellip", "…"), Map.entry("ndash", "–"),
            Map.entry("mdash", "—"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"),
            Map.entry("rdquo", "”"), Map.entry("bull", "•"), Map.entry("middot", "·"), Map.entry("copy", "©"),
            Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("euro", "€"), Map.entry("pound", "£"));

    private static Session readingSession() {
        Properties properties = new Properties();
        properties.setProperty("mail.mime.allowutf8", "true");
        return Session.getInstance(properties);
    }

    // ---- writing ---------------------------------------------------------------------

    /**
     * One copy for one recipient with nothing attached: a single text/plain part, byte for byte as
     * this service has always written it.
     */
    public static byte[] build(MailAddress from, MailAddress to, String subject, String body, String messageId,
                               Instant date) throws MessagingException {
        return build(from, to, subject, body, messageId, date, List.of());
    }

    /**
     * One copy for one recipient: UTF-8 throughout (non-ASCII subjects and names become encoded
     * words) and the Message-ID fixed when the copy was submitted, which a bounce or a reply quotes
     * back to find the copy. The To header holds only this recipient (M6).
     *
     * <p>With no attachments the message is one text/plain part and the bytes are exactly what the
     * six-argument {@code build} has always produced — the same {@code setText} call on the message
     * itself, not a body part wrapped in a multipart. With attachments it becomes
     * {@code multipart/mixed}: the same text as the first part, then one part per file carrying its
     * own name and type and marked {@code Content-Disposition: attachment}, which is what every
     * mail program shows as a paperclip. Jakarta Mail picks base64 for the file parts itself when
     * the headers are written, and picks the multipart boundary, so two builds of the same message
     * differ in that boundary alone.
     *
     * @param attachments in the order they should appear; never null, may be empty
     */
    public static byte[] build(MailAddress from, MailAddress to, String subject, String body, String messageId,
                               Instant date, List<Attachment> attachments) throws MessagingException {
        MimeMessage message = new MimeMessage(SESSION) {
            @Override
            protected void updateMessageID() throws MessagingException {
                if (messageId == null) super.updateMessageID();
                else setHeader("Message-ID", messageId);
            }
        };
        message.setFrom(internetAddress(from));
        message.setRecipient(Message.RecipientType.TO, internetAddress(to));
        message.setSubject(subject == null ? "" : subject, UTF_8);
        message.setSentDate(Date.from(date));
        // Mail lines end in CRLF; a text area's bare LF is not a line break to every receiver.
        String text = body == null ? "" : body.replaceAll("\\r\\n|\\r|\\n", "\r\n");
        if (attachments == null || attachments.isEmpty()) {
            message.setText(text, UTF_8);
        } else {
            MimeMultipart mixed = new MimeMultipart("mixed");
            MimeBodyPart said = new MimeBodyPart();
            said.setText(text, UTF_8);
            mixed.addBodyPart(said);
            for (Attachment attachment : attachments) {
                mixed.addBodyPart(filePart(attachment));
            }
            message.setContent(mixed);
        }
        message.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            message.writeTo(out);
        } catch (IOException e) {
            throw new MessagingException("The message could not be written", e);
        }
        return out.toByteArray();
    }

    /**
     * One file as a MIME part. The type is set from the data handler and again as a header, so the
     * {@code name} parameter that older mail programs read lands on it as well; the name itself is
     * an encoded word when it is not ASCII, which is what Gmail and Outlook both write and read.
     *
     * <p>base64 is asked for rather than left to Jakarta Mail, which would send a file whose bytes
     * happen to be 7-bit clean as-is. A file is not text: it may hold a line longer than the 998
     * characters a mail line may be, or a bare CR that a gateway would rewrite, and either would
     * reach the recipient as a corrupted file. base64 costs a third of the size and no surprises.
     */
    private static MimeBodyPart filePart(Attachment attachment) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        String type = contentType(attachment.contentType());
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment.content(), type)));
        part.setHeader("Content-Type", type);
        part.setHeader("Content-Transfer-Encoding", "base64");
        part.setDisposition(Part.ATTACHMENT);
        String name = cleanFileName(attachment.filename());
        try {
            part.setFileName(MimeUtility.encodeText(name.isEmpty() ? "attachment" : name, UTF_8, null));
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("UTF-8 is not available", e);
        }
        return part;
    }

    /**
     * A name that is safe in a header and readable in a mail program: no line breaks or other
     * control characters — one of those could end the header and start another of the sender's
     * choosing — no run of blanks, and no directory part, which is not the recipient's business
     * and is how a name reaches a path it should not. Empty when nothing usable is left; the
     * submit then refuses the file, and {@link #filePart} falls back to a plain name.
     */
    public static String cleanFileName(String filename) {
        if (filename == null) return "";
        String name = filename.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash < 0 ? name : name.substring(slash + 1).trim();
    }

    /** The given type when it is a type at all, and {@code application/octet-stream} when it is not. */
    static String contentType(String given) {
        if (given == null || given.isBlank()) return Attachment.DEFAULT_CONTENT_TYPE;
        String type = given.trim();
        try {
            ContentType parsed = new ContentType(type);
            if (parsed.getPrimaryType() == null || parsed.getSubType() == null) {
                return Attachment.DEFAULT_CONTENT_TYPE;
            }
            return type;
        } catch (ParseException e) {
            return Attachment.DEFAULT_CONTENT_TYPE;
        }
    }

    /** A received message as Jakarta Mail reads it, raw 8-bit headers as UTF-8. */
    public static MimeMessage read(byte[] raw) throws MessagingException {
        return new MimeMessage(READING, new ByteArrayInputStream(raw));
    }

    private static InternetAddress internetAddress(MailAddress address) throws MessagingException {
        String email = address.address() == null ? "" : address.address().trim();
        String name = address.name() == null || address.name().isBlank() ? null : address.name().trim();
        try {
            InternetAddress internet = new InternetAddress(email, name, UTF_8);
            internet.validate();
            return internet;
        } catch (AddressException e) {
            throw new AddressException("Invalid email address \"" + email + "\": " + e.getMessage());
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("UTF-8 is not available", e);
        }
    }

    // ---- reading ---------------------------------------------------------------------

    /**
     * Reads a received message. Headers are parsed leniently and a body that cannot be read leaves
     * the text empty, so one odd message still links to its record rather than being lost. No text
     * that comes back holds a NUL, which Postgres cannot store.
     *
     * @param receivedAt when Gmail received it; the Date header is used only when this is null
     */
    public static IncomingMail parse(String providerMessageId, String providerThreadId, Instant receivedAt, byte[] raw)
            throws MessagingException {
        MimeMessage message = read(raw);
        List<MailAddress> from = addresses(message, "From");
        String subject = message.getSubject();
        Instant when = receivedAt;
        if (when == null) {
            Date sent = message.getSentDate();
            when = sent == null ? Instant.now() : sent.toInstant();
        }
        List<String> inReplyTo = messageIds(header(message, "In-Reply-To"));
        return new IncomingMail(providerMessageId, providerThreadId,
                messageIds(header(message, "Message-ID")).stream().findFirst().orElse(null),
                inReplyTo.isEmpty() ? null : inReplyTo.get(0),
                messageIds(header(message, "References")),
                from.isEmpty() ? null : from.get(0),
                addresses(message, "To"),
                addresses(message, "Cc"),
                subject == null ? "" : withoutNul(subject).trim(),
                withoutNul(body(message, providerMessageId)),
                when);
    }

    private static String header(MimeMessage message, String name) throws MessagingException {
        String value = message.getHeader(name, " ");
        return value == null ? null : withoutNul(MimeUtility.unfold(value)).trim();
    }

    /** A NUL comes from a mislabelled charset or an encoded word; it carries nothing to read. */
    private static String withoutNul(String text) {
        return text == null || text.indexOf('\0') < 0 ? text : text.replace("\0", "");
    }

    /** The first Message-ID in a header value such as In-Reply-To, or null. */
    public static String messageId(String header) {
        List<String> ids = messageIds(header == null ? null : withoutNul(MimeUtility.unfold(header)).trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Bracketed Message-IDs in order; a header that has none is taken whole, as some programs write it. */
    private static List<String> messageIds(String header) {
        List<String> ids = new ArrayList<>();
        if (header == null || header.isBlank()) return ids;
        Matcher m = MESSAGE_ID.matcher(header);
        while (m.find()) ids.add(m.group());
        if (ids.isEmpty()) {
            for (String token : header.trim().split("\\s+")) ids.add(token);
        }
        return ids;
    }

    /** An address list with group syntax flattened; names come back decoded from encoded words. */
    private static List<MailAddress> addresses(MimeMessage message, String headerName) throws MessagingException {
        List<MailAddress> result = new ArrayList<>();
        String value = message.getHeader(headerName, ",");
        if (value == null || value.isBlank()) return result;
        InternetAddress[] parsed;
        try {
            parsed = InternetAddress.parseHeader(MimeUtility.unfold(value), false);
        } catch (AddressException e) {
            log.debug("Unreadable {} header: {}", headerName, e.getMessage());
            return result;
        }
        for (InternetAddress address : parsed) {
            if (address.isGroup()) {
                try {
                    for (InternetAddress member : address.getGroup(false)) add(result, member);
                } catch (AddressException e) {
                    log.debug("Unreadable group in {} header: {}", headerName, e.getMessage());
                }
            } else {
                add(result, address);
            }
        }
        return result;
    }

    /** The first address in a header value such as {@code Jane Doe <jane@company.com>}, or null. */
    public static String address(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            for (InternetAddress parsed : InternetAddress.parseHeader(MimeUtility.unfold(header), false)) {
                if (parsed.getAddress() != null && !parsed.getAddress().isBlank()) return parsed.getAddress().trim();
            }
        } catch (AddressException e) {
            log.debug("Unreadable address header: {}", e.getMessage());
        }
        return null;
    }

    private static void add(List<MailAddress> list, InternetAddress address) {
        String email = withoutNul(address.getAddress());
        String name = withoutNul(address.getPersonal());
        email = email == null || email.isBlank() ? null : email.trim();
        name = name == null || name.isBlank() ? null : name.trim();
        if (email != null || name != null) list.add(new MailAddress(name, email));
    }

    /** The plain text if the message has any, else its HTML reduced to text. */
    private static String body(Part message, String providerMessageId) {
        try {
            String plain = firstText(message, "text/plain");
            if (plain != null) return tidy(plain);
            String html = firstText(message, "text/html");
            return html == null ? "" : tidy(htmlToText(html));
        } catch (MessagingException | IOException | RuntimeException e) {
            log.warn("The body of Gmail message {} could not be read: {}", providerMessageId, e.getMessage());
            return "";
        }
    }

    /** Depth first through nested multiparts, passing over attachments. */
    private static String firstText(Part part, String mimeType) throws MessagingException, IOException {
        if (isAttachment(part)) return null;
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = new MimeMultipart(part.getDataHandler().getDataSource());
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = firstText(multipart.getBodyPart(i), mimeType);
                if (text != null) return text;
            }
            return null;
        }
        return part.isMimeType(mimeType) ? decode(part) : null;
    }

    private static boolean isAttachment(Part part) {
        try {
            return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
        } catch (MessagingException e) {
            return false;
        }
    }

    /** The transfer encoding undone, then the declared charset — UTF-8 when it is missing or unknown. */
    private static String decode(Part part) throws MessagingException, IOException {
        Charset charset = StandardCharsets.UTF_8;
        try {
            String declared = part.getContentType() == null ? null
                    : new ContentType(part.getContentType()).getParameter("charset");
            if (declared != null && !declared.isBlank()) charset = Charset.forName(MimeUtility.javaCharset(declared.trim()));
        } catch (MessagingException | IllegalArgumentException e) {
            // Garbled or unsupported: UTF-8 still reads the ASCII in it correctly.
        }
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), charset);
        }
    }

    private static String tidy(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    /**
     * Readable text from an HTML-only message: the words and line breaks, without markup. Only the
     * first {@link #HTML_MAX} characters are read, and the time taken grows in step with their length.
     */
    static String htmlToText(String html) {
        String text = decodeEntities(markupReplaced(htmlPrefix(html)))
                .replace('\u00a0', ' ')
                // From the start of a run of spaces only, so a long run of &nbsp; is read once, not from
                // each space in it again.
                .replaceAll("(?<![ \\t])[ \\t]*\n\\s*", "\n")
                // A block that starts right after a break is already on its own line.
                .replaceAll("([" + LINE_BREAK + PARAGRAPH_BREAK + "])\n", "$1")
                .replace(LINE_BREAK, "\n")
                .replace(PARAGRAPH_BREAK, "\n\n");
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            out.append(line.replaceAll(" {2,}", " ").strip()).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    /**
     * At most {@code length} UTF-16 units from the start, never ending between the two halves of a
     * character outside the BMP (an emoji): the half left over is not text, and would be stored as '?'
     * or U+FFFD. The whole character is dropped instead.
     */
    public static String start(String text, int length) {
        if (text.length() <= length) return text;
        int end = length > 0 && Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }

    /**
     * At most {@link #HTML_MAX} characters, without a tag, an entity or a character the cut would leave
     * half written. Half an entity ("&amp;nb") is not markup any more, so it would be read as text.
     */
    private static String htmlPrefix(String html) {
        if (html.length() <= HTML_MAX) return html;
        String prefix = start(html, HTML_MAX);
        int unfinishedTag = prefix.lastIndexOf('<');
        if (unfinishedTag > prefix.lastIndexOf('>')) prefix = prefix.substring(0, unfinishedTag);
        int unfinishedEntity = prefix.lastIndexOf('&');
        if (unfinishedEntity >= 0 && prefix.length() - unfinishedEntity <= LONGEST_ENTITY
                && prefix.substring(unfinishedEntity + 1).matches("#?[A-Za-z0-9]*")) {
            prefix = prefix.substring(0, unfinishedEntity);
        }
        return prefix;
    }

    /** "&amp;#x10FFFF" and the longest named entity we decode are shorter; a longer run is plain text. */
    private static final int LONGEST_ENTITY = 10;

    /**
     * The markup replaced in one pass from start to end: tags at a block's edge by line breaks, other
     * tags and comments by nothing, and head, script, style and title together with their content.
     * Line breaks in the source are only spaces. A search for where something ends that finds nothing
     * is not repeated, so however many tags are left open, no part of the HTML is read more than a
     * few times; regular expressions for the same took time growing with the square of its length.
     */
    private static String markupReplaced(String html) {
        StringBuilder out = new StringBuilder(html.length());
        boolean tagsEnd = true;
        boolean commentsEnd = true;
        // Hidden element name -> a position after which its end tag does not appear.
        Map<String, Integer> neverEnds = new HashMap<>();
        int i = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c != '<') {
                if (!isHtmlSpace(c)) {
                    out.append(c);
                } else if (out.isEmpty() || out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                i++;
                continue;
            }
            if (commentsEnd && html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                if (end >= 0) {
                    i = end + 3;
                    continue;
                }
                commentsEnd = false;
            }
            int close = tagsEnd ? html.indexOf('>', i + 1) : -1;
            if (close < 0) {
                // Nothing closes it, so it is text.
                tagsEnd = false;
                out.append(c);
                i++;
                continue;
            }
            boolean endTag = html.charAt(i + 1) == '/';
            int nameEnd = endTag ? i + 2 : i + 1;
            while (nameEnd < close && isWordChar(html.charAt(nameEnd))) nameEnd++;
            String name = html.substring(endTag ? i + 2 : i + 1, nameEnd).toLowerCase(Locale.ROOT);
            i = close + 1;
            if (!endTag && HIDDEN_ELEMENTS.contains(name) && i < neverEnds.getOrDefault(name, Integer.MAX_VALUE)) {
                int end = endOfElement(html, name, i);
                if (end >= 0) {
                    i = end;
                    continue;
                }
                neverEnds.put(name, i);
            }
            if (!endTag && name.equals("br")) {
                out.append(LINE_BREAK);
            } else if (endTag && PARAGRAPH_ENDS.contains(name)) {
                out.append(PARAGRAPH_BREAK);
            } else if (!endTag && name.equals("li")) {
                out.append(LINE_BREAK).append("- ");
            } else if (BLOCK_EDGES.contains(name)) {
                out.append('\n');
            } else if (endTag && (name.equals("td") || name.equals("th"))) {
                out.append(' ');
            }
        }
        return out.toString();
    }

    /** Just past the end tag {@code </name>} found first from {@code from}, or -1 when there is none. */
    private static int endOfElement(String html, String name, int from) {
        for (int at = html.indexOf("</", from); at >= 0; at = html.indexOf("</", at + 2)) {
            if (!html.regionMatches(true, at + 2, name, 0, name.length())) continue;
            int j = at + 2 + name.length();
            while (j < html.length() && isHtmlSpace(html.charAt(j))) j++;
            if (j < html.length() && html.charAt(j) == '>') return j + 1;
        }
        return -1;
    }

    /** Whitespace as a regular expression's {@code \s} means it. */
    private static boolean isHtmlSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String decodeEntities(String text) {
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String entity = m.group(1);
            String replacement = null;
            if (entity.startsWith("#")) {
                try {
                    int codePoint = entity.length() > 1 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')
                            ? Integer.parseInt(entity.substring(2), 16)
                            : Integer.parseInt(entity.substring(1));
                    if (codePoint == 0 || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                        // Not characters, as HTML reads them: NUL and half of a UTF-16 pair.
                        replacement = "\uFFFD";
                    } else if (Character.isValidCodePoint(codePoint)) {
                        replacement = new String(Character.toChars(codePoint));
                    }
                } catch (NumberFormatException ignored) {
                    // Left as written.
                }
            } else {
                replacement = NAMED_ENTITIES.get(entity);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? m.group() : replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}

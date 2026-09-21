package com.geneinvoice.mail.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * {@code mail.*}, checked as it is bound, so a missing setting stops startup naming the variable to
 * set rather than every call failing later. The secrets are rejected as global errors, which Spring
 * Boot reports without the value; a field error would print the rejected key in the startup log.
 * No {@code toString}, for the same reason.
 */
@ConfigurationProperties(prefix = "mail")
@Getter
@Setter
public class MailProperties implements Validator {

    public enum Queue { RABBIT, DIRECT }

    static final int MIN_SECRET_LENGTH = 16;
    static final int SECRETS_KEY_BYTES = 32;
    /** Gmail refuses a message over 25 MB; base64 makes a file a third larger, so 25 MB × 3/4 of file. */
    public static final long PROVIDER_MESSAGE_LIMIT_BYTES = 25_000_000L;
    public static final long MAX_ATTACHMENT_BYTES_LIMIT = PROVIDER_MESSAGE_LIMIT_BYTES * 3 / 4;

    /** The key the backend sends in {@code X-Api-Key}. */
    private String apiKey;
    /** Base64 of the 32-byte AES key that seals each connection's client secret and refresh token. */
    private String secretsKey;
    private Queue queue = Queue.RABBIT;
    private Webhook webhook = new Webhook();
    private Send send = new Send();
    private Tracking tracking = new Tracking();
    private Sync sync = new Sync();
    private Google google = new Google();

    @Getter
    @Setter
    public static class Webhook {
        /** Where events go; blank keeps them waiting in the outbox. */
        private String url;
        private String secret;
        private long intervalMs = 1000;
        private int batchSize = 100;
    }

    @Getter
    @Setter
    public static class Send {
        private int concurrency = 4;
        private int maxConcurrency = 8;
        /** Gmail's per-user rate limit: one send per mailbox this often at most. */
        private long perMailboxIntervalMs = 500;
        private int maxAttempts = 3;
        private long sweepIntervalMs = 30000;
        /** The wait after the first failed attempt, then after every later one (§4.5). */
        private List<Duration> retryDelays = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));
        /** How many files one email may carry; the backend's compose form offers at most ten. */
        private int maxAttachments = 10;
        /**
         * The largest one email's files may be in total, counted as the raw bytes before encoding.
         *
         * <p>Gmail refuses a message over 25 MB, and it measures the message as it goes out — that
         * is, <em>after</em> base64 has turned every 3 bytes of a file into 4, making it a third
         * larger. So the ceiling on the raw bytes is 25 MB × 3/4 ≈ 18.75 MB, and something has to
         * be left over for the headers, the body and the multipart boundaries. The default is
         * 17 MiB: about 24.4 MB once encoded, comfortably inside Gmail's limit. 18 MiB would
         * already come to 25.2 MB and be refused by Gmail <em>after</em> this service had accepted
         * the email and told the backend it was queued, which is the failure worth avoiding.
         *
         * <p>Raising it is only safe while {@code value × 4 / 3} stays under the provider's own
         * limit, which {@link MailProperties#validate} checks. Zero turns attachments off.
         */
        private long maxAttachmentBytes = 17L * 1024 * 1024;
        /**
         * The largest message that goes to Gmail as JSON. Google takes a message two ways:
         * {@code messages.send} with the message base64url'd inside a JSON body, which is what this
         * service has always used and which Google refuses above about 5 MB of request; and the
         * upload URI, which takes the message as {@code message/rfc822} bytes and takes 35 MB. The
         * JSON form inflates the message by another third, so a 5 MB request carries only about a
         * 3.7 MB message — far below the 25 MB Gmail itself allows, and below what one attached
         * invoice and a scan can come to.
         *
         * <p>Anything over this goes through the upload URI instead, where nothing is inflated.
         * The default leaves room under Google's 5 MB for the JSON around the message. Zero sends
         * everything through the upload URI.
         */
        private long maxJsonSendBytes = 3L * 1024 * 1024;

        /** The wait before the next attempt, after {@code attempts} have failed. */
        public Duration retryDelay(int attempts) {
            return attempts <= 1 ? retryDelays.get(0) : retryDelays.get(retryDelays.size() - 1);
        }
    }

    @Getter
    @Setter
    public static class Tracking {
        private long intervalMs = 60000;
        /** A copy nothing bounced within this long counts as delivered (estimated). */
        private Duration deliveredAfter = Duration.ofMinutes(15);
        /** How long after sending a copy is looked for in the recipient's own mailbox. */
        private Duration confirmWindow = Duration.ofHours(24);
    }

    @Getter
    @Setter
    public static class Sync {
        private boolean enabled = true;
        private long intervalMs = 60000;
    }

    @Getter
    @Setter
    public static class Google {
        private String apiBaseUrl = "https://gmail.googleapis.com";
        private String tokenUrl = "https://oauth2.googleapis.com/token";
        private String tokeninfoUrl = "https://oauth2.googleapis.com/tokeninfo";
        private String revokeUrl = "https://oauth2.googleapis.com/revoke";
    }

    public boolean webhookEnabled() {
        return !blank(webhook.url);
    }

    /** The AES key, once it has passed {@link #validate}. */
    public byte[] secretsKeyBytes() {
        return Base64.getDecoder().decode(secretsKey.trim());
    }

    @Override
    public boolean supports(Class<?> type) {
        return MailProperties.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        MailProperties p = (MailProperties) target;
        if (blank(p.apiKey) || p.apiKey.trim().length() < MIN_SECRET_LENGTH) {
            errors.reject("apiKey", "Set MAIL_API_KEY to a key of at least " + MIN_SECRET_LENGTH
                    + " characters; the backend sends the same key (MAIL_SERVICE_API_KEY)");
        }
        if (!validSecretsKey(p.secretsKey)) {
            errors.reject("secretsKey", "Set MAIL_SECRETS_KEY to the base64 of " + SECRETS_KEY_BYTES
                    + " random bytes, e.g. the output of: openssl rand -base64 32");
        }
        if (p.webhookEnabled() && (blank(p.webhook.secret) || p.webhook.secret.trim().length() < MIN_SECRET_LENGTH)) {
            errors.reject("webhookSecret", "Set MAIL_WEBHOOK_SECRET to at least " + MIN_SECRET_LENGTH
                    + " characters when MAIL_WEBHOOK_URL is set; the backend checks with the same secret"
                    + " (MAIL_SERVICE_WEBHOOK_SECRET)");
        }
        if (p.queue == null) errors.rejectValue("queue", "required", "Must be rabbit or direct");
        if (p.webhook.intervalMs < 100) errors.rejectValue("webhook.intervalMs", "range", "Must be at least 100");
        if (p.webhook.batchSize < 1 || p.webhook.batchSize > 1000) {
            errors.rejectValue("webhook.batchSize", "range", "Must be between 1 and 1000");
        }
        if (p.send.concurrency < 1) errors.rejectValue("send.concurrency", "range", "Must be at least 1");
        if (p.send.maxConcurrency < p.send.concurrency) {
            errors.rejectValue("send.maxConcurrency", "range", "Must be at least mail.send.concurrency");
        }
        if (p.send.perMailboxIntervalMs < 0) {
            errors.rejectValue("send.perMailboxIntervalMs", "range", "Must not be negative");
        }
        if (p.send.maxAttempts < 1) errors.rejectValue("send.maxAttempts", "range", "Must be at least 1");
        if (p.send.sweepIntervalMs < 1000) errors.rejectValue("send.sweepIntervalMs", "range", "Must be at least 1000");
        if (p.send.retryDelays == null || p.send.retryDelays.isEmpty() || p.send.retryDelays.size() > 2
                || p.send.retryDelays.stream().anyMatch(d -> d == null || d.toMillis() < 1)) {
            errors.rejectValue("send.retryDelays", "range", "Give one or two positive durations, e.g. PT1M, PT5M");
        }
        if (p.send.maxAttachments < 0) errors.rejectValue("send.maxAttachments", "range", "Must not be negative");
        if (p.send.maxJsonSendBytes < 0) {
            errors.rejectValue("send.maxJsonSendBytes", "range", "Must not be negative");
        }
        if (p.send.maxAttachmentBytes < 0 || p.send.maxAttachmentBytes > MAX_ATTACHMENT_BYTES_LIMIT) {
            errors.rejectValue("send.maxAttachmentBytes", "range", "Must be between 0 and "
                    + MAX_ATTACHMENT_BYTES_LIMIT + ": base64 makes files a third larger as they go out, and Gmail"
                    + " refuses a message over " + PROVIDER_MESSAGE_LIMIT_BYTES + " bytes");
        }
        if (p.tracking.intervalMs < 1000) errors.rejectValue("tracking.intervalMs", "range", "Must be at least 1000");
        if (p.tracking.deliveredAfter == null || p.tracking.deliveredAfter.isNegative()) {
            errors.rejectValue("tracking.deliveredAfter", "range", "Must be a duration such as PT15M");
        }
        if (p.tracking.confirmWindow == null || p.tracking.confirmWindow.isNegative()) {
            errors.rejectValue("tracking.confirmWindow", "range", "Must be a duration such as PT24H");
        }
        if (p.sync.intervalMs < 1000) {
            errors.rejectValue("sync.intervalMs", "range", "Set MAIL_SYNC_INTERVAL_MS to at least 1000 (one second)");
        }
        if (blank(p.google.apiBaseUrl)) errors.rejectValue("google.apiBaseUrl", "required", "Set GMAIL_API_BASE_URL");
        if (blank(p.google.tokenUrl)) errors.rejectValue("google.tokenUrl", "required", "Set GOOGLE_TOKEN_URL");
        if (blank(p.google.tokeninfoUrl)) {
            errors.rejectValue("google.tokeninfoUrl", "required", "Set GOOGLE_TOKENINFO_URL");
        }
        if (blank(p.google.revokeUrl)) errors.rejectValue("google.revokeUrl", "required", "Set GOOGLE_REVOKE_URL");
    }

    private static boolean validSecretsKey(String key) {
        if (blank(key)) return false;
        try {
            return Base64.getDecoder().decode(key.trim()).length == SECRETS_KEY_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

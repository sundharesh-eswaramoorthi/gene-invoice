package com.geneinvoice.email.mailservice;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/**
 * {@code app.mail.service.*}. Only bound when the mail service is the transport, and checked as it
 * is bound, so a missing setting stops startup naming the variable to set — rather than every email
 * failing later with a message nobody reads. The key and the secret are never logged.
 */
@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "mail-service")
@ConfigurationProperties(prefix = "app.mail.service")
@Getter
@Setter
public class MailServiceProperties implements Validator {

    /** Where the mail service listens, e.g. {@code http://localhost:8091}. */
    private String url = "http://localhost:8091";
    /** Sent as {@code X-Api-Key} on every call; the service's {@code MAIL_API_KEY}. */
    private String apiKey;
    /** Signs the service's webhook calls; the service's {@code MAIL_WEBHOOK_SECRET}. */
    private String webhookSecret;
    private long connectTimeoutMs = 5000;
    /** Connecting a Gmail account waits on Google, so this is longer than a hand-off needs. */
    private long readTimeoutMs = 40000;
    /**
     * The largest webhook body read (a batch of events), 32 MiB: well above the service's largest
     * batch (100 events, replies cut to 20,000 characters). The webhook is open to anyone who can
     * reach the backend, and its body is read before the signature can be checked, so a larger one
     * is refused unread (413). Should a real batch ever be refused, lower the service's
     * {@code mail.webhook.batch-size} or raise this.
     */
    private long webhookMaxBytes = MAX_WEBHOOK_BYTES_DEFAULT;

    public static final long MAX_WEBHOOK_BYTES_DEFAULT = 32L * 1024 * 1024;
    private static final long MAX_WEBHOOK_BYTES_LIMIT = 1024L * 1024 * 1024;

    @Override
    public boolean supports(Class<?> type) {
        return MailServiceProperties.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        MailServiceProperties p = (MailServiceProperties) target;
        if (blank(p.url)) {
            errors.rejectValue("url", "required", "Set MAIL_SERVICE_URL to the mail service's address, e.g. http://localhost:8091");
        }
        if (blank(p.apiKey)) {
            errors.rejectValue("apiKey", "required", "Set MAIL_SERVICE_API_KEY to the mail service's MAIL_API_KEY");
        }
        if (blank(p.webhookSecret)) {
            errors.rejectValue("webhookSecret", "required",
                    "Set MAIL_SERVICE_WEBHOOK_SECRET to the mail service's MAIL_WEBHOOK_SECRET");
        }
        if (p.connectTimeoutMs < 1) errors.rejectValue("connectTimeoutMs", "range", "Must be at least 1");
        if (p.readTimeoutMs < 1) errors.rejectValue("readTimeoutMs", "range", "Must be at least 1");
        if (p.webhookMaxBytes < 1024 || p.webhookMaxBytes > MAX_WEBHOOK_BYTES_LIMIT) {
            errors.rejectValue("webhookMaxBytes", "range", "Must be between 1024 and " + MAX_WEBHOOK_BYTES_LIMIT);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

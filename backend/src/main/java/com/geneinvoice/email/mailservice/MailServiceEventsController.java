package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.email.mailservice.MailServiceDtos.Event;
import com.geneinvoice.email.mailservice.MailServiceDtos.EventBatch;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The mail service's webhook (mail-service.md §5.5). It carries no user's token — the service is
 * not a user — so it is open in the security chain and trusts only the signature over the raw body.
 * It exists only when the mail service is the transport.
 */
@RestController
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "mail-service")
@Slf4j
public class MailServiceEventsController {

    public static final String PATH = "/api/mail-service/events";

    /**
     * Bodies read at once. The service sends one batch at a time; anyone else is refused once the
     * signature is checked, but only after their body was read, so this and the size limit bound the
     * memory a caller without the secret can take.
     */
    static final int READERS = 2;
    private static final long READER_WAIT_MS = 2000;

    private final MailServiceProperties properties;
    private final MailServiceEventHandler handler;
    private final ObjectMapper json;
    private final Semaphore readers = new Semaphore(READERS);

    public MailServiceEventsController(MailServiceProperties properties, MailServiceEventHandler handler,
                                       ObjectMapper json) {
        this.properties = properties;
        this.handler = handler;
        this.json = json;
    }

    /**
     * Applies the events in order, each on its own. One that cannot be applied is logged and passed
     * over; a database that cannot be reached stops the batch with 503, and the service sends it again.
     * The body is read only from a call that could be the service's — both headers, a recent
     * timestamp, a declared size within {@code app.mail.service.webhook-max-bytes} — and never past
     * that size, whatever the call says.
     */
    @PostMapping(PATH)
    public ResponseEntity<Map<String, Object>> events(
            @RequestHeader(value = "X-Mail-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Mail-Signature", required = false) String signature,
            HttpServletRequest request) {
        if (signature == null || !WebhookSignature.timely(timestamp, Instant.now())) return invalidSignature();
        long maxBytes = properties.getWebhookMaxBytes();
        if (request.getContentLengthLong() > maxBytes) return tooLarge();
        byte[] body;
        try {
            if (!readers.tryAcquire(READER_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("message", "Busy; send the events again"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Busy; send the events again"));
        }
        try {
            try {
                body = readAtMost(request.getInputStream(), maxBytes);
            } catch (IOException e) {
                log.warn("The mail service's events could not be read: {}", e.getMessage());
                return ResponseEntity.badRequest().body(Map.of("message", "The events cannot be read"));
            }
            if (body == null) return tooLarge();
            // Trimmed as the service trims it before signing, so a stray space in either's setting does not matter.
            String secret = properties.getWebhookSecret() == null ? null : properties.getWebhookSecret().trim();
            if (!WebhookSignature.verify(secret, timestamp, signature, body)) return invalidSignature();
        } finally {
            readers.release();
        }
        List<Event> events;
        try {
            EventBatch batch = json.readValue(body, EventBatch.class);
            events = batch == null || batch.events() == null ? List.of() : batch.events();
        } catch (IOException e) {
            log.warn("The mail service sent events that cannot be read: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "The events cannot be read"));
        }
        int processed = 0;
        for (Event event : events) {
            try {
                handler.apply(event);
                processed++;
            } catch (RuntimeException e) {
                if (MailServiceEventHandler.databaseUnavailable(e)) {
                    log.warn("Stopped at mail service event {} ({}): the database is unavailable: {}",
                            event.id(), event.type(), e.getMessage());
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(Map.of("message", "The database is unavailable; send the events again"));
                }
                log.warn("Mail service event {} ({}) could not be applied and is passed over", event.id(), event.type(), e);
            }
        }
        return ResponseEntity.ok(Map.of("processed", processed));
    }

    private static ResponseEntity<Map<String, Object>> invalidSignature() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid signature"));
    }

    private ResponseEntity<Map<String, Object>> tooLarge() {
        log.warn("Refused a mail service events call larger than app.mail.service.webhook-max-bytes ({} bytes)",
                properties.getWebhookMaxBytes());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "The events are too large"));
    }

    /** The whole stream, or null when it is longer than {@code max} bytes; never more than that is held. */
    static byte[] readAtMost(InputStream in, long max) throws IOException {
        byte[] read = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
        return read.length > max ? null : read;
    }
}

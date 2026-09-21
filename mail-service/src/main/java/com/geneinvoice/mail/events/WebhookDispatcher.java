package com.geneinvoice.mail.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.config.MailProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@Slf4j
public class WebhookDispatcher {

    static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    static final Duration LONGEST_WAIT = Duration.ofMinutes(5);
    static final Duration KEEP_DELIVERED = Duration.ofDays(7);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BATCHES_PER_RUN = 10;
    private static final int ANSWER_EXCERPT = 200;

    private final MailProperties properties;
    private final MailEventRepository events;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Duration wait;
    private Instant nextTryAt = Instant.MIN;

    public WebhookDispatcher(MailProperties properties, MailEventRepository events, TransactionTemplate transactions,
                             ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.events = events;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mail.webhook.interval-ms:1000}", initialDelayString = "${mail.webhook.interval-ms:1000}")
    public void run() {
        try {
            dispatch();
        } catch (RuntimeException e) {
            log.warn("Delivering mail events failed", e);
        }
    }

    public synchronized int dispatch() {
        if (!properties.webhookEnabled()) return 0;
        if (clock.instant().isBefore(nextTryAt)) return 0;
        int batchSize = properties.getWebhook().getBatchSize();
        int delivered = 0;
        for (int run = 0; run < MAX_BATCHES_PER_RUN; run++) {
            List<MailEvent> batch = transactions.execute(status -> events.findUndelivered(PageRequest.of(0, batchSize)));
            if (batch == null || batch.isEmpty()) break;
            List<Long> ids = batch.stream().map(MailEvent::getId).toList();
            String failure = post(batch);
            if (failure != null) {
                transactions.executeWithoutResult(status -> events.markFailed(ids, MailText.fit(failure, MailEvent.ERROR_MAX)));
                wait = wait == null ? FIRST_WAIT : min(wait.multipliedBy(2), LONGEST_WAIT);
                nextTryAt = clock.instant().plus(wait);
                log.warn("The backend did not take {} mail event(s); trying again in {} s: {}",
                        ids.size(), wait.toSeconds(), failure);
                break;
            }
            transactions.executeWithoutResult(status -> events.markDelivered(ids, clock.instant()));
            wait = null;
            nextTryAt = Instant.MIN;
            delivered += ids.size();
            if (batch.size() < batchSize) break;
        }
        return delivered;
    }

    public synchronized Duration currentWait() {
        return wait;
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT10M")
    public void deleteDelivered() {
        Integer deleted = transactions.execute(status ->
                events.deleteDeliveredBefore(clock.instant().minus(KEEP_DELIVERED)));
        if (deleted != null && deleted > 0) log.info("Deleted {} delivered mail event(s) older than a week", deleted);
    }

    private String post(List<MailEvent> batch) {
        String body;
        try {
            body = body(batch);
        } catch (JsonProcessingException e) {
            return "An event could not be read: " + e.getOriginalMessage();
        }
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getWebhook().getUrl().trim()))
                    .timeout(READ_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header(WebhookSigner.TIMESTAMP_HEADER, timestamp)
                    .header(WebhookSigner.SIGNATURE_HEADER,
                            WebhookSigner.sign(properties.getWebhook().getSecret().trim(), timestamp, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) return null;
            String answer = response.body() == null ? "" : response.body().strip();
            return "The webhook answered " + response.statusCode()
                    + (answer.isEmpty() ? "" : ": " + MailText.fit(answer, ANSWER_EXCERPT));
        } catch (IOException | IllegalArgumentException e) {
            return "Could not reach the webhook: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while delivering";
        }
    }

    private String body(List<MailEvent> batch) throws JsonProcessingException {
        ObjectNode root = json.createObjectNode();
        ArrayNode list = root.putArray("events");
        for (MailEvent event : batch) {
            ObjectNode item = list.addObject();
            item.put("id", event.getId());
            item.put("type", event.getType());
            item.put("occurredAt", event.getCreatedAt().toString());
            item.set("data", json.readTree(event.getPayload()));
        }
        return json.writeValueAsString(root);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}

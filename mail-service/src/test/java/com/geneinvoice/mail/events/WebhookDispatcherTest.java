package com.geneinvoice.mail.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.mail.FakeWebhook;
import com.geneinvoice.mail.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The outbox delivered to the backend (§4.8): signed, in order, in batches, and patiently. */
class WebhookDispatcherTest extends IntegrationTestBase {

    private static final String SECRET = "test-webhook-secret-0123456789";
    static final FakeWebhook backend = FakeWebhook.start();

    @Autowired WebhookDispatcher dispatcher;
    @Autowired EventRecorder recorder;
    @Autowired TransactionTemplate transactions;

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void pointAtTheBackend() {
        backend.reset();
        properties.getWebhook().setUrl(backend.url());
        properties.getWebhook().setBatchSize(100);
    }

    private void record(int count) {
        for (int i = 1; i <= count; i++) {
            int n = i;
            transactions.executeWithoutResult(status ->
                    recorder.record("message.status", "7", "gi-91-" + n, map("externalId", "gi-91-" + n, "seq", n)));
        }
    }

    private static String hmac(String secret, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void eventsGoOutSignedAndInTheOrderTheyHappened() throws Exception {
        connect("7", "Jane Doe", "jane@gmail.com");
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob", "bob@acme.com")));

        assertThat(dispatcher.dispatch()).isEqualTo(2);

        FakeWebhook.Delivery delivery = backend.deliveries().get(0);
        assertThat(delivery.contentType()).startsWith("application/json");
        assertThat(delivery.timestamp()).isEqualTo(String.valueOf(T0.getEpochSecond()));
        assertThat(delivery.signature()).isEqualTo("sha256=" + hmac(SECRET, delivery.timestamp() + "." + delivery.body()));
        assertThat(WebhookSigner.verify(SECRET, delivery.timestamp(), delivery.signature(), delivery.body())).isTrue();

        JsonNode events = delivery.json().get("events");
        List<MailEvent> stored = eventRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId())).toList();
        assertThat(events).hasSize(2);
        for (int i = 0; i < 2; i++) {
            JsonNode event = events.get(i);
            assertThat(event.get("id").asLong()).isEqualTo(stored.get(i).getId());
            assertThat(event.get("type").asText()).isEqualTo(stored.get(i).getType());
            assertThat(event.get("occurredAt").asText()).isEqualTo(T0.toString());
            assertThat(event.get("data")).isEqualTo(payload(stored.get(i)));
        }
        assertThat(events).extracting(e -> e.get("type").asText()).containsExactly("connection.status", "message.status");
        assertThat(events.get(1).get("data").get("externalId").asText()).isEqualTo("gi-91-1");
        assertThat(eventRepository.findAll()).allSatisfy(e -> assertThat(e.getDeliveredAt()).isEqualTo(T0));

        // Delivered once only.
        assertThat(dispatcher.dispatch()).isZero();
        assertThat(backend.deliveries()).hasSize(1);
    }

    @Test
    void aBacklogGoesOutInBatchesOfTheConfiguredSize() {
        properties.getWebhook().setBatchSize(2);
        record(5);

        assertThat(dispatcher.dispatch()).isEqualTo(5);

        List<List<String>> batches = new ArrayList<>();
        for (FakeWebhook.Delivery delivery : backend.deliveries()) {
            List<String> ids = new ArrayList<>();
            delivery.json().get("events").forEach(e -> ids.add(e.get("data").get("externalId").asText()));
            batches.add(ids);
        }
        assertThat(batches).containsExactly(List.of("gi-91-1", "gi-91-2"), List.of("gi-91-3", "gi-91-4"), List.of("gi-91-5"));
    }

    @Test
    void aBackendThatRefusesIsTriedAgainAfterAWaitThatDoublesUpToFiveMinutes() {
        record(1);
        backend.answer(503);

        assertThat(dispatcher.dispatch()).isZero();
        MailEvent failed = eventRepository.findAll().get(0);
        assertThat(failed.getDeliveredAt()).isNull();
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("The webhook answered 503: {\"processed\":0}");
        assertThat(dispatcher.currentWait()).isEqualTo(Duration.ofSeconds(1));

        // Waiting: nothing is sent until the wait is over.
        assertThat(dispatcher.dispatch()).isZero();
        assertThat(backend.deliveries()).hasSize(1);

        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            clock.advance(dispatcher.currentWait());
            dispatcher.dispatch();
            waits.add(dispatcher.currentWait().toSeconds());
        }
        assertThat(waits).containsExactly(2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 300L, 300L, 300L);
        assertThat(eventRepository.findAll().get(0).getAttempts()).isEqualTo(12);

        backend.answer(200);
        clock.advance(Duration.ofMinutes(4));
        assertThat(dispatcher.dispatch()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(dispatcher.dispatch()).isEqualTo(1);
        assertThat(dispatcher.currentWait()).isNull();
        assertThat(eventRepository.findAll().get(0).getDeliveredAt()).isNotNull();
    }

    @Test
    void aBackendOutOfReachIsTriedAgainToo() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        properties.getWebhook().setUrl("http://127.0.0.1:" + closedPort + "/api/mail-service/events");
        record(1);

        assertThat(dispatcher.dispatch()).isZero();

        assertThat(eventRepository.findAll().get(0).getLastError()).startsWith("Could not reach the webhook");
        assertThat(dispatcher.currentWait()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void withoutAWebhookEventsWait() {
        properties.getWebhook().setUrl(" ");
        record(2);

        assertThat(dispatcher.dispatch()).isZero();

        assertThat(backend.deliveries()).isEmpty();
        assertThat(eventRepository.findAll()).allSatisfy(e -> {
            assertThat(e.getDeliveredAt()).isNull();
            assertThat(e.getAttempts()).isZero();
        });
    }

    @Test
    void deliveredEventsAreKeptAWeek() {
        record(3);
        dispatcher.dispatch();
        clock.advance(Duration.ofDays(3));
        record(1);
        dispatcher.dispatch();
        backend.answer(500);
        record(1);
        dispatcher.dispatch();

        clock.advance(Duration.ofDays(4).plusSeconds(1));
        dispatcher.deleteDelivered();

        assertThat(eventRepository.findAll()).extracting(MailEvent::getExternalId)
                .containsExactlyInAnyOrder("gi-91-1", "gi-91-1");
        assertThat(eventRepository.findAll()).filteredOn(e -> e.getDeliveredAt() == null).hasSize(1);
    }
}

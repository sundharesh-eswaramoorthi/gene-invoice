package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.email.RecipientDeliveryStatus;
import com.geneinvoice.email.transport.ConnectionState;
import com.geneinvoice.email.transport.ConnectionStatus;
import com.geneinvoice.email.transport.CopyRequest;
import com.geneinvoice.email.transport.CopyState;
import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.email.transport.MailSendException;
import com.geneinvoice.email.transport.Submission;
import com.geneinvoice.email.transport.SyncResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailServiceClientTest {

    private static final String API_KEY = "test-api-key-0123456789";
    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    record Request(String method, String path, String apiKey, String contentType, String body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }

    record Reply(int status, String body, long delayMs) {}

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private MailServiceClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-mail-service");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        client = client("http://127.0.0.1:" + server.getAddress().getPort() + "/", 5000);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static MailServiceClient client(String url, long readTimeoutMs) {
        MailServiceProperties properties = new MailServiceProperties();
        properties.setUrl(url);
        properties.setApiKey(API_KEY);
        properties.setWebhookSecret("test-webhook-secret-0123456789");
        properties.setConnectTimeoutMs(2000);
        properties.setReadTimeoutMs(readTimeoutMs);
        return new MailServiceClient(properties, JSON);
    }

    private void on(String method, String path, int status, String body) {
        replies.put(method + " " + path, new Reply(status, body, 0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Api-Key"), exchange.getRequestHeaders().getFirst("Content-Type"),
                body));
        Reply reply = replies.getOrDefault(key, new Reply(404, "{\"status\":404,\"message\":\"Not found\"}", 0));
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : bytes.length);
        if (reply.status() != 204) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static Submission submission() {
        return new Submission(7, "Jane Doe", "Invoice INV-0042", "Please pay", "91", true,
                List.of(new CopyRequest("gi-91-501", "Bob Smith", "bob@acme.test"),
                        new CopyRequest("gi-91-502", "Ann Lee", "ann@acme.test")));
    }

    @Test
    void aHandOffPostsTheCopiesWithTheApiKeyAndReadsWhereEachStands() throws Exception {
        on("POST", "/api/v1/messages", 202, """
                {"copies": [
                  {"externalId": "gi-91-501", "groupRef": "91", "seq": 1, "status": "QUEUED", "error": null, "attempts": 0,
                   "fromAddress": null, "sentAt": null, "deliveredAt": null, "deliveredConfirmed": false, "readAt": null,
                   "bouncedAt": null, "providerMessageId": null, "providerThreadId": null, "rfcMessageId": "<gm-1@gmail.com>"},
                  {"externalId": "gi-91-502", "groupRef": "91", "seq": 5, "status": "DELIVERED", "attempts": 1,
                   "fromAddress": "jane@gmail.com", "sentAt": "2026-09-20T10:00:00Z", "deliveredAt": "2026-09-20T10:15:00Z",
                   "deliveredConfirmed": true, "providerMessageId": "18c2", "providerThreadId": "18c2t", "extra": "ignored"}
                ]}
                """);

        List<CopyState> states = client.submit(submission());

        Request sent = requests.get(0);
        assertThat(sent.apiKey()).isEqualTo(API_KEY);
        assertThat(sent.contentType()).isEqualTo("application/json");
        JsonNode body = sent.json();
        assertThat(body.at("/sender/ownerRef").asText()).isEqualTo("7");
        assertThat(body.at("/sender/name").asText()).isEqualTo("Jane Doe");
        assertThat(body.get("subject").asText()).isEqualTo("Invoice INV-0042");
        assertThat(body.get("body").asText()).isEqualTo("Please pay");
        assertThat(body.get("groupRef").asText()).isEqualTo("91");
        assertThat(body.get("retry").asBoolean()).isTrue();
        assertThat(body.at("/copies/1/externalId").asText()).isEqualTo("gi-91-502");
        assertThat(body.at("/copies/1/to/name").asText()).isEqualTo("Ann Lee");
        assertThat(body.at("/copies/1/to/address").asText()).isEqualTo("ann@acme.test");

        assertThat(states).extracting(CopyState::status)
                .containsExactly(RecipientDeliveryStatus.QUEUED, RecipientDeliveryStatus.DELIVERED);
        assertThat(states.get(0).rfcMessageId()).isEqualTo("<gm-1@gmail.com>");
        assertThat(states.get(1).seq()).isEqualTo(5);
        assertThat(states.get(1).deliveredAt()).isEqualTo(Instant.parse("2026-09-20T10:15:00Z"));
        assertThat(states.get(1).deliveredConfirmed()).isTrue();
        assertThat(states.get(1).fromAddress()).isEqualTo("jane@gmail.com");
    }

    @Test
    void aFailedHandOffSaysWhetherTryingAgainCanHelp() throws Exception {
        on("POST", "/api/v1/messages", 503, "{\"status\":503,\"message\":\"Queue down\"}");
        assertThatThrownBy(() -> client.submit(submission())).isInstanceOfSatisfying(MailSendException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("The mail service is unavailable (503)");
            assertThat(e.isTransientFailure()).isTrue();
        });

        on("POST", "/api/v1/messages", 400, """
                {"status":400,"error":"Bad Request","message":"Enter a subject; The body is too long",
                 "fieldErrors":{"subject":"Enter a subject","body":"The body is too long"}}
                """);
        assertThatThrownBy(() -> client.submit(submission())).isInstanceOfSatisfying(MailSendException.class, e -> {
            assertThat(e.getMessage()).isEqualTo(
                    "The mail service refused the email: Enter a subject; The body is too long");
            assertThat(e.isTransientFailure()).isFalse();
        });

        on("POST", "/api/v1/messages", 400, "{\"status\":400,\"fieldErrors\":{\"subject\":\"Enter a subject\"}}");
        assertThatThrownBy(() -> client.submit(submission()))
                .hasMessage("The mail service refused the email: Enter a subject");

        on("POST", "/api/v1/messages", 401, "");
        assertThatThrownBy(() -> client.submit(submission()))
                .hasMessage("The mail service refused the email: HTTP 401");

        on("POST", "/api/v1/messages", 202, "<html>proxy</html>");
        assertThatThrownBy(() -> client.submit(submission())).isInstanceOfSatisfying(MailSendException.class, e -> {
            assertThat(e.getMessage()).startsWith("Unexpected answer from the mail service");
            assertThat(e.isTransientFailure()).isTrue();
        });

        replies.put("POST /api/v1/messages", new Reply(202, "{\"copies\":[]}", 1500));
        assertThatThrownBy(() -> client("http://127.0.0.1:" + server.getAddress().getPort(), 200).submit(submission()))
                .isInstanceOfSatisfying(MailSendException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Could not reach the mail service: the request timed out");
                    assertThat(e.isTransientFailure()).isTrue();
                });
    }

    @Test
    void aServiceThatIsNotThereIsATransientFailure() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        MailServiceClient nowhere = client("http://127.0.0.1:" + closed, 2000);

        assertThatThrownBy(() -> nowhere.submit(submission())).isInstanceOfSatisfying(MailSendException.class, e -> {
            assertThat(e.getMessage()).startsWith("Could not reach the mail service: ");
            assertThat(e.isTransientFailure()).isTrue();
        });
        assertThatThrownBy(() -> nowhere.connection(7)).isInstanceOfSatisfying(MailConnectException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(503);
            assertThat(e.getMessage()).startsWith("Could not reach the mail service: ");
        });
        assertThat(nowhere.syncNow(7)).satisfies(r -> {
            assertThat(r.enabled()).isTrue();
            assertThat(r.error()).startsWith("Could not reach the mail service: ");
        });
    }

    private static final String CONNECTED = """
            {"ownerRef": "7", "ownerName": "Jane Doe", "status": "CONNECTED", "gmailAddress": "jane@gmail.com",
             "clientId": "123-abc.apps.googleusercontent.com",
             "scopes": ["https://www.googleapis.com/auth/gmail.send", "https://www.googleapis.com/auth/gmail.readonly"],
             "statusReason": null, "connectedAt": "2026-09-20T10:00:00Z", "lastSyncedAt": null, "lastSyncError": null}
            """;

    @Test
    void connectingPassesTheThreeValuesThroughAndReadsTheConnectionBack() throws Exception {
        on("PUT", "/api/v1/connections/7", 200, CONNECTED);

        ConnectionState state = client.connect(7, "Jane Doe", "123-abc.apps.googleusercontent.com", "s3cret", "1//refresh");

        Request put = requests.get(0);
        assertThat(put.apiKey()).isEqualTo(API_KEY);
        JsonNode body = put.json();
        assertThat(body.get("ownerName").asText()).isEqualTo("Jane Doe");
        assertThat(body.get("clientId").asText()).isEqualTo("123-abc.apps.googleusercontent.com");
        assertThat(body.get("clientSecret").asText()).isEqualTo("s3cret");
        assertThat(body.get("refreshToken").asText()).isEqualTo("1//refresh");
        assertThat(state.status()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(state.gmailAddress()).isEqualTo("jane@gmail.com");
        assertThat(state.scopes()).hasSize(2);
        assertThat(state.connectedAt()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
        assertThat(new MailServiceDtos.ConnectRequest("Jane", "id", "s3cret", "1//refresh").toString())
                .doesNotContain("s3cret", "1//refresh");
    }

    @Test
    void googlesRefusalComesBackAsTheServiceWordedItAndAnyOtherFailureAsTheServiceBeingUnreachable() {
        String refused = "Google did not accept the refresh token (invalid_grant: Bad Request). Make sure it was made"
                + " with this client ID and secret, and has not expired or been revoked.";
        on("PUT", "/api/v1/connections/7", 400, "{\"status\":400,\"error\":\"Bad Request\",\"message\":\"" + refused + "\"}");
        assertThatThrownBy(() -> client.connect(7, "Jane", "id", "secret", "token"))
                .isInstanceOfSatisfying(MailConnectException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo(refused);
                });

        on("PUT", "/api/v1/connections/7", 502, "{\"status\":502,\"message\":\"Could not reach Google: timed out\"}");
        assertThatThrownBy(() -> client.connect(7, "Jane", "id", "secret", "token"))
                .isInstanceOfSatisfying(MailConnectException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(502);
                    assertThat(e.getMessage()).isEqualTo("Could not reach Google: timed out");
                });

        on("PUT", "/api/v1/connections/7", 401,
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Missing or invalid API key\"}");
        assertThatThrownBy(() -> client.connect(7, "Jane", "id", "secret", "token"))
                .isInstanceOfSatisfying(MailConnectException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(503);
                    assertThat(e.getMessage()).isEqualTo("Could not reach the mail service: Missing or invalid API key (401)");
                });

        on("GET", "/api/v1/connections/7", 500, "");
        assertThatThrownBy(() -> client.connection(7)).isInstanceOfSatisfying(MailConnectException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(503);
            assertThat(e.getMessage()).isEqualTo("Could not reach the mail service: HTTP 500");
        });
    }

    @Test
    void lookingUpDisconnectingAndReadingNow() {
        assertThat(client.connection(7)).isEmpty();

        on("GET", "/api/v1/connections/7", 200, CONNECTED);
        assertThat(client.connection(7)).get().satisfies(s -> assertThat(s.ownerRef()).isEqualTo("7"));

        on("DELETE", "/api/v1/connections/7", 204, null);
        client.disconnect(7);
        assertThat(requests).extracting(Request::method).contains("DELETE");

        on("POST", "/api/v1/connections/7/sync", 200,
                "{\"enabled\": true, \"fetched\": 4, \"imported\": 2, \"error\": null}");
        assertThat(client.syncNow(7)).isEqualTo(new SyncResult(true, 4, 2, null));

        on("POST", "/api/v1/connections/7/sync", 200,
                "{\"enabled\": false, \"fetched\": 0, \"imported\": 0, \"error\": \"Gmail is not connected\"}");
        assertThat(client.syncNow(7)).isEqualTo(new SyncResult(false, 0, 0, "Gmail is not connected"));
        assertThat(requests).allSatisfy(r -> assertThat(r.apiKey()).isEqualTo(API_KEY));
    }
}

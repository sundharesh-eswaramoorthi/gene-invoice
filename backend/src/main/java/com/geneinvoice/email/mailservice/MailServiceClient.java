package com.geneinvoice.email.mailservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.email.mailservice.MailServiceDtos.ConnectRequest;
import com.geneinvoice.email.mailservice.MailServiceDtos.ErrorBody;
import com.geneinvoice.email.mailservice.MailServiceDtos.SubmitRequest;
import com.geneinvoice.email.mailservice.MailServiceDtos.SubmitResponse;
import com.geneinvoice.email.transport.ConnectionState;
import com.geneinvoice.email.transport.CopyState;
import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.email.transport.MailConnections;
import com.geneinvoice.email.transport.MailSendException;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.email.transport.Submission;
import com.geneinvoice.email.transport.SyncResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The mail service over REST (mail-service.md §4.3): sends go through its queue, and each user's
 * Gmail connection lives there. On the JDK client, which never repeats a POST by itself — though a
 * repeated hand-off would be harmless, as the service keys every copy by its {@code externalId}.
 * Request bodies are never logged: a connect carries the user's secrets.
 */
@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "mail-service")
public class MailServiceClient implements MailTransport, MailConnections {

    static final String UNREACHABLE = "Could not reach the mail service: ";

    private final ObjectMapper json;
    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final Duration readTimeout;

    public MailServiceClient(MailServiceProperties properties, ObjectMapper json) {
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String url = properties.getUrl().trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = properties.getApiKey().trim();
        this.readTimeout = Duration.ofMillis(properties.getReadTimeoutMs());
    }

    private record Answer(int status, String body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    // ---- sending ---------------------------------------------------------------------

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<CopyState> submit(Submission submission) {
        Answer answer;
        try {
            answer = call("POST", "/api/v1/messages", SubmitRequest.of(submission));
        } catch (IOException e) {
            throw new MailSendException(UNREACHABLE + detail(e), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailSendException(UNREACHABLE + "interrupted", true, e);
        }
        if (answer.status() >= 500) {
            throw new MailSendException("The mail service is unavailable (" + answer.status() + ")", true);
        }
        if (answer.status() >= 400) {
            throw new MailSendException("The mail service refused the email: " + message(answer), false);
        }
        if (!answer.ok()) {
            throw new MailSendException("Unexpected answer from the mail service (" + answer.status() + ")", false);
        }
        try {
            SubmitResponse response = json.readValue(answer.body(), SubmitResponse.class);
            return response == null || response.copies() == null ? List.of() : response.copies();
        } catch (JsonProcessingException e) {
            // The service has the copies; handing them over again is harmless.
            throw new MailSendException("Unexpected answer from the mail service: " + e.getOriginalMessage(), true, e);
        }
    }

    // ---- connections -------------------------------------------------------------------

    @Override
    public ConnectionState connect(long userId, String name, String clientId, String clientSecret,
                                   String refreshToken) {
        Answer answer = connectionCall("PUT", connectionPath(userId),
                new ConnectRequest(name, clientId, clientSecret, refreshToken));
        if (!answer.ok()) throw failure(answer);
        return read(answer, ConnectionState.class);
    }

    @Override
    public Optional<ConnectionState> connection(long userId) {
        Answer answer = connectionCall("GET", connectionPath(userId), null);
        if (answer.status() == 404) return Optional.empty();
        if (!answer.ok()) throw failure(answer);
        return Optional.of(read(answer, ConnectionState.class));
    }

    @Override
    public void disconnect(long userId) {
        Answer answer = connectionCall("DELETE", connectionPath(userId), null);
        if (!answer.ok()) throw failure(answer);
    }

    @Override
    public SyncResult syncNow(long userId) {
        try {
            Answer answer = connectionCall("POST", connectionPath(userId) + "/sync", null);
            if (!answer.ok()) return new SyncResult(true, 0, 0, failure(answer).getMessage());
            return read(answer, SyncResult.class);
        } catch (MailConnectException e) {
            return new SyncResult(true, 0, 0, e.getMessage());
        }
    }

    private static String connectionPath(long userId) {
        return "/api/v1/connections/" + userId;
    }

    private Answer connectionCall(String method, String path, Object body) {
        try {
            return call(method, path, body);
        } catch (IOException e) {
            throw new MailConnectException(503, UNREACHABLE + detail(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailConnectException(503, UNREACHABLE + "interrupted");
        }
    }

    /**
     * Google refused (400) or could not be reached (502): the service's words, as they are. Anything
     * else means the service itself is not answering as it should, which the user cannot fix.
     */
    private MailConnectException failure(Answer answer) {
        if (answer.status() == 400 || answer.status() == 502) {
            return new MailConnectException(answer.status(), message(answer));
        }
        String said = serviceMessage(answer);
        return new MailConnectException(503, UNREACHABLE
                + (said == null ? "HTTP " + answer.status() : said + " (" + answer.status() + ")"));
    }

    private <T> T read(Answer answer, Class<T> type) {
        try {
            return json.readValue(answer.body(), type);
        } catch (JsonProcessingException e) {
            throw new MailConnectException(503, "Unexpected answer from the mail service: " + e.getOriginalMessage());
        }
    }

    // ---- HTTP ----------------------------------------------------------------------

    private Answer call(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(readTimeout)
                .header("X-Api-Key", apiKey)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        }
        HttpResponse<String> response = http.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Answer(response.statusCode(), response.body());
    }

    /** The service's own message; the status when it gave none. */
    private String message(Answer answer) {
        String said = serviceMessage(answer);
        return said == null ? "HTTP " + answer.status() : said;
    }

    /**
     * The service's {@code message}, which already joins the messages of its {@code fieldErrors}
     * (mail-service.md §4.3); the field errors alone when it has no message.
     */
    private String serviceMessage(Answer answer) {
        if (answer.body() == null || answer.body().isBlank()) return null;
        try {
            ErrorBody error = json.readValue(answer.body(), ErrorBody.class);
            if (error == null) return null;
            if (error.message() != null && !error.message().isBlank()) return error.message();
            Map<String, String> fields = error.fieldErrors();
            if (fields != null && !fields.isEmpty()) {
                return fields.values().stream().distinct().collect(Collectors.joining("; "));
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Not the service's error shape; the status says enough.
        }
        return null;
    }

    /** The JDK's own words for a network failure, or plain words when it has none. */
    static String detail(IOException e) {
        if (e instanceof HttpConnectTimeoutException) return "the connection timed out";
        if (e instanceof HttpTimeoutException) return "the request timed out";
        if (e.getMessage() != null && !e.getMessage().isBlank()) return e.getMessage();
        if (e instanceof ConnectException) return "the connection was refused";
        return "a network error";
    }
}

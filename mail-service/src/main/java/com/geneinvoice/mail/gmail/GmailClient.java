package com.geneinvoice.mail.gmail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.MailConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

@Component
public class GmailClient {

    private static final String SERVICE = "Gmail";

    private final MailProperties properties;
    private final GoogleTokens tokens;
    private final RestClient http = GmailHttp.restClient();

    public GmailClient(MailProperties properties, GoogleTokens tokens) {
        this.properties = properties;
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendResult(String id, String threadId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(String emailAddress, String historyId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageRef(String id, String threadId, List<String> labelIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageAdded(MessageRef message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelsRemoved(MessageRef message, List<String> labelIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record History(String id, List<MessageAdded> messagesAdded, List<LabelsRemoved> labelsRemoved) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryPage(List<History> history, String nextPageToken, String historyId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessagePage(List<MessageRef> messages, String nextPageToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadMessages(String id, List<MessageRef> messages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageInfo(String id, String threadId, List<String> labelIds, Long sizeEstimate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawMessage(String id, String threadId, List<String> labelIds, String internalDate, String raw) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Header(String name, String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(List<Header> headers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MetadataMessage(Payload payload) {}

    public SendResult send(MailConnection mailbox, byte[] mime) {
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime);
        return call(mailbox, token -> http.post()
                .uri(uri("/messages/send", Map.of()))
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("raw", raw))
                .retrieve()
                .body(SendResult.class));
    }

    public Profile profile(MailConnection mailbox) {
        return call(mailbox, this::profileWith);
    }

    public Profile profile(String accessToken) {
        try {
            return profileWith(accessToken);
        } catch (RestClientException e) {
            throw GmailApiException.of(SERVICE, e);
        }
    }

    private Profile profileWith(String token) {
        return http.get()
                .uri(uri("/profile", Map.of()))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(Profile.class);
    }

    public HistoryPage history(MailConnection mailbox, String startHistoryId, String pageToken) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("startHistoryId", startHistoryId);
        query.add("historyTypes", "messageAdded");
        query.add("historyTypes", "labelRemoved");
        if (pageToken != null) query.add("pageToken", pageToken);
        return call(mailbox, token -> http.get()
                .uri(buildUri("/history", query, Map.of()))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(HistoryPage.class));
    }

    public MessagePage listMessages(MailConnection mailbox, String search, String pageToken, boolean includeSpamTrash) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("q", search);
        if (includeSpamTrash) query.add("includeSpamTrash", "true");
        if (pageToken != null) query.add("pageToken", pageToken);
        return call(mailbox, token -> http.get()
                .uri(buildUri("/messages", query, Map.of()))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(MessagePage.class));
    }

    public MessageInfo messageInfo(MailConnection mailbox, String id) {
        return call(mailbox, token -> http.get()
                .uri(uri("/messages/{id}", Map.of("format", "metadata"), Map.of("id", id)))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(MessageInfo.class));
    }

    public ThreadMessages thread(MailConnection mailbox, String threadId) {
        return call(mailbox, token -> http.get()
                .uri(uri("/threads/{id}", Map.of("format", "minimal"), Map.of("id", threadId)))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(ThreadMessages.class));
    }

    public RawMessage rawMessage(MailConnection mailbox, String id) {
        return call(mailbox, token -> http.get()
                .uri(uri("/messages/{id}", Map.of("format", "raw"), Map.of("id", id)))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(RawMessage.class));
    }

    public Map<String, String> headers(MailConnection mailbox, String id, List<String> names) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("format", "metadata");
        names.forEach(name -> query.add("metadataHeaders", name));
        MetadataMessage message = call(mailbox, token -> http.get()
                .uri(buildUri("/messages/{id}", query, Map.of("id", id)))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(MetadataMessage.class));
        Map<String, String> headers = new HashMap<>();
        if (message == null || message.payload() == null || message.payload().headers() == null) return headers;
        for (Header header : message.payload().headers()) {
            if (header.name() != null && header.value() != null && !header.value().isBlank()) {
                headers.putIfAbsent(header.name().toLowerCase(Locale.ROOT), header.value().trim());
            }
        }
        return headers;
    }

    private <T> T call(MailConnection mailbox, Function<String, T> request) {
        try {
            return request.apply(tokens.accessToken(mailbox));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) throw GmailApiException.of(SERVICE, e);
            tokens.forget(mailbox.getId());
        } catch (RestClientException e) {
            throw GmailApiException.of(SERVICE, e);
        }
        try {
            return request.apply(tokens.accessToken(mailbox));
        } catch (RestClientException e) {
            throw GmailApiException.of(SERVICE, e);
        }
    }

    private URI uri(String path, Map<String, String> query) {
        return uri(path, query, Map.of());
    }

    private URI uri(String path, Map<String, String> query, Map<String, String> pathVariables) {
        MultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        query.forEach((name, value) -> {
            if (value != null) values.add(name, value);
        });
        return buildUri(path, values, pathVariables);
    }

    private URI buildUri(String path, MultiValueMap<String, String> query, Map<String, String> pathVariables) {
        String base = properties.getGoogle().getApiBaseUrl().trim().replaceAll("/+$", "");
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base).path("/gmail/v1/users/me" + path);
        Map<String, Object> variables = new HashMap<>(pathVariables);
        query.forEach((name, list) -> {
            for (int i = 0; i < list.size(); i++) {
                String variable = "q_" + name + "_" + i;
                builder.queryParam(name, "{" + variable + "}");
                variables.put(variable, list.get(i));
            }
        });
        return builder.encode().buildAndExpand(variables).toUri();
    }
}

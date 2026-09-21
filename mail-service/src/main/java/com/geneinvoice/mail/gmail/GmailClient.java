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

/**
 * The few Gmail API calls the service makes, each on one connection's mailbox ({@code users/me} with
 * that connection's access token). Failures come out as {@link GmailApiException}, already sorted
 * into transient and permanent; a refused refresh token as {@link GoogleAuthException}.
 */
@Component
public class GmailClient {

    private static final String SERVICE = "Gmail";
    /** What a whole email is, as Gmail's upload URI wants it labelled. */
    private static final MediaType RFC822 = MediaType.parseMediaType("message/rfc822");

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

    /** Labels taken off a message: {@code labelIds} were removed, {@code message.labelIds} are what it has left. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelsRemoved(MessageRef message, List<String> labelIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record History(String id, List<MessageAdded> messagesAdded, List<LabelsRemoved> labelsRemoved) {}

    /** One page of mailbox changes; {@code historyId} is where the mailbox stands now. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryPage(List<History> history, String nextPageToken, String historyId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessagePage(List<MessageRef> messages, String nextPageToken) {}

    /** A thread's messages, oldest first, each with only its id and labels. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThreadMessages(String id, List<MessageRef> messages) {}

    /** A message's current labels and its size in bytes, without its content. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageInfo(String id, String threadId, List<String> labelIds, Long sizeEstimate) {}

    /** {@code raw} is the whole RFC 822 message, base64url; {@code internalDate} is epoch millis. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawMessage(String id, String threadId, List<String> labelIds, String internalDate, String raw) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Header(String name, String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payload(List<Header> headers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MetadataMessage(Payload payload) {}

    /**
     * {@code users.messages.send} with the message as written, headers and all.
     *
     * <p>A small message goes as JSON with the bytes base64url'd into {@code raw}, which is how
     * this service has always sent. A message with files on it is another matter: base64 inside the
     * message has already made it a third bigger, the JSON body makes it a third bigger again, and
     * Google refuses an ordinary request of about 5 MB or more. Over
     * {@code mail.send.max-json-send-bytes} the message therefore goes to the upload URI instead,
     * as {@code message/rfc822} bytes with nothing wrapped around them — the same call Gmail's own
     * documentation points at for anything sizeable, and good for 35 MB.
     */
    public SendResult send(MailConnection mailbox, byte[] mime) {
        if (mime.length > properties.getSend().getMaxJsonSendBytes()) return sendAsMedia(mailbox, mime);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime);
        return call(mailbox, token -> http.post()
                .uri(uri("/messages/send", Map.of()))
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("raw", raw))
                .retrieve()
                .body(SendResult.class));
    }

    /** The same send, through the upload URI: the message itself is the body. */
    private SendResult sendAsMedia(MailConnection mailbox, byte[] mime) {
        return call(mailbox, token -> http.post()
                .uri(uploadUri())
                .headers(h -> h.setBearerAuth(token))
                .contentType(RFC822)
                .body(mime)
                .retrieve()
                .body(SendResult.class));
    }

    public Profile profile(MailConnection mailbox) {
        return call(mailbox, this::profileWith);
    }

    /** The profile with a token just granted, before there is a connection to cache it for. */
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

    /**
     * Messages added, and labels removed, since {@code startHistoryId}. Mail taken out of Spam or Trash,
     * and mail read, show only as a removed label. A 404 means Gmail no longer keeps history that old.
     */
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

    /** Message ids matching a Gmail search, newest first; with {@code includeSpamTrash}, Spam and Trash too. */
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

    /** A message's labels and size, which say whether to download it at all. */
    public MessageInfo messageInfo(MailConnection mailbox, String id) {
        return call(mailbox, token -> http.get()
                .uri(uri("/messages/{id}", Map.of("format", "metadata"), Map.of("id", id)))
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(MessageInfo.class));
    }

    /** The messages of a thread, ids and labels only ({@code format=minimal}). A 404: the thread is gone. */
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

    /**
     * Headers of a stored message as Gmail keeps them, by lower-case name (the first value of each).
     * What Gmail sent can differ from what the service wrote: it may put its own Message-ID, and it
     * puts the account's address in From when the one there is not one of its aliases.
     */
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

    /** Runs a call with the mailbox's token, and once more with a fresh one when Google says the token is no good. */
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

    /** Gmail's media-upload address, which lives under {@code /upload} rather than beside the rest. */
    private URI uploadUri() {
        String base = properties.getGoogle().getApiBaseUrl().trim().replaceAll("/+$", "");
        return UriComponentsBuilder.fromUriString(base)
                .path("/upload/gmail/v1/users/me/messages/send")
                .queryParam("uploadType", "media")
                .encode().build().toUri();
    }

    /** A query parameter that is null is left out. */
    private URI uri(String path, Map<String, String> query, Map<String, String> pathVariables) {
        MultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        query.forEach((name, value) -> {
            if (value != null) values.add(name, value);
        });
        return buildUri(path, values, pathVariables);
    }

    /** Values go in as URI variables, so a search or page token is encoded rather than read as syntax. */
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

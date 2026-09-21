package com.geneinvoice.mail.gmail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.connection.SecretBox;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GoogleTokens {

    static final String SIGN_IN = "Google sign-in";
    static final String GOOGLE = "Google";
    private static final Set<String> AUTH_ERRORS = Set.of("invalid_grant", "invalid_client", "unauthorized_client",
            "deleted_client", "disabled_client");
    private static final Duration EARLY_REFRESH = Duration.ofSeconds(60);
    private static final long DEFAULT_LIFETIME_SECONDS = 3600;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MailProperties properties;
    private final SecretBox secrets;
    private final Clock clock;
    private final RestClient http = GmailHttp.restClient();
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private final Map<Long, Object> refreshing = new ConcurrentHashMap<>();

    public record Grant(String accessToken, long expiresInSeconds, String scope) {}

    private record Cached(long version, String token, Instant refreshAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") Long expiresIn,
                         String scope) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenInfo(String scope) {}

    public GoogleTokens(MailProperties properties, SecretBox secrets, Clock clock) {
        this.properties = properties;
        this.secrets = secrets;
        this.clock = clock;
    }

    public Grant refresh(String clientId, String clientSecret, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        TokenResponse response;
        try {
            response = http.post()
                    .uri(properties.getGoogle().getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException e) {
            throw classified(e);
        }
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new GmailApiException(SIGN_IN + " returned no access token", 0, false, null);
        }
        long lifetime = response.expiresIn() == null ? DEFAULT_LIFETIME_SECONDS : response.expiresIn();
        return new Grant(response.accessToken(), lifetime, response.scope());
    }

    public String accessToken(MailConnection connection) {
        long id = connection.getId();
        long version = connection.getVersion() == null ? 0 : connection.getVersion();
        synchronized (refreshing.computeIfAbsent(id, key -> new Object())) {
            Instant now = clock.instant();
            Cached cached = cache.get(id);
            if (cached != null && cached.version() == version && now.isBefore(cached.refreshAt())) return cached.token();

            String clientSecret;
            String refreshToken;
            try {
                clientSecret = secrets.open(connection.getClientSecretEnc());
                refreshToken = secrets.open(connection.getRefreshTokenEnc());
            } catch (SecretBox.UnreadableSecretException e) {
                throw GoogleAuthException.unreadableSecrets();
            }
            Grant grant = refresh(connection.getClientId(), clientSecret, refreshToken);
            remember(connection, grant, now);
            return grant.accessToken();
        }
    }

    public void remember(MailConnection connection, Grant grant, Instant grantedAt) {
        long version = connection.getVersion() == null ? 0 : connection.getVersion();
        Instant refreshAt = grantedAt.plusSeconds(grant.expiresInSeconds()).minus(EARLY_REFRESH);
        cache.put(connection.getId(), new Cached(version, grant.accessToken(), refreshAt));
    }

    public void forget(long connectionId) {
        cache.remove(connectionId);
    }

    public List<String> scopes(Grant grant) {
        String scope = grant.scope();
        if (scope == null) {
            try {
                TokenInfo info = http.get()
                        .uri(UriComponentsBuilder.fromUriString(properties.getGoogle().getTokeninfoUrl())
                                .queryParam("access_token", "{token}")
                                .encode().buildAndExpand(grant.accessToken()).toUri())
                        .retrieve()
                        .body(TokenInfo.class);
                scope = info == null ? null : info.scope();
            } catch (RestClientException e) {
                throw GmailApiException.of(GOOGLE, e);
            }
        }
        return scope == null ? List.of() : Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isEmpty()).toList();
    }

    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        try {
            http.post()
                    .uri(properties.getGoogle().getRevokeUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw GmailApiException.of(GOOGLE, e);
        }
    }

    private static RuntimeException classified(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            int code = response.getStatusCode().value();
            if (code >= 400 && code < 500) {
                JsonNode body = json(response.getResponseBodyAsString());
                String error = body.path("error").isTextual() ? body.path("error").asText() : null;
                if (error != null && AUTH_ERRORS.contains(error)) {
                    String description = body.path("error_description").isTextual()
                            ? body.path("error_description").asText() : null;
                    return new GoogleAuthException(error, description);
                }
                if (code != 429) return GmailApiException.of(SIGN_IN, e);
            }
        }
        return GmailApiException.of(GOOGLE, e);
    }

    private static JsonNode json(String body) {
        try {
            return body == null || body.isBlank() ? JSON.missingNode() : JSON.readTree(body);
        } catch (Exception e) {
            return JSON.missingNode();
        }
    }
}

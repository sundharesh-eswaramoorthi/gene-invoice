package com.geneinvoice.mail.gmail;

import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.FakeGoogle.Reply;
import com.geneinvoice.mail.MovableClock;
import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.connection.SecretBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleTokensTest {

    private static final Instant START = Instant.parse("2026-09-20T09:00:00Z");

    FakeGoogle google;
    MovableClock clock;
    SecretBox secrets;
    GoogleTokens tokens;

    @BeforeEach
    void start() {
        google = FakeGoogle.start();
        clock = new MovableClock(START);
        MailProperties properties = new MailProperties();
        properties.setSecretsKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        properties.getGoogle().setTokenUrl(google.tokenUrl());
        properties.getGoogle().setTokeninfoUrl(google.tokeninfoUrl());
        properties.getGoogle().setRevokeUrl(google.revokeUrl());
        secrets = new SecretBox(properties);
        tokens = new GoogleTokens(properties, secrets, clock);
    }

    @AfterEach
    void stop() {
        google.close();
    }

    private MailConnection connection(long id, long version, String refreshToken) {
        return MailConnection.builder()
                .id(id)
                .version(version)
                .ownerRef(String.valueOf(id))
                .clientId("client-1.apps.googleusercontent.com")
                .clientSecretEnc(secrets.seal("secret-1"))
                .refreshTokenEnc(secrets.seal(refreshToken))
                .build();
    }

    @Test
    void aTokenIsFetchedOnceAndReusedUntilAMinuteBeforeItExpires() {
        google.account("jane@gmail.com", "1//refresh-1");
        MailConnection jane = connection(1, 0, "1//refresh-1");

        assertThat(tokens.accessToken(jane)).isEqualTo("tok-1");
        assertThat(google.requests("POST", "/token")).singleElement().satisfies(request -> {
            assertThat(request.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
            assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "grant_type", "refresh_token",
                    "client_id", "client-1.apps.googleusercontent.com",
                    "client_secret", "secret-1",
                    "refresh_token", "1//refresh-1"));
        });

        clock.set(START.plusSeconds(3599 - 61));
        assertThat(tokens.accessToken(jane)).isEqualTo("tok-1");
        clock.set(START.plusSeconds(3599 - 60));
        assertThat(tokens.accessToken(jane)).isEqualTo("tok-2");
        assertThat(google.requests("POST", "/token")).hasSize(2);
    }

    @Test
    void aReconnectOrARefusedTokenNeverReusesTheOldOne() {
        google.account("jane@gmail.com", "1//refresh-1");
        google.account("jane@gmail.com", "1//refresh-2");

        assertThat(tokens.accessToken(connection(1, 0, "1//refresh-1"))).isEqualTo("tok-1");
        assertThat(tokens.accessToken(connection(1, 1, "1//refresh-2"))).isEqualTo("tok-2");
        assertThat(google.requests("POST", "/token").get(1).form().get("refresh_token")).isEqualTo("1//refresh-2");

        tokens.forget(1);
        assertThat(tokens.accessToken(connection(1, 1, "1//refresh-2"))).isEqualTo("tok-3");
    }

    @Test
    void eachConnectionHasItsOwnToken() {
        google.account("jane@gmail.com", "1//jane");
        google.account("sam@gmail.com", "1//sam");

        assertThat(tokens.accessToken(connection(1, 0, "1//jane"))).isEqualTo("tok-1");
        assertThat(tokens.accessToken(connection(2, 0, "1//sam"))).isEqualTo("tok-2");
        assertThat(tokens.accessToken(connection(1, 0, "1//jane"))).isEqualTo("tok-1");
    }

    @Test
    void aTokenGrantedWhileConnectingIsKept() {
        MailConnection jane = connection(1, 3, "1//refresh-1");
        tokens.remember(jane, new GoogleTokens.Grant("tok-connect", 3600, null), START);

        assertThat(tokens.accessToken(jane)).isEqualTo("tok-connect");
        assertThat(google.requests("POST", "/token")).isEmpty();
    }

    @Test
    void workersSendingFromOneMailboxShareOneRefresh() throws Exception {
        google.account("jane@gmail.com", "1//refresh-1");
        google.on("POST", "/token", exchange -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reply(200, "{\"access_token\":\"tok-slow\",\"expires_in\":3600}");
        });
        MailConnection jane = connection(1, 0, "1//refresh-1");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> all = new ArrayList<>();
            for (int i = 0; i < 4; i++) all.add(pool.submit(() -> tokens.accessToken(jane)));
            for (Future<String> f : all) assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("tok-slow");
        } finally {
            pool.shutdownNow();
        }
        assertThat(google.requests("POST", "/token")).hasSize(1);
    }

    @Test
    void credentialsGoogleRefusesNeedAReconnect() {
        google.onSequence("POST", "/token",
                new Reply(400, FakeGoogle.tokenError("invalid_grant", "Token has been expired or revoked.")),
                new Reply(401, FakeGoogle.tokenError("invalid_client", "The OAuth client was not found.")),
                new Reply(400, "{\"error\":\"unauthorized_client\"}"));
        MailConnection jane = connection(1, 0, "1//refresh-1");

        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GoogleAuthException.class, e -> {
            assertThat(e.error()).isEqualTo("invalid_grant");
            assertThat(e.description()).isEqualTo("Token has been expired or revoked.");
            assertThat(e.reconnectReason()).isEqualTo("Google no longer accepts this Gmail connection"
                    + " (invalid_grant: Token has been expired or revoked.). Reconnect Gmail.");
            assertThat(e.connectMessage()).startsWith("Google did not accept the refresh token (invalid_grant: ");
        });
        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GoogleAuthException.class, e ->
                assertThat(e.connectMessage())
                        .isEqualTo("Google did not accept the client ID and secret (invalid_client: The OAuth client was not found.)."));
        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GoogleAuthException.class, e -> {
            assertThat(e.reconnectReason())
                    .isEqualTo("Google no longer accepts this Gmail connection (unauthorized_client). Reconnect Gmail.");
            assertThat(e.connectMessage()).isEqualTo("Google did not accept the client ID and secret (unauthorized_client).");
        });
    }

    @Test
    void anOAuthClientDeletedOrDisabledNeedsAReconnect() {
        google.onSequence("POST", "/token",
                new Reply(401, FakeGoogle.tokenError("deleted_client", "The OAuth client was deleted.")),
                new Reply(401, FakeGoogle.tokenError("disabled_client", "The OAuth client was disabled.")));
        MailConnection jane = connection(1, 0, "1//refresh-1");

        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GoogleAuthException.class, e -> {
            assertThat(e.error()).isEqualTo("deleted_client");
            assertThat(e.reconnectReason()).isEqualTo("Google no longer accepts this Gmail connection"
                    + " (deleted_client: The OAuth client was deleted.). Reconnect Gmail.");
            assertThat(e.connectMessage())
                    .isEqualTo("Google did not accept the client ID and secret (deleted_client: The OAuth client was deleted.).");
        });
        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GoogleAuthException.class, e -> {
            assertThat(e.error()).isEqualTo("disabled_client");
            assertThat(e.connectMessage())
                    .isEqualTo("Google did not accept the client ID and secret (disabled_client: The OAuth client was disabled.).");
        });
    }

    @Test
    void otherFailuresAreSortedIntoPassingAndLasting() {
        google.onSequence("POST", "/token",
                new Reply(400, FakeGoogle.tokenError("invalid_request", "Missing required parameter: refresh_token")),
                new Reply(503, "{\"error\":\"temporarily_unavailable\"}"),
                new Reply(429, "{\"error\":\"rate_limit_exceeded\"}"),
                new Reply(200, "{\"token_type\":\"Bearer\"}"));
        MailConnection jane = connection(1, 0, "1//refresh-1");

        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GmailApiException.class, e -> {
            assertThat(e.isTransientFailure()).isFalse();
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.getMessage()).isEqualTo(
                    "Google sign-in refused the request (400): invalid_request (Missing required parameter: refresh_token)");
        });
        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GmailApiException.class, e -> {
            assertThat(e.isTransientFailure()).isTrue();
            assertThat(e.getMessage()).isEqualTo("Google is unavailable (503): temporarily_unavailable");
        });
        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GmailApiException.class, e -> {
            assertThat(e.isTransientFailure()).isTrue();
            assertThat(e.getMessage()).isEqualTo("Google is rate limiting requests (429): rate_limit_exceeded");
        });
        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GmailApiException.class, e ->
                assertThat(e.getMessage()).isEqualTo("Google sign-in returned no access token"));
    }

    @Test
    void secretsSealedWithAnotherKeyNeedAReconnect() {
        MailConnection jane = connection(1, 0, "1//refresh-1");
        jane.setRefreshTokenEnc("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==");

        assertThatThrownBy(() -> tokens.accessToken(jane)).isInstanceOfSatisfying(GoogleAuthException.class, e -> {
            assertThat(e.error()).isNull();
            assertThat(e.reconnectReason()).isEqualTo("The stored Gmail secrets cannot be read. Reconnect Gmail.");
        });
        assertThat(google.requests("POST", "/token")).isEmpty();
    }

    @Test
    void theScopesComeWithTheTokenOrFromTokeninfo() {
        assertThat(tokens.scopes(new GoogleTokens.Grant("tok-9", 3600,
                " https://www.googleapis.com/auth/gmail.send  https://www.googleapis.com/auth/gmail.readonly ")))
                .containsExactly("https://www.googleapis.com/auth/gmail.send", "https://www.googleapis.com/auth/gmail.readonly");
        assertThat(google.requests("GET", "/tokeninfo")).isEmpty();

        google.on("GET", "/tokeninfo", 200, "{\"scope\":\"https://mail.google.com/\",\"expires_in\":3000}");
        assertThat(tokens.scopes(new GoogleTokens.Grant("tok-9", 3600, null))).containsExactly("https://mail.google.com/");
        assertThat(google.requests("GET", "/tokeninfo").get(0).param("access_token")).isEqualTo("tok-9");

        google.on("GET", "/tokeninfo", 400, "{\"error\":\"invalid_token\",\"error_description\":\"Invalid Value\"}");
        assertThatThrownBy(() -> tokens.scopes(new GoogleTokens.Grant("tok-9", 3600, null)))
                .isInstanceOfSatisfying(GmailApiException.class, e ->
                        assertThat(e.getMessage()).isEqualTo("Google refused the request (400): invalid_token (Invalid Value)"));
    }

    @Test
    void revokingPostsTheRefreshToken() {
        tokens.revoke("1//refresh-1");
        assertThat(google.requests("POST", "/revoke")).singleElement()
                .satisfies(request -> assertThat(request.form()).isEqualTo(Map.of("token", "1//refresh-1")));

        google.on("POST", "/revoke", 400, "{\"error\":\"invalid_token\"}");
        assertThatThrownBy(() -> tokens.revoke("1//refresh-1")).isInstanceOf(GmailApiException.class);
    }
}

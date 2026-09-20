package com.geneinvoice.mail.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.FakeGoogle.Exchange;
import com.geneinvoice.mail.FakeGoogle.Reply;
import com.geneinvoice.mail.IntegrationTestBase;
import com.geneinvoice.mail.events.MailEvent;
import com.geneinvoice.mail.gmail.GoogleTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Connecting a user's Gmail (§4.3, §4.4) through the API, against a stand-in for Google. */
class ConnectionApiTest extends IntegrationTestBase {

    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";
    private static final String READ = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String MAKE_A_NEW_ONE = ". Make a new one with the scopes"
            + " https://www.googleapis.com/auth/gmail.send and https://www.googleapis.com/auth/gmail.readonly.";

    @Autowired SecretBox secrets;
    @Autowired GoogleTokens tokens;

    private static Map<String, Object> body(String clientId, String clientSecret, String refreshToken) {
        return map("ownerName", "Jane Doe", "clientId", clientId, "clientSecret", clientSecret, "refreshToken", refreshToken);
    }

    private JsonNode connectJane(int expectedStatus) throws Exception {
        return read(call(put("/api/v1/connections/7"),
                body(" 123-abc.apps.googleusercontent.com ", " secret-7 ", " 1//refresh-7 "))
                .andExpect(status().is(expectedStatus)));
    }

    @Test
    void connectingChecksTheValuesWithGoogleAndKeepsTheSecretsSealed() throws Exception {
        google.account("Jane@Gmail.com", "1//refresh-7");

        String answer = call(put("/api/v1/connections/7"),
                body(" 123-abc.apps.googleusercontent.com ", " secret-7 ", " 1//refresh-7 "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode dto = objectMapper.readTree(answer);
        assertThat(dto.get("ownerRef").asText()).isEqualTo("7");
        assertThat(dto.get("ownerName").asText()).isEqualTo("Jane Doe");
        assertThat(dto.get("status").asText()).isEqualTo("CONNECTED");
        assertThat(dto.get("gmailAddress").asText()).isEqualTo("jane@gmail.com");
        assertThat(dto.get("clientId").asText()).isEqualTo("123-abc.apps.googleusercontent.com");
        assertThat(dto.get("scopes")).extracting(JsonNode::asText).containsExactly(SEND, READ);
        assertThat(dto.get("statusReason").isNull()).isTrue();
        assertThat(dto.get("connectedAt").asText()).isEqualTo("2026-09-20T10:00:00Z");
        assertThat(dto.get("lastSyncedAt").isNull()).isTrue();
        assertThat(dto.get("lastSyncError").isNull()).isTrue();
        assertThat(answer).doesNotContain("secret-7", "1//refresh-7", "tok-1");

        // The refresh-token grant, trimmed values, then the profile with the token it gave.
        assertThat(google.requests("POST", "/token")).singleElement().satisfies(request -> {
            assertThat(request.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
            assertThat(request.form()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "client_id", "123-abc.apps.googleusercontent.com",
                    "client_secret", "secret-7",
                    "refresh_token", "1//refresh-7",
                    "grant_type", "refresh_token"));
        });
        assertThat(google.requests("GET", FakeGoogle.api("/profile"))).singleElement()
                .satisfies(request -> assertThat(request.header("Authorization")).isEqualTo("Bearer tok-1"));
        // The scope came with the token, so tokeninfo was not needed.
        assertThat(google.requests("GET", "/tokeninfo")).isEmpty();

        MailConnection stored = connection("7");
        assertThat(stored.getClientSecretEnc()).isNotBlank().doesNotContain("secret-7");
        assertThat(stored.getRefreshTokenEnc()).isNotBlank().doesNotContain("refresh-7");
        assertThat(secrets.open(stored.getClientSecretEnc())).isEqualTo("secret-7");
        assertThat(secrets.open(stored.getRefreshTokenEnc())).isEqualTo("1//refresh-7");
        assertThat(stored.getScopes()).isEqualTo(SEND + " " + READ);
        assertThat(stored.getHistoryId()).isEqualTo("1000");

        List<MailEvent> events = events("connection.status");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getOwnerRef()).isEqualTo("7");
            assertThat(payload(event).get("status").asText()).isEqualTo("CONNECTED");
            assertThat(payload(event).get("gmailAddress").asText()).isEqualTo("jane@gmail.com");
            assertThat(event.getPayload()).doesNotContain("secret-7", "refresh-7");
        });

        // The token from connecting is kept for sending.
        assertThat(tokens.accessToken(stored)).isEqualTo("tok-1");
        assertThat(google.requests("POST", "/token")).hasSize(1);

        JsonNode fetched = read(call(get("/api/v1/connections/7")).andExpect(status().isOk()));
        assertThat(fetched).isEqualTo(dto);
    }

    @Test
    void reconnectingReplacesTheConnectionAndNeverUsesTheOldToken() throws Exception {
        google.account("jane@gmail.com", "1//refresh-7");
        connectJane(200);
        MailConnection first = connection("7");
        assertThat(tokens.accessToken(first)).isEqualTo("tok-1");

        google.account("jane.doe@gmail.com", "1//refresh-7b");
        google.historyAt("jane.doe@gmail.com", "7000");
        JsonNode dto = read(call(put("/api/v1/connections/7"),
                body("456-def.apps.googleusercontent.com", "secret-7b", "1//refresh-7b")).andExpect(status().isOk()));

        assertThat(dto.get("gmailAddress").asText()).isEqualTo("jane.doe@gmail.com");
        assertThat(dto.get("clientId").asText()).isEqualTo("456-def.apps.googleusercontent.com");
        MailConnection second = connection("7");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getVersion()).isGreaterThan(first.getVersion());
        assertThat(tokens.accessToken(second)).isEqualTo("tok-2");
        assertThat(events("connection.status")).hasSize(2);
        // Another mailbox: read from where it stands now, not from the old mailbox's place.
        assertThat(second.getHistoryId()).isEqualTo("7000");
    }

    @Test
    void renewingTheSameMailboxKeepsItsPlaceInTheHistory() throws Exception {
        google.account("jane@gmail.com", "1//refresh-7");
        connectJane(200);
        assertThat(connection("7").getHistoryId()).isEqualTo("1000");

        // A week later the token has expired; mail came in meanwhile, and Jane pastes a new token.
        google.account("Jane@Gmail.com", "1//refresh-7-renewed");
        google.historyAt("Jane@Gmail.com", "1500");
        call(put("/api/v1/connections/7"), body("123-abc.apps.googleusercontent.com", "secret-7", "1//refresh-7-renewed"))
                .andExpect(status().isOk());

        // What came in meanwhile is still to be read.
        assertThat(connection("7").getHistoryId()).isEqualTo("1000");
        assertThat(connection("7").getGmailAddress()).isEqualTo("jane@gmail.com");

        // A mailbox that never got a place gets the profile's.
        connectionRepository.findByOwnerRef("7").ifPresent(c -> {
            c.setHistoryId(null);
            connectionRepository.save(c);
        });
        call(put("/api/v1/connections/7"), body("123-abc.apps.googleusercontent.com", "secret-7", "1//refresh-7-renewed"))
                .andExpect(status().isOk());
        assertThat(connection("7").getHistoryId()).isEqualTo("1500");
    }

    @Test
    void theThreeValuesAreRequiredAndLimited() throws Exception {
        JsonNode blank = read(call(put("/api/v1/connections/7"), body(" ", null, ""))
                .andExpect(status().isBadRequest()));

        assertThat(blank.get("status").asInt()).isEqualTo(400);
        assertThat(blank.get("error").asText()).isEqualTo("Bad Request");
        assertThat(blank.get("message").asText())
                .isEqualTo("Enter the client ID; Enter the client secret; Enter the refresh token");
        assertThat(objectMapper.convertValue(blank.get("fieldErrors"), Map.class)).isEqualTo(Map.of(
                "clientId", "Enter the client ID",
                "clientSecret", "Enter the client secret",
                "refreshToken", "Enter the refresh token"));

        JsonNode tooLong = read(call(put("/api/v1/connections/7"),
                body("c".repeat(301), "s".repeat(301), "r".repeat(2001))).andExpect(status().isBadRequest()));
        assertThat(objectMapper.convertValue(tooLong.get("fieldErrors"), Map.class)).isEqualTo(Map.of(
                "clientId", "The client ID is too long",
                "clientSecret", "The client secret is too long",
                "refreshToken", "The refresh token is too long"));

        // At the limits it is accepted, and a long name is cut rather than refused.
        google.account("jane@gmail.com", "r".repeat(2000));
        Map<String, Object> atLimits = body("c".repeat(300), "s".repeat(300), "r".repeat(2000));
        atLimits.put("ownerName", "N".repeat(250));
        JsonNode dto = read(call(put("/api/v1/connections/7"), atLimits).andExpect(status().isOk()));
        assertThat(dto.get("ownerName").asText()).isEqualTo("N".repeat(199) + "…");

        assertThat(google.requests("POST", "/token")).hasSize(1);
        assertThat(read(call(put("/api/v1/connections/" + "x".repeat(65)), body("c", "s", "r"))
                .andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("The owner reference is too long");
    }

    @Test
    void whatGoogleSaysAboutTheCredentialsIsPassedOnAndNothingIsStored() throws Exception {
        google.onSequence("POST", "/token",
                new Reply(400, FakeGoogle.tokenError("invalid_grant", "Token has been expired or revoked.")),
                new Reply(401, FakeGoogle.tokenError("invalid_client", "The OAuth client was not found.")),
                new Reply(400, FakeGoogle.tokenError("unauthorized_client", "Unauthorized")),
                new Reply(400, FakeGoogle.tokenError("invalid_request", "Missing required parameter: refresh_token")),
                new Reply(503, "{\"error\":\"temporarily_unavailable\"}"),
                new Reply(429, "{\"error\":\"rate_limit_exceeded\"}"));

        assertThat(connectJane(400).get("message").asText()).isEqualTo(
                "Google did not accept the refresh token (invalid_grant: Token has been expired or revoked.). Make sure"
                        + " it was made with this client ID and secret, and has not expired or been revoked.");
        assertThat(connectJane(400).get("message").asText())
                .isEqualTo("Google did not accept the client ID and secret (invalid_client: The OAuth client was not found.).");
        assertThat(connectJane(400).get("message").asText())
                .isEqualTo("Google did not accept the client ID and secret (unauthorized_client: Unauthorized).");
        assertThat(connectJane(400).get("message").asText()).isEqualTo(
                "Google sign-in refused the request (400): invalid_request (Missing required parameter: refresh_token)");
        assertThat(connectJane(502).get("message").asText())
                .isEqualTo("Google is unavailable (503): temporarily_unavailable");
        assertThat(connectJane(502).get("message").asText())
                .isEqualTo("Google is rate limiting requests (429): rate_limit_exceeded");

        assertThat(connectionRepository.findAll()).isEmpty();
        assertThat(eventRepository.findAll()).isEmpty();
        assertThat(google.requests("GET", FakeGoogle.api("/profile"))).isEmpty();
    }

    @Test
    void googleOutOfReachIsABadGateway() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String tokenUrl = properties.getGoogle().getTokenUrl();
        properties.getGoogle().setTokenUrl("http://127.0.0.1:" + closedPort + "/token");
        try {
            assertThat(connectJane(502).get("message").asText()).startsWith("Could not reach Google: ");
        } finally {
            properties.getGoogle().setTokenUrl(tokenUrl);
        }
        assertThat(connectionRepository.findAll()).isEmpty();
    }

    @Test
    void theProfileMustBeReadableAndAFailedReconnectLeavesTheConnectionAsItWas() throws Exception {
        google.account("jane@gmail.com", "1//refresh-7");
        connectJane(200);
        MailConnection before = connection("7");

        google.on("GET", FakeGoogle.api("/profile"), 403,
                FakeGoogle.gmailError(403, "Gmail API has not been used in project 123 before or it is disabled."));
        assertThat(connectJane(400).get("message").asText()).isEqualTo(
                "Gmail refused the request (403): Gmail API has not been used in project 123 before or it is disabled.");

        google.on("GET", FakeGoogle.api("/profile"), 500, FakeGoogle.gmailError(500, "Backend Error"));
        assertThat(connectJane(502).get("message").asText()).isEqualTo("Gmail is unavailable (500): Backend Error");

        MailConnection after = connection("7");
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(after.getRefreshTokenEnc()).isEqualTo(before.getRefreshTokenEnc());
        assertThat(events("connection.status")).hasSize(1);
    }

    @Test
    void theTokenMustBeAllowedToSendAndToRead() throws Exception {
        google.account("jane@gmail.com", "1//read-only", READ);
        google.account("jane@gmail.com", "1//send-only", SEND);
        google.account("jane@gmail.com", "1//neither", "openid https://www.googleapis.com/auth/userinfo.email");

        assertThat(read(call(put("/api/v1/connections/7"), body("c", "s", "1//read-only"))
                .andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("This refresh token cannot send mail" + MAKE_A_NEW_ONE);
        assertThat(read(call(put("/api/v1/connections/7"), body("c", "s", "1//send-only"))
                .andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("This refresh token cannot read mail" + MAKE_A_NEW_ONE);
        assertThat(read(call(put("/api/v1/connections/7"), body("c", "s", "1//neither"))
                .andExpect(status().isBadRequest())).get("message").asText())
                .isEqualTo("This refresh token cannot send or read mail" + MAKE_A_NEW_ONE);
        assertThat(connectionRepository.findAll()).isEmpty();

        // Wider scopes do both.
        google.account("jane@gmail.com", "1//full", "https://mail.google.com/");
        call(put("/api/v1/connections/7"), body("c", "s", "1//full")).andExpect(status().isOk());
        google.account("sam@gmail.com", "1//modify", "https://www.googleapis.com/auth/gmail.modify");
        call(put("/api/v1/connections/8"), body("c", "s", "1//modify")).andExpect(status().isOk());
    }

    @Test
    void withoutAScopeInTheTokenAnswerTokeninfoSays() throws Exception {
        google.account("jane@gmail.com", "1//refresh-7", null);

        JsonNode dto = connectJane(200);

        assertThat(dto.get("scopes")).extracting(JsonNode::asText).containsExactly(SEND, READ);
        Exchange tokeninfo = google.requests("GET", "/tokeninfo").get(0);
        assertThat(tokeninfo.param("access_token")).isEqualTo("tok-1");
    }

    @Test
    void connectionsAreListedByOwnerNameAndAnUnknownOwnerIsNotFound() throws Exception {
        connect("8", "Zoe Adams", "zoe@gmail.com");
        connect("7", "Adam Zed", "adam@gmail.com");

        JsonNode list = read(call(get("/api/v1/connections")).andExpect(status().isOk()));
        assertThat(list).extracting(n -> n.get("ownerName").asText()).containsExactly("Adam Zed", "Zoe Adams");
        assertThat(list.toString()).doesNotContain("secret-", "refresh-");

        JsonNode missing = read(call(get("/api/v1/connections/99")).andExpect(status().isNotFound()));
        assertThat(missing.get("message").asText()).isEqualTo("No Gmail connection for 99");
        assertThat(missing.has("fieldErrors")).isFalse();
    }

    @Test
    void disconnectingRevokesTheTokenAndWipesTheSecrets() throws Exception {
        connect("7", "Jane Doe", "jane@gmail.com");

        call(delete("/api/v1/connections/7")).andExpect(status().isNoContent());

        assertThat(google.requests("POST", "/revoke")).singleElement()
                .satisfies(request -> assertThat(request.form()).isEqualTo(Map.of("token", "1//refresh-7")));
        MailConnection gone = connection("7");
        assertThat(gone.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(gone.getClientSecretEnc()).isNull();
        assertThat(gone.getRefreshTokenEnc()).isNull();
        assertThat(gone.getStatusReason()).isNull();
        assertThat(events("connection.status")).extracting(e -> payload(e).get("status").asText())
                .containsExactly("CONNECTED", "DISCONNECTED");

        // Again, or for someone who never connected: nothing to do, and still 204.
        call(delete("/api/v1/connections/7")).andExpect(status().isNoContent());
        call(delete("/api/v1/connections/99")).andExpect(status().isNoContent());
        assertThat(events("connection.status")).hasSize(2);
        assertThat(google.requests("POST", "/revoke")).hasSize(1);
    }

    @Test
    void disconnectingAlsoClearsTheLastCheckAndWhyItFailed() throws Exception {
        connect("7", "Jane Doe", "jane@gmail.com");
        // Reading the mailbox failed at some point — a Gmail 5xx, or Google refusing the token — so
        // the failure sits on the connection and is shown while it stands.
        MailConnection failing = connection("7");
        failing.setLastSyncedAt(T0);
        failing.setLastSyncError("Gmail is unavailable (500): Backend Error");
        connectionRepository.saveAndFlush(failing);
        assertThat(read(call(get("/api/v1/connections/7"))).get("lastSyncError").asText())
                .isEqualTo("Gmail is unavailable (500): Backend Error");

        call(delete("/api/v1/connections/7")).andExpect(status().isNoContent());

        // Nothing reads the mailbox any more: the error belongs to a connection that is gone, and
        // would otherwise be answered beside NOT_CONNECTED to the owner (and its invalid_grant text
        // would tell them to reconnect a connection they just removed).
        JsonNode gone = read(call(get("/api/v1/connections/7")).andExpect(status().isOk()));
        assertThat(gone.get("status").asText()).isEqualTo("DISCONNECTED");
        assertThat(gone.get("lastSyncError").isNull()).isTrue();
        assertThat(gone.get("lastSyncedAt").isNull()).isTrue();

        // A row disconnected before this was so, with the error still on it, is cleaned by asking
        // again — without reporting a status that did not change.
        MailConnection stale = connection("7");
        stale.setLastSyncedAt(T0);
        stale.setLastSyncError("Gmail is unavailable (500): Backend Error");
        connectionRepository.saveAndFlush(stale);

        call(delete("/api/v1/connections/7")).andExpect(status().isNoContent());

        assertThat(connection("7").getLastSyncError()).isNull();
        assertThat(connection("7").getLastSyncedAt()).isNull();
        assertThat(events("connection.status")).extracting(e -> payload(e).get("status").asText())
                .containsExactly("CONNECTED", "DISCONNECTED");
    }

    @Test
    void aRevokeGoogleRefusesStillDisconnects() throws Exception {
        connect("7", "Jane Doe", "jane@gmail.com");
        google.on("POST", "/revoke", 400, "{\"error\":\"invalid_token\"}");

        call(delete("/api/v1/connections/7")).andExpect(status().isNoContent());

        assertThat(connection("7").getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    void syncingSaysWhenTheMailboxIsNotConnected() throws Exception {
        JsonNode never = read(call(post("/api/v1/connections/7/sync")).andExpect(status().isOk()));
        assertThat(objectMapper.convertValue(never, Map.class)).isEqualTo(
                map("enabled", false, "fetched", 0, "imported", 0, "error", "Gmail is not connected"));

        connect("7", "Jane Doe", "jane@gmail.com");
        google.on("GET", FakeGoogle.api("/history"), 200, "{\"historyId\":\"1001\"}");
        JsonNode connected = read(call(post("/api/v1/connections/7/sync")).andExpect(status().isOk()));
        assertThat(objectMapper.convertValue(connected, Map.class)).isEqualTo(
                map("enabled", true, "fetched", 0, "imported", 0, "error", null));

        call(delete("/api/v1/connections/7")).andExpect(status().isNoContent());
        assertThat(read(call(post("/api/v1/connections/7/sync"))).get("error").asText()).isEqualTo("Gmail is not connected");
    }
}
